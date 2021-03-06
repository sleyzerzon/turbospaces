/**
 * Copyright (C) 2011-2012 Andrey Borisov <aandrey.borisov@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turbospaces.spaces;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.concurrent.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ObjectUtils;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.primitives.Ints;
import com.turbospaces.api.JSpace;
import com.turbospaces.api.SpaceConfiguration;
import com.turbospaces.api.SpaceErrors;
import com.turbospaces.api.SpaceNotificationListener;
import com.turbospaces.api.SpaceTopology;
import com.turbospaces.core.Memory;
import com.turbospaces.core.SpaceUtility;
import com.turbospaces.model.BO;
import com.turbospaces.model.CacheStoreEntryWrapper;
import com.turbospaces.offmemory.OffHeapCacheStore;
import com.turbospaces.serialization.SerializationEntry;
import com.turbospaces.spaces.tx.SpaceTransactionHolder;
import com.turbospaces.spaces.tx.TransactionModificationContext;
import com.turbospaces.spaces.tx.WriteTakeEntry;

/**
 * Basic implementation of JSpace specification. You should subclass from this parent instead of implementing
 * {@link JSpace} directly.</p>
 * 
 * This class is not transactional and has nothing to do with transactions at all, this is something that must be
 * covered at the child level.
 * 
 * @since 0.1
 */
@SuppressWarnings("rawtypes")
@ThreadSafe
public abstract class AbstractJSpace implements TransactionalJSpace, SpaceErrors {
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final ConcurrentMap<Class<?>, OffHeapCacheStore> offHeapBuffers;
    private final SpaceConfiguration configuration;
    private final Set<NotificationContext> notificationContext;
    private final SpaceReceiveAdapter messageListener;

    protected AbstractJSpace(final SpaceConfiguration configuration) {
        super();

        this.configuration = configuration;
        offHeapBuffers = SpaceUtility.newCompMap( new Function<Class<?>, OffHeapCacheStore>() {
            @Override
            public OffHeapCacheStore apply(final Class<?> input) {
                OffHeapCacheStore cacheStore = new OffHeapCacheStore( configuration, input );
                cacheStore.afterPropertiesSet();
                return cacheStore;
            }
        } );
        notificationContext = new HashSet<NotificationContext>();
        messageListener = new SpaceReceiveAdapter( this );
    }

    @Override
    public SpaceConfiguration getSpaceConfiguration() {
        return configuration;
    }

    @Override
    public SpaceTopology getSpaceTopology() {
        return configuration.getTopology();
    }

    @Override
    public long size() {
        long size = 0;
        Collection<OffHeapCacheStore> buffers = offHeapBuffers.values();
        for ( OffHeapCacheStore b : buffers )
            size += b.getIndexManager().size();
        return size;
    }

    @Override
    public int mbUsed() {
        long used = 0;
        Collection<OffHeapCacheStore> buffers = offHeapBuffers.values();
        for ( OffHeapCacheStore b : buffers )
            used += b.getIndexManager().offHeapBytesOccuiped();
        return Memory.toMb( used );
    }

    @Override
    public long evictAll() {
        long evicted = 0;
        Collection<OffHeapCacheStore> buffers = offHeapBuffers.values();
        for ( OffHeapCacheStore b : buffers )
            evicted += b.getIndexManager().evictAll();
        logger.info( "evictAll(): {} entries detached from jspace", evicted );
        return evicted;
    }

    @Override
    public long evictPercentage(final int percentage) {
        long evicted = 0;
        Collection<OffHeapCacheStore> buffers = offHeapBuffers.values();
        for ( OffHeapCacheStore b : buffers )
            evicted += b.getIndexManager().evictPercentage( percentage );
        logger.info( "evictPercentage({}): {} entries detached from jspace", percentage, evicted );
        return evicted;
    }

    @Override
    public long evictElements(final long elements) {
        long evicted = 0;
        Collection<OffHeapCacheStore> buffers = offHeapBuffers.values();
        for ( OffHeapCacheStore b : buffers )
            evicted += b.getIndexManager().evictElements( elements );
        logger.info( "evictElements({}): {} entries detached from jspace", elements, evicted );
        return evicted;
    }

