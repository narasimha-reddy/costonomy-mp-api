package com.costonomy.mp.storage.invoice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** No AWS is contacted: the client is a mock and the presigner signs offline with made-up keys. */
class S3InvoiceStorageTest {

    private static S3Presigner presigner() {
        return S3Presigner.builder().region(Region.AP_SOUTH_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("AKIAFAKEFAKEFAKEFAKE", "fakefakefakefakefakefakefakefakefakefake")))
                .build();
    }

    @Test
    @DisplayName("a put is server-side encrypted (AES256), has no ACL, and is not cached publicly")
    void putIsEncryptedAndPrivate() {
        var client = mock(S3Client.class);
        new S3InvoiceStorage(client, presigner(), "bills-bucket")
                .put("invoices/1/2026-10/abc-p1.jpg", new byte[]{1, 2, 3}, "image/jpeg");

        var captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(captor.capture(), any(RequestBody.class));
        var request = captor.getValue();
        assertThat(request.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(request.acl()).isNull();
        assertThat(request.bucket()).isEqualTo("bills-bucket");
        assertThat(request.key()).isEqualTo("invoices/1/2026-10/abc-p1.jpg");
        assertThat(request.cacheControl()).doesNotContain("public");
    }

    @Test
    @DisplayName("a page link is a presigned GET that lasts five minutes")
    void presignedLink() {
        var url = new S3InvoiceStorage(mock(S3Client.class), presigner(), "bills-bucket")
                .openOrPresign("invoices/1/2026-10/abc-p1.jpg", Duration.ofMinutes(5)).orElseThrow();
        assertThat(url).startsWith("https://bills-bucket.s3.ap-south-1.amazonaws.com/invoices/1/2026-10/abc-p1.jpg?");
        assertThat(url).contains("X-Amz-Expires=300").contains("X-Amz-Signature=");
    }
}
