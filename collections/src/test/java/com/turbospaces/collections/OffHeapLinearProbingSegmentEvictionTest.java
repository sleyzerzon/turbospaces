package com.turbospaces.collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.esotericsoftware.kryo.ObjectBuffer;
import com.esotericsoftware.minlog.Log;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.turbospaces.api.CacheEvictionPolicy;
import com.turbospaces.api.SpaceExpirationListener;
import com.turbospaces.core.EffectiveMemoryManager;
import com.turbospaces.core.UnsafeMemoryManager;
import com.turbospaces.model.BO;
import com.turbospaces.model.CacheStoreEntryWrapper;
import com.turbospaces.model.TestEntity1;
import com.turbospaces.offmemory.ByteArrayPointer;
import com.turbospaces.serialization.DecoratedKryo;
import com.turbospaces.serialization.PropertiesSerializer;

@SuppressWarnings("javadoc")
public class OffHeapLinearProbingSegmentEvictionTest {
    EffectiveMemoryManager memoryManager;
    BO bo;
    DecoratedKryo decoratedKryo;
    ObjectBuffer objectBuffer;
    OffHeapLinearProbingSegment segment;
    PropertiesSerializer propertySerializer;

    @Before
    public void setUp()
                       throws Exception {
        memoryManager = new UnsafeMemoryManager();
        bo = TestEntity1.getPersistentEntity();
        decoratedKryo = new DecoratedKryo();
        BO.registerPersistentClasses( decoratedKryo, bo.getOriginalPersistentEntity() );
        propertySerializer = new PropertiesSerializer( decoratedKryo, bo );
        objectBuffer = new ObjectBuffer( decoratedKryo );
    }

    @After
    public void tearDown() {
        segment.destroy();
    }

    @Test(expected = IllegalStateException.class)
    public void negativeScenario() {
        segment = new OffHeapLinearProbingSegment(
                memoryManager,
                2,
                propertySerializer,
                MoreExecutors.sameThreadExecutor(),
                CacheEvictionPolicy.REJECT );
        segment.evictElements( 2 );
    }

    @Test
    public void testRandomEviction() {
        int size = 12;
        segment = new OffHeapLinearProbingSegment(
                memoryManager,
                size,
                propertySerializer,
                MoreExecutors.sameThreadExecutor(),
                CacheEvictionPolicy.RANDOM );

        for ( int i = 0; i < size; i++ ) {
            TestEntity1 e = new TestEntity1();
            e.afterPropertiesSet();

            byte[] bytes = objectBuffer.writeObjectData( CacheStoreEntryWrapper.writeValueOf( bo, e ) );
            ByteArrayPointer p = new ByteArrayPointer( memoryManager, bytes, e, Integer.MAX_VALUE );

            segment.put( e.getUniqueIdentifier(), p );
        }

        // 25 % = 3
        assertThat( segment.evictPercentage( 25 ), is( 3 ) );
        assertThat( segment.evictElements( 2 ), is( 2 ) );

        // 7?
        assertThat( segment.size(), is( 7 ) );
    }

    @Test
    public void testRandomizeEvictionAfterExpiration()
                                                      throws InterruptedException {
        segment = new OffHeapLinearProbingSegment(
                memoryManager,
                2,
                propertySerializer,
                MoreExecutors.sameThreadExecutor(),
                CacheEvictionPolicy.RANDOM );

        TestEntity1 e = new TestEntity1();
        e.afterPropertiesSet();

        byte[] bytes = objectBuffer.writeObjectData( CacheStoreEntryWrapper.writeValueOf( bo, e ) );
        ByteArrayPointer p = new ByteArrayPointer( memoryManager, bytes, e, 1 );

        segment.put( e.getUniqueIdentifier(), p );
        Thread.sleep( 1 );
        assertThat( segment.evictPercentage( 25 ), is( 0 ) );
    }

    @Test
    public void testLruEviction1()
                                  throws InterruptedException {
        segment = new OffHeapLinearProbingSegment( memoryManager, 2, propertySerializer, MoreExecutors.sameThreadExecutor(), CacheEvictionPolicy.LRU );

        String[] keys = new String[100];
        for ( int i = 0; i < keys.length; i++ ) {
            TestEntity1 entity1 = new TestEntity1();
            entity1.afterPropertiesSet();
            keys[i] = entity1.getUniqueIdentifier();

            byte[] bytes = objectBuffer.writeObjectData( CacheStoreEntryWrapper.writeValueOf( bo, entity1 ) );
            ByteArrayPointer p = new ByteArrayPointer( memoryManager, bytes, entity1, Integer.MAX_VALUE );
            segment.put( keys[i], p );
        }
        Thread.sleep( 2 );
        for ( int i = 0; i < keys.length / 2; i++ )
            assertThat( segment.getAsPointer( keys[i] ), is( notNullValue() ) );
        Thread.sleep( 1 );
        segment.evictPercentage( 50 );
        for ( int i = 0; i < keys.length / 2; i++ )
            assertThat( segment.getAsPointer( keys[i] ), is( notNullValue() ) );
        for ( int i = keys.length / 2; i < keys.length; i++ )
            assertThat( segment.getAsPointer( keys[i] ), is( nullValue() ) );
    }

