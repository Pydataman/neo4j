/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.index.schema;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ExecutionException;

import org.neo4j.concurrent.Work;
import org.neo4j.concurrent.WorkSync;
import org.neo4j.index.internal.gbptree.GBPTree;
import org.neo4j.index.internal.gbptree.Layout;
import org.neo4j.index.internal.gbptree.RecoveryCleanupWorkCollector;
import org.neo4j.index.internal.gbptree.Writer;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.IOLimiter;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.index.IndexEntryUpdate;
import org.neo4j.kernel.api.index.IndexPopulator;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.api.index.PropertyAccessor;
import org.neo4j.kernel.api.index.SchemaIndexProvider;
import org.neo4j.kernel.api.schema.index.IndexDescriptor;

import static org.neo4j.index.internal.gbptree.GBPTree.NO_HEADER_WRITER;

/**
 * {@link IndexPopulator} backed by a {@link GBPTree}.
 *
 * @param <KEY> type of {@link NativeSchemaKey}.
 * @param <VALUE> type of {@link NativeSchemaValue}.
 */
public abstract class NativeSchemaIndexPopulator<KEY extends NativeSchemaKey, VALUE extends NativeSchemaValue>
        extends NativeSchemaIndex<KEY,VALUE> implements IndexPopulator
{
    static final byte BYTE_FAILED = 0;
    static final byte BYTE_ONLINE = 1;
    static final byte BYTE_POPULATING = 2;

    private final KEY treeKey;
    private final VALUE treeValue;
    private final ConflictDetectingValueMerger<KEY,VALUE> conflictDetectingValueMerger;
    private WorkSync<IndexUpdateApply<KEY,VALUE>,IndexUpdateWork<KEY,VALUE>> workSync;

    private Writer<KEY,VALUE> singleTreeWriter;
    private byte[] failureBytes;
    private boolean dropped;

    NativeSchemaIndexPopulator( PageCache pageCache, FileSystemAbstraction fs, File storeFile, Layout<KEY,VALUE> layout,
            SchemaIndexProvider.Monitor monitor, IndexDescriptor descriptor, long indexId )
    {
        super( pageCache, fs, storeFile, layout, monitor, descriptor, indexId );
        this.treeKey = layout.newKey();
        this.treeValue = layout.newValue();
        this.conflictDetectingValueMerger = new ConflictDetectingValueMerger<>();
    }

    @Override
    public synchronized void create() throws IOException
    {
        gbpTreeFileUtil.deleteFileIfPresent( storeFile );
        instantiateTree( RecoveryCleanupWorkCollector.IMMEDIATE, new NativeSchemaIndexHeaderWriter( BYTE_POPULATING ) );
        instantiateWriter();
        workSync = new WorkSync<>( new IndexUpdateApply<>( treeKey, treeValue, singleTreeWriter, conflictDetectingValueMerger ) );
    }

    void instantiateWriter() throws IOException
    {
        assert singleTreeWriter == null;
        singleTreeWriter = tree.writer();
    }

    @Override
    public synchronized void drop() throws IOException
    {
        try
        {
            closeWriter();
            closeTree();
            gbpTreeFileUtil.deleteFileIfPresent( storeFile );
        }
        finally
        {
            dropped = true;
        }
    }

    @Override
    public void add( Collection<? extends IndexEntryUpdate<?>> updates ) throws IOException
    {
        applyWithWorkSync( updates );
    }

    @Override
    public void verifyDeferredConstraints( PropertyAccessor propertyAccessor )
    {
        // No-op, uniqueness is checked for each update in add(IndexEntryUpdate)
    }

    @Override
    public IndexUpdater newPopulatingUpdater( PropertyAccessor accessor )
    {
        return new IndexUpdater()
        {
            private boolean closed;
            private final Collection<IndexEntryUpdate<?>> updates = new ArrayList<>();

            @Override
            public void process( IndexEntryUpdate<?> update )
            {
                assertOpen();
                updates.add( update );
            }

            @Override
            public void close() throws IOException
            {
                applyWithWorkSync( updates );
                closed = true;
            }

            private void assertOpen()
            {
                if ( closed )
                {
                    throw new IllegalStateException( "Updater has been closed" );
                }
            }
        };
    }

    @Override
    public synchronized void close( boolean populationCompletedSuccessfully ) throws IOException
    {
        closeWriter();
        if ( populationCompletedSuccessfully && failureBytes != null )
        {
            throw new IllegalStateException( "Can't mark index as online after it has been marked as failure" );
        }

        try
        {
            if ( populationCompletedSuccessfully )
            {
                assertPopulatorOpen();
                markTreeAsOnline();
            }
            else
            {
                assertNotDropped();
                ensureTreeInstantiated();
                markTreeAsFailed();
            }
        }
        finally
        {
            closeTree();
        }
    }

    private void applyWithWorkSync( Collection<? extends IndexEntryUpdate<?>> updates ) throws IOException
    {
        try
        {
            workSync.apply( new IndexUpdateWork<>( updates ) );
        }
        catch ( ExecutionException e )
        {
            throw new IOException( e );
        }
    }

    private void assertNotDropped()
    {
        if ( dropped )
        {
            throw new IllegalStateException( "Populator has already been dropped." );
        }
    }

    @Override
    public void markAsFailed( String failure )
    {
        failureBytes = failure.getBytes( StandardCharsets.UTF_8 );
    }

    private void ensureTreeInstantiated() throws IOException
    {
        if ( tree == null )
        {
            instantiateTree( RecoveryCleanupWorkCollector.IGNORE, NO_HEADER_WRITER );
        }
    }

    private void assertPopulatorOpen()
    {
        if ( tree == null )
        {
            throw new IllegalStateException( "Populator has already been closed." );
        }
    }

    private void markTreeAsFailed() throws IOException
    {
        if ( failureBytes == null )
        {
            failureBytes = new byte[0];
        }
        tree.checkpoint( IOLimiter.unlimited(), new FailureHeaderWriter( failureBytes ) );
    }

    private void markTreeAsOnline() throws IOException
    {
        tree.checkpoint( IOLimiter.unlimited(), pc -> pc.putByte( BYTE_ONLINE ) );
    }

    void closeWriter() throws IOException
    {
        singleTreeWriter = closeIfPresent( singleTreeWriter );
    }

    private static class IndexUpdateApply<KEY extends NativeSchemaKey, VALUE extends NativeSchemaValue>
    {
        private final KEY treeKey;
        private final VALUE treeValue;
        private final Writer<KEY,VALUE> writer;
        private final ConflictDetectingValueMerger<KEY,VALUE> conflictDetectingValueMerger;

        IndexUpdateApply( KEY treeKey, VALUE treeValue, Writer<KEY,VALUE> writer,
                ConflictDetectingValueMerger<KEY,VALUE> conflictDetectingValueMerger )
        {
            this.treeKey = treeKey;
            this.treeValue = treeValue;
            this.writer = writer;
            this.conflictDetectingValueMerger = conflictDetectingValueMerger;
        }

        public void process( IndexEntryUpdate<?> indexEntryUpdate ) throws Exception
        {
            NativeSchemaIndexUpdater.processUpdate( treeKey, treeValue, indexEntryUpdate, writer, conflictDetectingValueMerger );
        }
    }

    private static class IndexUpdateWork<KEY extends NativeSchemaKey, VALUE extends NativeSchemaValue>
            implements Work<IndexUpdateApply<KEY,VALUE>,IndexUpdateWork<KEY,VALUE>>
    {
        private final Collection<? extends IndexEntryUpdate<?>> updates;

        IndexUpdateWork( Collection<? extends IndexEntryUpdate<?>> updates )
        {
            this.updates = updates;
        }

        @Override
        public IndexUpdateWork<KEY,VALUE> combine( IndexUpdateWork<KEY,VALUE> work )
        {
            ArrayList<IndexEntryUpdate<?>> combined = new ArrayList<>( updates );
            combined.addAll( work.updates );
            return new IndexUpdateWork<>( combined );
        }

        @Override
        public void apply( IndexUpdateApply<KEY,VALUE> indexUpdateApply ) throws Exception
        {
            for ( IndexEntryUpdate<?> indexEntryUpdate : updates )
            {
                indexUpdateApply.process( indexEntryUpdate );
            }
        }
    }
}
