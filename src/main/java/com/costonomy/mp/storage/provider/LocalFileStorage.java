package com.costonomy.mp.storage.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Development and test storage: the same keys, on local disk. Doc 10 §4.
 *
 * <p>Deliberately equivalent to the S3 adapter rather than simpler than it. It
 * takes the identical key, writes it at the identical relative path, and returns
 * an absolute URL — so the client code that uploads and the column that stores
 * the result are exercised for real long before a bucket exists. The only thing
 * a bucket changes is which adapter is wired.
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.storage.provider",
                       havingValue = "LOCAL", matchIfMissing = true)
@Slf4j
public class LocalFileStorage implements FileStorage {

    private final Path root;
    private final String baseUrl;

    public LocalFileStorage(
            @Value("${costonomy.mp.storage.local.directory:./var/uploads}") String directory,
            @Value("${costonomy.mp.storage.base-url:http://localhost:7070/costonomy-mp-api/files}")
            String baseUrl) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        log.info("Local file storage at {} served from {}", root, this.baseUrl);
    }

    @Override
    public String put(String key, byte[] content, String contentType) {
        // The key is built by ObjectKeys and never contains a client string, but
        // resolving and re-checking costs nothing and makes that a property of
        // this class rather than an assumption about its caller.
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new StorageException("Refusing to write outside the storage root", null);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new StorageException("Could not store the file", e);
        }
        return baseUrl + "/" + key;
    }

    @Override
    public String name() {
        return "LOCAL";
    }

    /** Where {@code key} was written, for the controller that serves it back. */
    public Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        return target.startsWith(root) ? target : null;
    }
}
