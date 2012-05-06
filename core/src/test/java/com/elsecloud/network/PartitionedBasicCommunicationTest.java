package com.elsecloud.network;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.LinkedList;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.elsecloud.api.ClientSpaceConfiguration;
import com.elsecloud.api.JSpace;
import com.elsecloud.api.SpaceConfiguration;
import com.elsecloud.api.SpaceTopology;
import com.elsecloud.core.SpaceUtility;
import com.elsecloud.model.TestEntity1;
import com.elsecloud.spaces.OffHeapJSpace;
import com.elsecloud.spaces.RemoteJSpace;
import com.elsecloud.spaces.SimplisticJSpace;

@SuppressWarnings("javadoc")
public class PartitionedBasicCommunicationTest {
    static ClientSpaceConfiguration clientConfiguration;
    static SpaceConfiguration configuration1, configuration2;

    static SimplisticJSpace simplisticJSpace1, simplisticJSpace2;
    static OffHeapJSpace offHeapJSpace1, offHeapJSpace2;
    static RemoteJSpace remoteJSpace;

    @BeforeClass
    public static void beforeClass()
                                    throws Exception {
        configuration1 = TestEntity1.configurationFor();
        configuration2 = TestEntity1.configurationFor();
        configuration1.setTopology( SpaceTopology.PARTITIONED );
        configuration2.setTopology( SpaceTopology.PARTITIONED );
        clientConfiguration = TestEntity1.clientConfigurationFor();

        offHeapJSpace1 = new OffHeapJSpace( configuration1 );
        offHeapJSpace2 = new OffHeapJSpace( configuration2 );
        offHeapJSpace1.afterPropertiesSet();
        offHeapJSpace2.afterPropertiesSet();

        simplisticJSpace1 = new SimplisticJSpace( offHeapJSpace1 );
        simplisticJSpace2 = new SimplisticJSpace( offHeapJSpace2 );
        simplisticJSpace1.afterPropertiesSet();
        simplisticJSpace2.afterPropertiesSet();

        remoteJSpace = new RemoteJSpace( clientConfiguration );
        remoteJSpace.afterPropertiesSet();

        configuration1.joinNetwork();
        configuration2.joinNetwork();
        clientConfiguration.joinNetwork();
    }

    @AfterClass
    public static void afterClass()
                                   throws Exception {
        simplisticJSpace1.destroy();
        simplisticJSpace2.destroy();
        offHeapJSpace1.destroy();
        offHeapJSpace2.destroy();
        remoteJSpace.destroy();

        clientConfiguration.destroy();
        configuration1.destroy();
        configuration2.destroy();
    }

    @Test
    public void canWriteFetchNonTransactionally()
                                                 throws InterruptedException {
        LinkedList<Throwable> errors = SpaceUtility.repeatConcurrently( Runtime.getRuntime().availableProcessors(), 1000, new Runnable() {

            @Override
            public void run() {
                final TestEntity1 entity = new TestEntity1();
                entity.afterPropertiesSet();

                remoteJSpace.write( entity, JSpace.LEASE_FOREVER, 0, JSpace.WRITE_ONLY );
                Object[] resp = remoteJSpace.fetch( entity, 0, 1, JSpace.MATCH_BY_ID );
                entity.assertMatch( (TestEntity1) resp[0] );
                resp = remoteJSpace.fetch( entity, 0, 1, JSpace.MATCH_BY_ID | JSpace.TAKE_ONLY );
                entity.assertMatch( (TestEntity1) resp[0] );
                entity.setRouting( null );
                resp = remoteJSpace.fetch( entity, 0, 1, JSpace.MATCH_BY_ID );
                assertThat( resp.length, is( 0 ) );
            }
        } );

        assertThat( errors.size(), is( 0 ) );
    }
}
