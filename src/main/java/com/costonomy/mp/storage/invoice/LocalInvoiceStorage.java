package com.costonomy.mp.storage.invoice;

import com.costonomy.mp.storage.provider.StorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Development and test storage for bills, on local disk. Pages are served only through the signed,
 * expiring route ({@code InvoiceFileController}); the directory is not exposed anywhere else.
 * Refused under a production profile by {@code ProductionProviderGuard}.
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.invoices.storage.provider", havingValue = "LOCAL", matchIfMissing = true)
@Slf4j
public class LocalInvoiceStorage implements InvoiceStorage {

    private final Path root;

    public LocalInvoiceStorage(
            @Value("${costonomy.mp.invoices.storage.local.directory:./var/invoices}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        log.info("Local invoice storage at {}", root);
    }

    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new StorageException("Refusing to use a path outside the storage root", null);
        }
        return target;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new StorageException("Could not store the file", e);
        }
    }

    @Override
    public Optional<String> openOrPresign(String key, Duration ttl) {
        return Optional.empty();
    }

    @Override
    public Optional<byte[]> read(String key) {
        try {
            return Optional.of(Files.readAllBytes(resolve(key)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new StorageException("Could not read the file", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new StorageException("Could not remove the file", e);
        }
    }

    @Override
    public String name() {
        return "LOCAL";
    }
}