    @Override
    public void notify(final Object template,
                       final SpaceNotificationListener listener,
                       final int modifiers) {
        boolean isMatchById = SpaceModifiers.isMatchById( modifiers );
        BO bo = configuration.boFor( template.getClass() );
        CacheStoreEntryWrapper cacheStoreEntryWrapper = CacheStoreEntryWrapper.writeValueOf( bo, template );

        if ( isMatchById && cacheStoreEntryWrapper.getId() == null )
            throw new InvalidDataAccessApiUsageException( String.format(
                    "Illegal attempt to perform matching by ID when id is not provided. Template = %s",
                    template ) );

        this.notificationContext.add( new NotificationContext( cacheStoreEntryWrapper, listener, modifiers ) );
    }

    @Override
    public Object[] fetch(final Object entry,
                          final int timeout,
                          final int maxResults,
                          final int modifiers) {
        SpaceTransactionHolder th = getTransactionHolder();
        return fetch( th, entry, timeout, maxResults, modifiers );
    }

    @Override
    public void write(final Object entry,
                      final int timeToLive,
                      final int timeout,
                      final int modifier) {
        SpaceTransactionHolder th = getTransactionHolder();
        write( th, entry, null, timeToLive, timeout, modifier );
    }

    Object[] fetch(final SpaceTransactionHolder th,
                   final Object entry,
                   final int timeout,
                   final int maxResults,
                   final int modifiers) {
        SpaceStore buffer = storeFor( entry );
        int txTimeout = timeout;
        TransactionModificationContext txModification;

        if ( th != null ) {
            if ( th.hasTimeout() && timeout > th.getTimeToLiveInMillis() )
                txTimeout = Ints.checkedCast( th.getTimeToLiveInMillis() );
            txModification = (TransactionModificationContext) th.getModificationContext();
        }
        else
            txModification = new TransactionModificationContext();

        try {
            return fetch0( txModification, entry, txTimeout, maxResults, modifiers );
        }
        finally {
            if ( th == null )
                if ( txModification.isDirty() )
                    txModification.flush( buffer, notificationContext );
        }
    }

    void write(final SpaceTransactionHolder th,
               final Object entry,
               final byte[] serializedEntry,
               final int timeToLive,
               final int timeout,
               final int modifier) {
        SpaceStore buffer = storeFor( entry );
        int txTimeout = timeout;
        TransactionModificationContext txModification;

        if ( th != null ) {
            if ( th.hasTimeout() && timeout > th.getTimeToLiveInMillis() )
                txTimeout = Ints.checkedCast( th.getTimeToLiveInMillis() );
            txModification = (TransactionModificationContext) th.getModificationContext();
        }
        else
            txModification = new TransactionModificationContext();

        try {
            write0( txModification, entry, serializedEntry, timeToLive, txTimeout, modifier );
        }
        finally {
            if ( th == null )
                if ( txModification.isDirty() )
                    txModification.flush( buffer, notificationContext );
        }
    }

