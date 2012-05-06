package com.elsecloud.api;

import java.nio.ByteBuffer;
import java.util.EventListener;

/**
 * jspace support concept of writing entities with some lease context(meaning that entity has strict time-to-live and
 * can expire). this interface provided callback for such automatic expiration handling. </p>
 * 
 * NOTE: framework does not guarantee that expiration will happen immediately after time-to-live timeout, but it is
 * guaranteed that entity will be removed during read/take by id/template, so it is impossible to retrieve such expired
 * entity.
 * 
 * @since 0.1
 * @see JSpace#write(Object, long, long, int)
 */
public interface SpaceExpirationListener extends EventListener {

    /**
     * callback triggered when entity has been automatically removed from jspace in case of lease expiration.
     * 
     * @param entity
     *            expired space entry
     * @param persistentClass
     *            actual persistent class(you may want to retrieve entity as byte's array, in this case it gives you
     *            information of actual type)
     * @param originalTimeToLive
     *            original time to live for the initial entry write(or last update)
     */
    void handleNotification(Object entity,
                            Class<?> persistentClass,
                            long originalTimeToLive);

    /**
     * @return true if you want to get entity as POJO, otherwise you will get as {@link ByteBuffer} back (also take care
     *         about proper casting to ByteBuffer in {@link #handleNotification(Object, Class, long)} method in this
     *         case).
     */
    boolean retrieveAsEntity();
}
