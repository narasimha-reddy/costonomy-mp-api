package com.costonomy.mp.storage.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.storage.service.UploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Uploads")
public class UploadController {

    private final UploadService uploads;

    @PostMapping(value = "/supplier-stores/{storeId}/sku-images",
                 consumes = "multipart/form-data")
    @Operation(
            summary = "Upload a photo of a pack",
            description = """
                    Returns the URL to put on a SKU's `imageUrl`. It does **not**
                    attach it to anything — picking an image and saving a SKU are
                    separate acts, and a supplier who abandons the form should leave
                    an orphaned object rather than a half-changed listing.

                    JPEG, PNG or WebP, up to 5 MB. The format is decided by reading
                    the file, not by what the client calls it.
                    """)
    public ApiResponse<UploadService.StoredFile> skuImage(
            @PathVariable Long storeId,
            @RequestParam("file") MultipartFile file) {

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "Could not read that file.");
        }
        return ApiResponse.ok(uploads.supplierSkuImage(
                ActorContext.requireUserId(), storeId, content, file.getContentType()));
    }
}