    private void write0(final TransactionModificationContext modificationContext,
                        final Object entry,
                        final byte[] serializedEntry,
                        final int timeToLive,
                        final int timeout,
                        final int modifier) {
        Preconditions.checkNotNull( entry );

        Preconditions.checkArgument( timeToLive >= 0, NEGATIVE_TTL );
        Preconditions.checkArgument( timeout >= 0, NEGATIVE_TIMEOUT );

        Class<?> persistentClass = entry.getClass();
        SpaceStore heapBuffer = offHeapBuffers.get( persistentClass );
        BO bo = configuration.boFor( entry.getClass() );

        boolean isWriteOnly = SpaceModifiers.isWriteOnly( modifier );
        boolean isWriteOrUpdate = SpaceModifiers.isWriteOrUpdate( modifier );
        boolean isUpdateOnly = SpaceModifiers.isUpdateOnly( modifier );

        CacheStoreEntryWrapper cacheStoreEntryWrapper = CacheStoreEntryWrapper.writeValueOf( bo, entry );
        Preconditions.checkNotNull( cacheStoreEntryWrapper.getId(), "ID must be provided before writing to JSpace" );
        cacheStoreEntryWrapper.setBeanAsBytes( serializedEntry );

        if ( isWriteOnly && isUpdateOnly )
            throw new InvalidDataAccessResourceUsageException( String.format(
                    "Illegal attempt to write %s with writeOnly and updateOnly modifiers at the same time",
                    entry ) );

        if ( isWriteOrUpdate && isUpdateOnly )
            throw new InvalidDataAccessResourceUsageException( String.format(
                    "Illegal attempt to write %s with writeOrUpdate and updateOnly modifiers at the same time",
                    entry ) );
        if ( isWriteOrUpdate && isWriteOnly )
            throw new InvalidDataAccessResourceUsageException( String.format(
                    "Illegal attempt to write %s with writeOrUpdate and writeOnly modifiers at the same time",
                    entry ) );

        if ( logger.isDebugEnabled() )
            logger.debug( "onWrite: entity {}, id={}, version={}, routing={} under {} transaction. ttl = {}", new Object[] { entry,
                    cacheStoreEntryWrapper.getId(), cacheStoreEntryWrapper.getOptimisticLockVersion(), cacheStoreEntryWrapper.getRouting(),
                    modificationContext.getTransactionId(), timeToLive } );

        // write
        heapBuffer.write( cacheStoreEntryWrapper, modificationContext, timeToLive, timeout, modifier );
    }

    private Object[] fetch0(final TransactionModificationContext modificationContext,
                            final Object entry,
                            final int timeout,
                            final int maxResults,
                            final int modifiers) {
        Preconditions.checkNotNull( entry );
        Preconditions.checkArgument( maxResults >= 1, NON_POSITIVE_MAX_RESULTS );
        Preconditions.checkArgument( timeout >= 0, NEGATIVE_TIMEOUT );

        SpaceStore heapBuffer;
        CacheStoreEntryWrapper cacheStoreEntryWrapper;
        Object template;

        if ( entry instanceof CacheStoreEntryWrapper ) {
            cacheStoreEntryWrapper = (CacheStoreEntryWrapper) entry;
            heapBuffer = offHeapBuffers.get( cacheStoreEntryWrapper.getPersistentEntity().getOriginalPersistentEntity().getType() );
            template = cacheStoreEntryWrapper.getBean();
        }
        else {
            heapBuffer = offHeapBuffers.get( entry.getClass() );
            cacheStoreEntryWrapper = CacheStoreEntryWrapper.writeValueOf( configuration.boFor( entry.getClass() ), entry );
            template = entry;
        }

        boolean isTakeOnly = SpaceModifiers.isTakeOnly( modifiers );
        boolean isReadOnly = SpaceModifiers.isReadOnly( modifiers );
        boolean isEvictOnly = SpaceModifiers.isEvictOnly( modifiers );
        boolean isExclusiveRead = SpaceModifiers.isExclusiveRead( modifiers );
        boolean isMatchById = SpaceModifiers.isMatchById( modifiers );
        boolean isReturnAsBytes = SpaceModifiers.isReturnAsBytes( modifiers );

        if ( isTakeOnly && isReadOnly )
            throw new InvalidDataAccessResourceUsageException( String.format(
                    "Illegal attempt to fetch by template %s with takeOnly and readOnly modifiers at the same time",
                    template ) );

        if ( isTakeOnly && isEvictOnly )
            throw new InvalidDataAccessResourceUsageException( String.format(
                    "Illegal attempt to fetch by template %s with takeOnly and evictOnly modifiers at the same time",
                    template ) );

        if ( isTakeOnly && isExclusiveRead )
            throw new InvalidDataAccessResourceUsageException( String.format(
                    "Illegal attempt to fetch by template %s with takeOnly and exclusiveReadLock modifiers at the same time",
                    template ) );

        if ( isReadOnly && isEvictOnly )
            throw new InvalidDataAccessResourceUsageException( String.format(
                    "Illegal attempt to fetch by template %s with takeOnly and evictOnly modifiers at the same time",
                    template ) );

        if ( isEvictOnly && isExclusiveRead )
            throw new InvalidDataAccessResourceUsageException( String.format(
                    "Illegal attempt to fetch by template %s with evictOnly and exclusiveReadLock modifiers at the same time",
                    template ) );

        if ( isMatchById && cacheStoreEntryWrapper.getId() == null )
            throw new InvalidDataAccessApiUsageException( String.format(
                    "Illegal attempt to perform matching by ID when id is not provided. Template = %s",
                    template ) );

        if ( logger.isDebugEnabled() )
            logger.debug( "onFetch: template={}, id={}, version={}, routing={}, timeout={}, maxResults={}, transaction={}", new Object[] {
                    cacheStoreEntryWrapper.getBean(), cacheStoreEntryWrapper.getId(), cacheStoreEntryWrapper.getOptimisticLockVersion(),
                    cacheStoreEntryWrapper.getRouting(), timeout, maxResults, modificationContext.getTransactionId() } );

        // fetch
        ByteBuffer[] c = heapBuffer.fetch( cacheStoreEntryWrapper, modificationContext, timeout, maxResults, modifiers );
        if ( c != null )
            if ( !isReturnAsBytes ) {
                int size = c.length;
                Class type = cacheStoreEntryWrapper.getPersistentEntity().getOriginalPersistentEntity().getType();

                Object[] result = new Object[size];
                Map<EntryKeyLockQuard, WriteTakeEntry> takes = modificationContext.getTakes();
                for ( int i = 0; i < size; i++ ) {
                    SerializationEntry sEntry = configuration.getKryo().deserialize( c[i], type );
                    Object[] propertyValues = sEntry.getPropertyValues();
                    Object id = propertyValues[BO.getIdIndex()];

                    if ( !takes.isEmpty() )
                        for ( Entry<EntryKeyLockQuard, WriteTakeEntry> next : takes.entrySet() )
                            if ( ObjectUtils.nullSafeEquals( next.getKey().getKey(), id ) ) {
                                next.getValue().setObj( sEntry.getObject() );
                                next.getValue().setPropertyValues( propertyValues );
                            }

                    result[i] = sEntry.getObject();
                }
                return result;
            }
        return c;
    }

