package com.costonomy.mp.storage.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.storage.provider.FileStorage;
import com.costonomy.mp.storage.provider.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Accepts an uploaded image and returns where it now lives.
 *
 * <p>Three things happen here and all three have to, which is why the endpoint
 * is not a thin pass-through to the bucket.
 *
 * <ol>
 *   <li><b>Authorisation against the owning tenant.</b> The same permission that
 *       lets someone edit this store's catalog, scoped to the store — so a
 *       supplier cannot write into another supplier's prefix by changing an id.
 *       Denial is 404 (doc 09 §3).</li>
 *   <li><b>Identification from the bytes.</b> {@link ImageBytes} decides what the
 *       file is; the client's content type and filename decide nothing.</li>
 *   <li><b>Key construction from server-held ids.</b> The caller never supplies a
 *       path, so no request can escape its own tenant's prefix.</li>
 * </ol>
 *
 * <p>The row is not written here. Uploading a picture and attaching it to a SKU
 * are separate acts: a supplier may pick an image and then abandon the form, and
 * an orphaned object costs a lifecycle rule where a half-updated SKU costs a
 * wrong listing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UploadService {

    private final FileStorage storage;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    public record StoredFile(String url, String key, String contentType, int bytes) {
    }

    public StoredFile supplierSkuImage(Long actorId, Long storeId, byte[] content,
                                       String declaredContentType) {

        accessControl.requireScoped(actorId, Permissions.CATALOG_EDIT,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        var kind = ImageBytes.identify(content);
        if (!kind.contentType().equals(ImageBytes.describe(declaredContentType))) {
            // Not an error: phones mislabel, and the bytes are the authority. Worth
            // a line, because a persistent mismatch is a client bug.
            log.debug("Upload declared {} but is {}", declaredContentType, kind.contentType());
        }

        Long organizationId = organizationOf(storeId);
        String key = ObjectKeys.supplierSkuImage(organizationId, storeId,
                kind.extension(), Instant.now());

        String url;
        try {
            url = storage.put(key, content, kind.contentType());
        } catch (StorageException e) {
            log.error("Storage rejected {}", key, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "Could not save that image. Please try again.");
        }

        auditService.recordChange(actorId, "SKU_IMAGE_UPLOADED", "SUPPLIER_STORE", storeId,
                null, Map.of("key", key, "bytes", content.length, "storage", storage.name()),
                "Supplier uploaded a SKU photo");

        return new StoredFile(url, key, kind.contentType(), content.length);
    }

    /**
     * The organisation a store belongs to — the tenant the object is filed under.
     *
     * <p>Read here rather than taken from the request for the obvious reason: a
     * client-supplied owner id is a directory-traversal parameter with a friendly
     * name.
     */
    private Long organizationOf(Long storeId) {
        var ids = jdbc.queryForList(
                "select supplier_organization_id from supplier_store where id = ?",
                Long.class, storeId);
        if (ids.isEmpty()) {
            throw new NotFoundException("SupplierStore", storeId);
        }
        return ids.get(0);
    }
}
