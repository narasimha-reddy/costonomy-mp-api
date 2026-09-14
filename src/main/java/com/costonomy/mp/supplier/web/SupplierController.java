package com.costonomy.mp.supplier.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.supplier.service.SupplierService;
import com.costonomy.mp.supplier.web.dto.SupplierDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Supplier organisations, stores and verification. Doc 04 §6. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Suppliers")
public class SupplierController {

    private final SupplierService supplierService;

    @PostMapping("/suppliers")
    @Operation(
            summary = "Register a supplier",
            description = """
                    The caller becomes its Owner. The organisation starts at REGISTERED
                    and cannot receive orders until it has been verified *and* activated
                    — two separate decisions.
                    """)
    public ApiResponse<SupplierDtos.SupplierResponse> create(
            @Valid @RequestBody SupplierDtos.CreateSupplierRequest request) {
        return ApiResponse.ok(supplierService.create(ActorContext.requireUserId(), request));
    }

    @GetMapping("/suppliers/{id}")
    @Operation(summary = "Get a supplier and its stores")
    public ApiResponse<SupplierDtos.SupplierResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(supplierService.get(ActorContext.requireUserId(), id));
    }

    @PatchMapping("/suppliers/{id}")
    @Operation(
            summary = "Update a supplier",
            description = "The GSTIN cannot be changed once verified — it is the thing "
                    + "that was verified.")
    public ApiResponse<SupplierDtos.SupplierResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody SupplierDtos.UpdateSupplierRequest request) {
        return ApiResponse.ok(supplierService.update(ActorContext.requireUserId(), id, request));
    }

    @PostMapping("/suppliers/{id}/stores")
    @Operation(
            summary = "Add a store",
            description = "Store-level state is authoritative for availability, price, "
                    + "delivery and credit, including the response SLA.")
    public ApiResponse<SupplierDtos.StoreResponse> createStore(
            @PathVariable Long id,
            @Valid @RequestBody SupplierDtos.CreateStoreRequest request) {
        return ApiResponse.ok(supplierService.createStore(ActorContext.requireUserId(), id, request));
    }

    @GetMapping("/supplier-stores/{id}")
    @Operation(summary = "Get a store")
    public ApiResponse<SupplierDtos.StoreResponse> getStore(@PathVariable Long id) {
        return ApiResponse.ok(supplierService.getStore(ActorContext.requireUserId(), id));
    }

    @PatchMapping("/supplier-stores/{id}")
    @Operation(
            summary = "Update a store",
            description = "A supplier can set a store ACTIVE or OFFLINE. SUSPENDED is an "
                    + "operations decision and is not reachable here.")
    public ApiResponse<SupplierDtos.StoreResponse> updateStore(
            @PathVariable Long id,
            @Valid @RequestBody SupplierDtos.UpdateStoreRequest request) {
        return ApiResponse.ok(supplierService.updateStore(ActorContext.requireUserId(), id, request));
    }

    @PostMapping("/suppliers/{id}/verification")
    @Operation(
            summary = "Submit for verification",
            description = """
                    Moves the supplier to VERIFICATION_PENDING. Submitting does not verify,
                    and being verified does not activate — both are separate decisions made
                    by an operator.
                    """)
    public ApiResponse<SupplierDtos.VerificationResponse> submitVerification(
            @PathVariable Long id,
            @Valid @RequestBody SupplierDtos.SubmitVerificationRequest request) {
        return ApiResponse.ok(
                supplierService.submitVerification(ActorContext.requireUserId(), id, request));
    }

    @GetMapping("/suppliers/{id}/verification")
    @Operation(
            summary = "Verification history",
            description = "Append-only: a rejection and a later resubmission both appear.")
    public ApiResponse<List<SupplierDtos.VerificationResponse>> verificationHistory(
            @PathVariable Long id) {
        return ApiResponse.ok(
                supplierService.verificationHistory(ActorContext.requireUserId(), id));
    }

    @PostMapping("/suppliers/{id}/users")
    @Operation(
            summary = "Add a member",
            description = """
                    Invites by phone number. Pass `storeId` to scope the role to one store,
                    or omit it to grant across the whole organisation. Only supplier-side
                    roles (`SUP_*`) can be granted here.
                    """)
    public ApiResponse<SupplierDtos.SupplierUserResponse> addUser(
            @PathVariable Long id,
            @Valid @RequestBody SupplierDtos.AddSupplierUserRequest request) {
        return ApiResponse.ok(supplierService.addUser(ActorContext.requireUserId(), id, request));
    }
}