    private SpaceStore storeFor(final Object entry) {
        return offHeapBuffers.get( entry instanceof CacheStoreEntryWrapper ? ( (CacheStoreEntryWrapper) entry )
                .getPersistentEntity()
                .getOriginalPersistentEntity()
                .getType() : entry.getClass() );
    }

    /**
     * synchronize modifications made within transaction modification context over internal space stores.
     * 
     * @param ctx
     *            transaction modifications
     * @param commit
     *            commit or rollback transaction
     */
    @Override
    public void syncTx(final Object ctx,
                       final boolean commit) {
        TransactionModificationContext c = (TransactionModificationContext) ctx;

        for ( OffHeapCacheStore heapBuffer : offHeapBuffers.values() )
            if ( commit )
                c.flush( heapBuffer, notificationContext );
            else
                c.discard( heapBuffer );
    }

    @Override
    public void destroy() {
        try {
            Collection<OffHeapCacheStore> values = offHeapBuffers.values();
            for ( OffHeapCacheStore b : values )
                b.destroy();
            offHeapBuffers.clear();
        }
        finally {
            getSpaceConfiguration().getMessageDispatcher().removeMessageReceiver( messageListener );
            messageListener.destroy();
        }
    }

    @Override
    public void afterPropertiesSet() {
        getSpaceConfiguration().getMessageDispatcher().addMessageReceiver( messageListener );
        messageListener.afterPropertiesSet();
    }

    @Override
    public SpaceTransactionHolder getTransactionHolder() {
        return (SpaceTransactionHolder) TransactionSynchronizationManager.getResource( this );
    }

    @Override
    public void bindTransactionHolder(final SpaceTransactionHolder transactionHolder) {
        TransactionSynchronizationManager.bindResource( this, transactionHolder );
    }

    @Override
    public Object unbindTransactionHolder(final SpaceTransactionHolder transactionHolder) {
        return TransactionSynchronizationManager.unbindResource( this );
    }

    @Override
    public String toString() {
        return Objects.toStringHelper( this ).add( "offHeapBuffers", offHeapBuffers ).toString();
    }
}
