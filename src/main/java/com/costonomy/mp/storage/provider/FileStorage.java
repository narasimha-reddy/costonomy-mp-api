package com.costonomy.mp.storage.provider;

/**
 * Stores a file somewhere durable and says where it went.
 *
 * <p>A port, per doc 02 §2's external-provider rule: S3 in production, local disk
 * everywhere else, and no provider type reaches a domain or a controller. The key
 * is chosen by {@link com.costonomy.mp.storage.service.ObjectKeys} rather than by
 * the adapter, so both implementations lay files out identically and a bucket can
 * be swapped in without a migration of paths.
 *
 * <p><b>The upload goes through us, not around us.</b> A presigned URL would be
 * cheaper and is the obvious alternative, but it moves validation to the client:
 * what a browser calls a JPEG and what a file actually is are different claims,
 * and only the server can check the second one. It would also give local
 * development no path at all, since there is no bucket to presign against.
 */
public interface FileStorage {

    /**
     * @param key     the full object key, already namespaced by tenant
     * @param content the bytes, already validated by the caller
     * @param contentType a checked image media type, never the client's word for it
     * @return the URL a client should render. Absolute and stable.
     */
    String put(String key, byte[] content, String contentType);

    /** {@code S3} or {@code LOCAL}. For logs and for saying which is configured. */
    String name();
}
