package com.costonomy.mp.storage.invoice;

import com.costonomy.mp.storage.provider.StorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.util.Optional;

/**
 * Production storage for bills (D-113): a private bucket, server-side encryption on every put, no ACL,
 * and pages reached only through presigned GET links that expire. Credentials come from the default
 * AWS provider chain and are never properties. Selected by
 * {@code costonomy.mp.invoices.storage.provider=S3}.
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.invoices.storage.provider", havingValue = "S3")
@Slf4j
public class S3InvoiceStorage implements InvoiceStorage, AutoCloseable {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    /** Folder in a bucket shared with other apps ("mp/"), or empty for a bucket of MP's own. Keys stored in the
     * database never include it, so it can be set or changed without touching them. */
    private final String prefix;

    @Autowired
    public S3InvoiceStorage(
            @Value("${costonomy.mp.invoices.storage.s3.bucket:}") String bucket,
            @Value("${costonomy.mp.invoices.storage.s3.region:ap-south-1}") String region,
            @Value("${costonomy.mp.invoices.storage.s3.prefix:}") String prefix) {
        this(S3Client.builder().region(Region.of(region)).build(),
                S3Presigner.builder().region(Region.of(region)).build(), requireBucket(bucket), prefix);
    }

    /** For tests, with a client and presigner of their own. */
    public S3InvoiceStorage(S3Client client, S3Presigner presigner, String bucket) {
        this(client, presigner, bucket, "");
    }

    public S3InvoiceStorage(S3Client client, S3Presigner presigner, String bucket, String prefix) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.prefix = com.costonomy.mp.storage.provider.S3FileStorage.folder(prefix);
    }

    private static String requireBucket(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(
                    "costonomy.mp.invoices.storage.s3.bucket is required when the invoice storage provider is S3.");
        }
        return bucket;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(prefix + key).contentType(contentType)
                            .serverSideEncryption(ServerSideEncryption.AES256)
                            .cacheControl("private, no-store")
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (S3Exception e) {
            // The provider's message can name the bucket and the account.
            log.error("S3 rejected an invoice put for key {}", key, e);
            throw new StorageException("Could not store the file", e);
        }
    }

    @Override
    public Optional<String> openOrPresign(String key, Duration ttl) {
        var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(prefix + key).build())
                .build());
        return Optional.of(presigned.url().toString());
    }

    @Override
    public Optional<byte[]> read(String key) {
        try {
            return Optional.of(client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(prefix + key).build()).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            log.error("S3 rejected an invoice read for key {}", key, e);
            throw new StorageException("Could not read the file", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(prefix + key).build());
        } catch (S3Exception e) {
            log.error("S3 rejected an invoice delete for key {}", key, e);
            throw new StorageException("Could not remove the file", e);
        }
    }

    @Override
    public String name() {
        return "S3";
    }

    /** Spring calls this on shutdown or undeploy, so the SDK's connection pools and threads go with the app. */
    @Override
    public void close() {
        presigner.close();
        client.close();
    }
}
