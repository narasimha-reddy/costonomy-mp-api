package com.costonomy.mp.storage.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;

/**
 * Production storage. Doc 02 §2.
 *
 * <p><b>Not wired until a bucket exists.</b> It is selected by
 * {@code costonomy.mp.storage.provider=S3} and nothing else; with the property
 * absent the local adapter runs and this class is not even instantiated. So the
 * whole upload path — picker, multipart, validation, key, column, rendering —
 * can be built and used now, and turning it on later is configuration rather
 * than code.
 *
 * <p>Credentials come from the default provider chain (instance role, then
 * environment, then profile) and are deliberately not properties. A bucket
 * secret in {@code application.properties} is a bucket secret in the repository.
 *
 * <p>No ACL is set: object ownership is the bucket's business, and a bucket
 * serving public images should do it through a CDN origin policy rather than
 * per-object ACLs — which many accounts now refuse outright.
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.storage.provider", havingValue = "S3")
@Slf4j
public class S3FileStorage implements FileStorage {

    private final S3Client client;
    private final String bucket;
    private final String baseUrl;

    public S3FileStorage(
            @Value("${costonomy.mp.storage.s3.bucket}") String bucket,
            @Value("${costonomy.mp.storage.s3.region:ap-south-1}") String region,
            @Value("${costonomy.mp.storage.s3.endpoint:}") String endpoint,
            @Value("${costonomy.mp.storage.base-url:}") String baseUrl) {

        this.bucket = bucket;
        // The public base is usually a CDN in front of the bucket, not the bucket.
        // Falling back to the virtual-hosted URL keeps a bare setup working.
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()
                ? "https://%s.s3.%s.amazonaws.com".formatted(bucket, region)
                : baseUrl).replaceAll("/+$", "");

        var builder = S3Client.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            // For MinIO or LocalStack, which are the same contract at a different host.
            builder = builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        this.client = builder.build();
        log.info("S3 storage: bucket {} in {}, served from {}", bucket, region, this.baseUrl);
    }

    @Override
    public String put(String key, byte[] content, String contentType) {
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            // Immutable by construction: the key carries a UUID, so a
                            // long cache is correct and a changed image is a new key.
                            .cacheControl("public, max-age=31536000, immutable")
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (S3Exception e) {
            // The provider's message can name the bucket and the account.
            log.error("S3 rejected a put for key {}", key, e);
            throw new StorageException("Could not store the file", e);
        }
        return baseUrl + "/" + key;
    }

    @Override
    public String name() {
        return "S3";
    }
}
