package com.costonomy.mp.identity.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.identity.service.DeviceService;
import com.costonomy.mp.identity.web.dto.AuthDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Push device registration. Doc 04 §3. */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Devices")
public class DeviceController {

    private final DeviceService deviceService;

    @PostMapping
    @Operation(
            summary = "Register or update this device",
            description = """
                    Idempotent by push token: re-registering the same token updates the
                    existing record rather than creating another. A token already held
                    by a different user is reassigned, so notifications cannot follow
                    the previous account.
                    """)
    public ApiResponse<AuthDtos.DeviceResponse> register(
            @Valid @RequestBody AuthDtos.DeviceRequest request) {
        var device = deviceService.register(ActorContext.requireUserId(), request);
        return ApiResponse.ok(DeviceService.toResponse(device));
    }

    @GetMapping
    @Operation(summary = "This user's active devices")
    public ApiResponse<List<AuthDtos.DeviceResponse>> list() {
        return ApiResponse.ok(deviceService.activeDevices(ActorContext.requireUserId())
                .stream()
                .map(DeviceService::toResponse)
                .toList());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Unregister a device and clear its push token")
    public ApiResponse<Void> unregister(@PathVariable Long id) {
        deviceService.unregister(ActorContext.requireUserId(), id);
        return ApiResponse.ok(null);
    }
}
