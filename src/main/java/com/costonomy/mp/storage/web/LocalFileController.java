package com.costonomy.mp.storage.web;

import com.costonomy.mp.storage.provider.LocalFileStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Serves back what {@link LocalFileStorage} wrote. Development only.
 *
 * <p>Exists so the local adapter is a real equivalent of S3 rather than a stub:
 * an upload produces a URL that actually resolves, so every screen that renders
 * an uploaded image can be checked long before a bucket exists. With
 * {@code costonomy.mp.storage.provider=S3} the local bean is absent and this
 * controller is not registered at all.
 *
 * <p>Unauthenticated, like the bucket it stands in for — and for the same reason
 * it is safe: keys carry a UUID, so the URL is the capability. It is registered
 * outside {@code /api/v1} and permitted in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/files")
@ConditionalOnBean(LocalFileStorage.class)
@RequiredArgsConstructor
public class LocalFileController {

    private final LocalFileStorage storage;

    @GetMapping("/**")
    public ResponseEntity<FileSystemResource> file(HttpServletRequest request) {
        String full = (String) request.getAttribute(
                HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String key = full == null ? "" : full.replaceFirst("^/files/", "");

        Path path = storage.resolve(key);
        if (path == null || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }

        MediaType type = switch (key.substring(key.lastIndexOf('.') + 1).toLowerCase()) {
            case "png" -> MediaType.IMAGE_PNG;
            case "webp" -> MediaType.parseMediaType("image/webp");
            default -> MediaType.IMAGE_JPEG;
        };

        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic())
                .body(new FileSystemResource(path));
    }
}
