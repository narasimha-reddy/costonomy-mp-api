package com.costonomy.mp.storage.invoice;

import java.time.Duration;
import java.util.Optional;

/**
 * Private storage for a shop's bill (D-113). Not {@code FileStorage}: that one is built for public
 * images, hands back a permanent URL and is cached for a year. A bill is private, so nothing here
 * returns a permanent address: a page is reached only through a short-lived link.
 */
public interface InvoiceStorage {

    /** Stores the bytes under {@code key}. Keys come from {@link InvoiceKeys}, never from a client. */
    void put(String key, byte[] content, String contentType);

    /**
     * A link that works for {@code ttl} and then stops, when the store can make one itself (S3: a
     * presigned GET). Empty when it cannot (local disk): the caller then serves the bytes through its
     * own signed route and reads them with {@link #read}.
     */
    Optional<String> openOrPresign(String key, Duration ttl);

    /** The stored bytes, or empty when there are none under that key. */
    Optional<byte[]> read(String key);

    /** Removes the object; a missing one is not an error. */
    void delete(String key);

    String name();
}