    @Test
    public void testLruEviction2()
                                  throws InterruptedException {
        segment = new OffHeapLinearProbingSegment( memoryManager, 2, propertySerializer, MoreExecutors.sameThreadExecutor(), CacheEvictionPolicy.LRU );

        String[] keys = new String[400];
        for ( int i = 0; i < keys.length; i++ ) {
            TestEntity1 entity1 = new TestEntity1();
            entity1.afterPropertiesSet();
            keys[i] = entity1.getUniqueIdentifier();

            byte[] bytes = objectBuffer.writeObjectData( CacheStoreEntryWrapper.writeValueOf( bo, entity1 ) );
            ByteArrayPointer p = new ByteArrayPointer( memoryManager, bytes, entity1, Integer.MAX_VALUE );
            segment.put( keys[i], p );
        }
        Thread.sleep( 1 );
        for ( int i = 0; i < keys.length / 4; i++ )
            assertThat( segment.getAsPointer( keys[i] ), is( notNullValue() ) );

        Thread.sleep( 2 );
        segment.evictPercentage( 75 );

        for ( int i = 0; i < keys.length / 4; i++ )
            assertThat( segment.getAsPointer( keys[i] ), is( notNullValue() ) );

        for ( int i = keys.length / 4; i < keys.length; i++ )
            assertThat( segment.getAsPointer( keys[i] ), is( nullValue() ) );
    }

    @Test
    public void testFifoEviction1()
                                   throws InterruptedException {
        segment = new OffHeapLinearProbingSegment( memoryManager, 2, propertySerializer, MoreExecutors.sameThreadExecutor(), CacheEvictionPolicy.FIFO );

        String[] keys = new String[100];
        for ( int i = 0; i < keys.length / 2; i++ ) {
            TestEntity1 entity1 = new TestEntity1();
            entity1.afterPropertiesSet();
            keys[i] = entity1.getUniqueIdentifier();

            byte[] bytes = objectBuffer.writeObjectData( CacheStoreEntryWrapper.writeValueOf( bo, entity1 ) );
            ByteArrayPointer p = new ByteArrayPointer( memoryManager, bytes, entity1, Integer.MAX_VALUE );
            segment.put( keys[i], p );
        }
        Thread.sleep( 10 );

        for ( int i = keys.length / 2; i < keys.length; i++ ) {
            TestEntity1 entity1 = new TestEntity1();
            entity1.afterPropertiesSet();
            keys[i] = entity1.getUniqueIdentifier();

            byte[] bytes = objectBuffer.writeObjectData( CacheStoreEntryWrapper.writeValueOf( bo, entity1 ) );
            ByteArrayPointer p = new ByteArrayPointer( memoryManager, bytes, entity1, Integer.MAX_VALUE );
            segment.put( keys[i], p );
        }

        segment.evictPercentage( 50 );
        for ( int i = 0; i < keys.length / 2; i++ )
            assertThat( segment.getAsPointer( keys[i] ), is( notNullValue() ) );
        for ( int i = keys.length / 2; i < keys.length; i++ )
            assertThat( segment.getAsPointer( keys[i] ), is( nullValue() ) );
    }

    @SuppressWarnings("unchecked")
    @Test
    public void randomizeTest() {
        ListeningExecutorService threadExecutor = MoreExecutors.sameThreadExecutor();
        final AtomicInteger evicted = new AtomicInteger();
        final Set<TestEntity1> expiredEntities = Sets.newHashSet();
        segment = new OffHeapLinearProbingSegment( memoryManager, 2, propertySerializer, threadExecutor, CacheEvictionPolicy.RANDOM );
        segment.setExpirationListeners( new SpaceExpirationListener<String, TestEntity1>() {
            @Override
            public void handleNotification(final TestEntity1 entity,
                                           final String id,
                                           final Class<TestEntity1> persistentClass,
                                           final int originalTimeToLive) {
                evicted.incrementAndGet();
                assertThat( entity.getUniqueIdentifier(), is( id ) );
                expiredEntities.add( entity );
            }
        } );

        System.out.println( "putting------" );
        Random r = new Random();
        TestEntity1[] entities = new TestEntity1[1793];
        for ( int i = 0; i < entities.length; i++ ) {
            TestEntity1 entity1 = new TestEntity1();
            entity1.afterPropertiesSet();
            entities[i] = entity1;

            byte[] bytes = objectBuffer.writeObjectData( CacheStoreEntryWrapper.writeValueOf( bo, entity1 ) );
            ByteArrayPointer p = new ByteArrayPointer( memoryManager, bytes, entity1, Math.max( 1, r.nextInt( 50 ) ) );
            segment.put( entities[i].getUniqueIdentifier(), p );
        }
        Assert.assertEquals( entities.length - evicted.get(), segment.size() );

        Log.info( String.format( "evicted = %s, segment_size = %s, elements=%s", evicted.get(), segment.size(), entities.length ) );
        System.out.println( "getting------" );
        AtomicInteger found = new AtomicInteger();
        final Set<TestEntity1> foundEntities = Sets.newHashSet();
        for ( int i = 0; i < entities.length; i++ ) {
            ByteArrayPointer p = segment.getAsPointer( entities[i].getUniqueIdentifier() );
            if ( p != null ) {
                found.incrementAndGet();
                Assert.assertFalse( expiredEntities.contains( entities[i] ) );
                foundEntities.add( (TestEntity1) propertySerializer.readObjectData( p.getSerializedDataBuffer(), TestEntity1.class ) );
            }
            else
                Assert.assertTrue( expiredEntities.contains( entities[i] ) );

        }
        Log.info( String.format(
                "evicted = %s, found = %s, segment_size = %s, elements=%s",
                evicted.get(),
                found.get(),
                segment.size(),
                entities.length ) );

        HashSet<TestEntity1> hashSet = new HashSet<TestEntity1>( foundEntities );
        hashSet.removeAll( segment.toImmutableSet() );
        System.out.println( "found:MINUS:segment = " + hashSet );
        System.out.println( "expired=" + expiredEntities );
        System.out.println( "segment=" + segment.toImmutableSet() );

        Assert.assertEquals( entities.length - evicted.get(), segment.size() );
    }
}
