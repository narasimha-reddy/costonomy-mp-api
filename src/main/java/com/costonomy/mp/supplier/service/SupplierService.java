package com.costonomy.mp.supplier.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.Roles;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.access.service.RoleGrantService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.identity.service.UserDirectoryService;
import com.costonomy.mp.supplier.domain.*;
import com.costonomy.mp.supplier.repository.*;
import com.costonomy.mp.supplier.web.dto.SupplierDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Supplier organisations, stores, verification and members.
 *
 * <p>The lifecycle rules in {@link SupplierLifecycleStatus} are enforced here
 * rather than trusted from the client (doc 03 §1 step 3, guardrail 4). In
 * particular: submitting verification does not verify a supplier, passing
 * verification does not activate one, and neither can be reached by sending a
 * status field.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierService {

    private final SupplierOrganizationRepository organizations;
    private final SupplierStoreRepository stores;
    private final SupplierVerificationRepository verifications;
    private final SupplierUserRepository memberships;
    private final UserDirectoryService userDirectory;
    private final AccessControlService accessControl;
    private final RoleGrantService roleGrants;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final ObjectMapper json;

    /** Register a supplier. The caller becomes its owner, for the same reason a restaurant creator does. */
    @Transactional
    public SupplierDtos.SupplierResponse create(Long actorId, SupplierDtos.CreateSupplierRequest request) {
        var organization = new SupplierOrganization();
        organization.setLegalName(request.legalName());
        organization.setDisplayName(request.displayName());
        organization.setGstin(blankToNull(request.gstin()));
        organization.setContactName(request.contactName());
        organization.setContactPhone(request.contactPhone());
        organization.setContactEmail(request.contactEmail());
        organization.setLifecycleStatus(SupplierLifecycleStatus.REGISTERED);
        organization.setCreatedBy(actorId);

        try {
            organizations.saveAndFlush(organization);
        } catch (DataIntegrityViolationException ex) {
            // uk_supplier_org_gstin. One GSTIN is one supplier — otherwise the
            // same business could register twice and split its performance history.
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A supplier with that GSTIN is already registered.");
        }

        var membership = new SupplierUser();
        membership.setSupplierOrganizationId(organization.getId());
        membership.setUserId(actorId);
        membership.setStatus("ACTIVE");
        membership.setJoinedAt(Instant.now());
        memberships.save(membership);

        roleGrants.grant(actorId, Roles.SUP_OWNER, ScopeType.SUPPLIER, organization.getId(), actorId);

        auditService.record(actorId, Roles.SUP_OWNER, "SUPPLIER_REGISTERED", "SUPPLIER",
                organization.getId(), null, SupplierLifecycleStatus.REGISTERED.name(), null, "API");

        if (request.firstStore() != null) {
            createStore(actorId, organization.getId(), request.firstStore());
        }

        return get(actorId, organization.getId());
    }

    @Transactional(readOnly = true)
    public SupplierDtos.SupplierResponse get(Long actorId, Long supplierId) {
        accessControl.requireScoped(actorId, Permissions.SUPPLIER_VIEW,
                ScopeType.SUPPLIER, supplierId, "Supplier");

        var organization = organizations.findById(supplierId)
                .orElseThrow(() -> new NotFoundException("Supplier", supplierId));

        return toResponse(organization, stores.findBySupplierOrganizationId(supplierId));
    }

    @Transactional
    public SupplierDtos.SupplierResponse update(
            Long actorId, Long supplierId, SupplierDtos.UpdateSupplierRequest request) {

        accessControl.requireScoped(actorId, Permissions.SUPPLIER_EDIT,
                ScopeType.SUPPLIER, supplierId, "Supplier");

        var organization = organizations.findById(supplierId)
                .orElseThrow(() -> new NotFoundException("Supplier", supplierId));

        if (request.legalName() != null) organization.setLegalName(request.legalName());
        if (request.displayName() != null) organization.setDisplayName(request.displayName());
        if (request.contactName() != null) organization.setContactName(request.contactName());
        if (request.contactPhone() != null) organization.setContactPhone(request.contactPhone());
        if (request.contactEmail() != null) organization.setContactEmail(request.contactEmail());

        if (request.gstin() != null) {
            // A verified supplier's GSTIN is the thing that was verified. Letting
            // it be edited afterwards would leave a VERIFIED badge attached to a
            // number nobody checked.
            if (organization.getVerificationStatus() == VerificationStatus.VERIFIED) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "The GSTIN can't be changed after verification. Contact support.");
            }
            organization.setGstin(blankToNull(request.gstin()));
        }

        organizations.save(organization);
        auditService.record(actorId, null, "SUPPLIER_UPDATED", "SUPPLIER",
                supplierId, null, null, null, "API");

        return get(actorId, supplierId);
    }

    // ── Stores ───────────────────────────────────────────────────────────

    @Transactional
    public SupplierDtos.StoreResponse createStore(
            Long actorId, Long supplierId, SupplierDtos.CreateStoreRequest request) {

        accessControl.requireScoped(actorId, Permissions.SUPPLIER_EDIT,
                ScopeType.SUPPLIER, supplierId, "Supplier");

        if (!organizations.existsById(supplierId)) {
            throw new NotFoundException("Supplier", supplierId);
        }

        var store = new SupplierStore();
        store.setSupplierOrganizationId(supplierId);
        store.setName(request.name());
        store.setAddressLine1(request.addressLine1());
        store.setAddressLine2(request.addressLine2());
        store.setCity(request.city());
        store.setState(request.state());
        store.setPincode(request.pincode());
        store.setLatitude(request.latitude());
        store.setLongitude(request.longitude());
        store.setContactName(request.contactName());
        store.setContactPhone(request.contactPhone());
        if (request.responseSlaSeconds() != null) {
            store.setResponseSlaSeconds(request.responseSlaSeconds());
        }
        if (request.preparationMinutes() != null) {
            store.setPreparationMinutes(request.preparationMinutes());
        }
        stores.save(store);

        auditService.record(actorId, null, "SUPPLIER_STORE_CREATED", "SUPPLIER_STORE",
                store.getId(), null, "ACTIVE", null, "API");

        return toStoreResponse(store);
    }

    @Transactional(readOnly = true)
    public SupplierDtos.StoreResponse getStore(Long actorId, Long storeId) {
        accessControl.requireScoped(actorId, Permissions.STORE_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        return stores.findById(storeId)
                .map(SupplierService::toStoreResponse)
                .orElseThrow(() -> new NotFoundException("SupplierStore", storeId));
    }

    @Transactional
    public SupplierDtos.StoreResponse updateStore(
            Long actorId, Long storeId, SupplierDtos.UpdateStoreRequest request) {

        accessControl.requireScoped(actorId, Permissions.STORE_EDIT,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        var store = stores.findById(storeId)
                .orElseThrow(() -> new NotFoundException("SupplierStore", storeId));

        if (request.name() != null) store.setName(request.name());
        if (request.addressLine1() != null) store.setAddressLine1(request.addressLine1());
        if (request.addressLine2() != null) store.setAddressLine2(request.addressLine2());
        if (request.city() != null) store.setCity(request.city());
        if (request.state() != null) store.setState(request.state());
        if (request.pincode() != null) store.setPincode(request.pincode());
        if (request.latitude() != null) store.setLatitude(request.latitude());
        if (request.longitude() != null) store.setLongitude(request.longitude());
        if (request.contactName() != null) store.setContactName(request.contactName());
        if (request.contactPhone() != null) store.setContactPhone(request.contactPhone());
        if (request.responseSlaSeconds() != null) {
            store.setResponseSlaSeconds(request.responseSlaSeconds());
        }
        if (request.preparationMinutes() != null) {
            store.setPreparationMinutes(request.preparationMinutes());
        }
        // ACTIVE ⇄ OFFLINE only. SUSPENDED is an operations decision and is not
        // reachable by a supplier editing their own store — the DTO's @Pattern
        // rejects it, and this is the second line of that defence.
        if (request.status() != null) {
            if (!"ACTIVE".equals(request.status()) && !"OFFLINE".equals(request.status())) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "A store can only be set ACTIVE or OFFLINE.");
            }
            store.setStatus(request.status());
        }

        stores.save(store);
        auditService.record(actorId, null, "SUPPLIER_STORE_UPDATED", "SUPPLIER_STORE",
                storeId, null, store.getStatus(), null, "API");

        return toStoreResponse(store);
    }

    // ── Verification ─────────────────────────────────────────────────────

    /**
     * Submit for verification. Doc 09 §8.
     *
     * <p>Moves the organisation to {@code VERIFICATION_PENDING} and nothing
     * further. The supplier cannot verify or activate itself; that is a separate
     * decision by an operator holding {@code SUPPLIER_VERIFY}.
     */
    @Transactional
    public SupplierDtos.VerificationResponse submitVerification(
            Long actorId, Long supplierId, SupplierDtos.SubmitVerificationRequest request) {

        accessControl.requireScoped(actorId, Permissions.SUPPLIER_EDIT,
                ScopeType.SUPPLIER, supplierId, "Supplier");

        var organization = organizations.findById(supplierId)
                .orElseThrow(() -> new NotFoundException("Supplier", supplierId));

        if (organization.getVerificationStatus() == VerificationStatus.VERIFIED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This supplier is already verified.");
        }
        verifications
                .findFirstBySupplierOrganizationIdAndStatusOrderByCreatedAtDesc(
                        supplierId, VerificationStatus.PENDING)
                .ifPresent(pending -> {
                    throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                            "A verification is already under review.");
                });

        var verification = new SupplierVerification();
        verification.setSupplierOrganizationId(supplierId);
        verification.setVerificationType(request.verificationType());
        verification.setSubmittedBy(actorId);
        verification.setSubmittedDataJson(writeJson(Map.of(
                "gstin", request.gstin(),
                "legalName", request.legalName())));
        verification.setStatus(VerificationStatus.PENDING);
        // MANUAL until a GST verification API is integrated. Doc 09 §8 requires
        // the source to be recorded either way, so the field is not optional.
        verification.setVerificationSource("MANUAL");
        verification.setEvidenceUrl(request.evidenceUrl());
        verifications.save(verification);

        organization.setGstin(request.gstin());
        organization.setVerificationStatus(VerificationStatus.PENDING);
        transitionLifecycle(organization, SupplierLifecycleStatus.VERIFICATION_PENDING, actorId);
        organizations.save(organization);

        auditService.record(actorId, null, "SUPPLIER_VERIFICATION_SUBMITTED", "SUPPLIER",
                supplierId, null, VerificationStatus.PENDING.name(), null, "API");

        return toVerificationResponse(verification);
    }

    @Transactional(readOnly = true)
    public List<SupplierDtos.VerificationResponse> verificationHistory(Long actorId, Long supplierId) {
        accessControl.requireScoped(actorId, Permissions.SUPPLIER_VIEW,
                ScopeType.SUPPLIER, supplierId, "Supplier");

        return verifications.findBySupplierOrganizationIdOrderByCreatedAtDesc(supplierId).stream()
                .map(SupplierService::toVerificationResponse)
                .toList();
    }

    /**
     * Operations decision on a submitted verification. Requires {@code SUPPLIER_VERIFY}.
     *
     * <p>Approval moves the organisation to {@code VERIFIED}, <b>not</b>
     * {@code ACTIVE}. Doc 03 §2: verification confirms identity; activation is a
     * separate decision to let the supplier trade.
     */
    @Transactional
    public SupplierDtos.VerificationResponse reviewVerification(
            Long actorId, Long verificationId, boolean approved, String reason) {

        accessControl.require(actorId, Permissions.SUPPLIER_VERIFY, ScopeType.PLATFORM, null);

        var verification = verifications.findById(verificationId)
                .orElseThrow(() -> new NotFoundException("SupplierVerification", verificationId));

        if (verification.getStatus() != VerificationStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This verification has already been decided.");
        }

        var organization = organizations.findById(verification.getSupplierOrganizationId())
                .orElseThrow(() -> new NotFoundException("Supplier",
                        verification.getSupplierOrganizationId()));

        Instant now = Instant.now();
        verification.setReviewedBy(actorId);
        verification.setReviewedAt(now);

        if (approved) {
            verification.setStatus(VerificationStatus.VERIFIED);
            verification.setVerifiedAt(now);
            organization.setVerificationStatus(VerificationStatus.VERIFIED);
            transitionLifecycle(organization, SupplierLifecycleStatus.VERIFIED, actorId);
        } else {
            verification.setStatus(VerificationStatus.REJECTED);
            verification.setRejectionReason(reason);
            organization.setVerificationStatus(VerificationStatus.REJECTED);
            // Back to REGISTERED so the supplier can correct and resubmit. Doc 03
            // §2: a failed verification must never silently activate a supplier.
            transitionLifecycle(organization, SupplierLifecycleStatus.REGISTERED, actorId);
        }

        verifications.save(verification);
        organizations.save(organization);

        auditService.record(actorId, null,
                approved ? "SUPPLIER_VERIFICATION_APPROVED" : "SUPPLIER_VERIFICATION_REJECTED",
                "SUPPLIER", organization.getId(), null,
                organization.getVerificationStatus().name(), reason, "ADMIN_API");

        return toVerificationResponse(verification);
    }

    /** Activate a verified supplier so it can receive orders. Requires {@code SUPPLIER_VERIFY}. */
    @Transactional
    public SupplierDtos.SupplierResponse activate(Long actorId, Long supplierId) {
        accessControl.require(actorId, Permissions.SUPPLIER_VERIFY, ScopeType.PLATFORM, null);

        var organization = organizations.findById(supplierId)
                .orElseThrow(() -> new NotFoundException("Supplier", supplierId));

        transitionLifecycle(organization, SupplierLifecycleStatus.ACTIVE, actorId);
        organizations.save(organization);

        outbox.publish("SupplierActivated", "SUPPLIER", supplierId,
                Map.of("supplierId", supplierId), actorId);

        return toResponse(organization, stores.findBySupplierOrganizationId(supplierId));
    }

    // ── Members ──────────────────────────────────────────────────────────

    @Transactional
    public SupplierDtos.SupplierUserResponse addUser(
            Long actorId, Long supplierId, SupplierDtos.AddSupplierUserRequest request) {

        accessControl.requireScoped(actorId, Permissions.SUPPLIER_USER_MANAGE,
                ScopeType.SUPPLIER, supplierId, "Supplier");

        // Supplier-side roles only. Without this an admin could grant themselves
        // an internal operations role and escalate out of their own tenant.
        if (!request.roleCode().startsWith("SUP_")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That role can't be granted on a supplier.");
        }

        ScopeType scopeType = request.storeId() == null ? ScopeType.SUPPLIER : ScopeType.SUPPLIER_STORE;
        Long scopeId = request.storeId() == null ? supplierId : request.storeId();

        if (scopeType == ScopeType.SUPPLIER_STORE) {
            var store = stores.findById(scopeId)
                    .orElseThrow(() -> new NotFoundException("SupplierStore", scopeId));
            // Otherwise an admin of supplier A could grant a role on supplier B's
            // store simply by passing its id.
            if (!store.getSupplierOrganizationId().equals(supplierId)) {
                throw new NotFoundException("SupplierStore", scopeId);
            }
        }

        var user = userDirectory.findOrInviteByPhone(request.phone(), request.country(), request.name());
        ensureMembership(supplierId, user.getId(), actorId);
        roleGrants.grant(user.getId(), request.roleCode(), scopeType, scopeId, actorId);

        return new SupplierDtos.SupplierUserResponse(
                user.getId(), user.getPhone(), user.getName(), "ACTIVE",
                List.copyOf(accessControl.permissionsAt(user.getId(), scopeType, scopeId)));
    }

    // ── internals ────────────────────────────────────────────────────────

    /**
     * Apply a lifecycle transition, or refuse it.
     *
     * <p>Doc 03 §1: validate the transition before persisting. An illegal move —
     * REGISTERED straight to ACTIVE, say — is a typed business error, never a
     * silent write.
     */
    private void transitionLifecycle(
            SupplierOrganization organization, SupplierLifecycleStatus target, Long actorId) {

        var current = organization.getLifecycleStatus();
        if (current == target) {
            return;
        }
        if (!current.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "A supplier can't move from %s to %s.".formatted(current, target));
        }

        organization.setLifecycleStatus(target);
        auditService.record(actorId, null, "SUPPLIER_LIFECYCLE_CHANGED", "SUPPLIER",
                organization.getId(), current.name(), target.name(), null, "API");
    }

    private void ensureMembership(Long supplierId, Long userId, Long invitedBy) {
        memberships.findBySupplierOrganizationIdAndUserId(supplierId, userId)
                .ifPresentOrElse(existing -> {
                    if (!"ACTIVE".equals(existing.getStatus())) {
                        existing.setStatus("ACTIVE");
                        existing.setJoinedAt(Instant.now());
                        existing.setRemovedAt(null);
                        memberships.save(existing);
                    }
                }, () -> {
                    var membership = new SupplierUser();
                    membership.setSupplierOrganizationId(supplierId);
                    membership.setUserId(userId);
                    membership.setStatus("ACTIVE");
                    membership.setInvitedBy(invitedBy);
                    membership.setInvitedAt(Instant.now());
                    membership.setJoinedAt(Instant.now());
                    memberships.save(membership);
                });
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /** Empty string is not a GSTIN; it must be null so the unique index ignores it. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static SupplierDtos.SupplierResponse toResponse(
            SupplierOrganization organization, List<SupplierStore> organizationStores) {

        return new SupplierDtos.SupplierResponse(
                organization.getId(), organization.getLegalName(), organization.getDisplayName(),
                organization.getGstin(), organization.getLifecycleStatus(),
                organization.getVerificationStatus(), organization.canTradeNow(),
                organizationStores.stream().map(SupplierService::toStoreResponse).toList());
    }

    private static SupplierDtos.StoreResponse toStoreResponse(SupplierStore store) {
        return new SupplierDtos.StoreResponse(
                store.getId(), store.getSupplierOrganizationId(), store.getName(),
                store.getAddressLine1(), store.getCity(), store.getState(), store.getPincode(),
                store.getLatitude(), store.getLongitude(), store.getContactName(),
                store.getContactPhone(), store.getResponseSlaSeconds(),
                store.getPreparationMinutes(), store.getStatus());
    }

    private static SupplierDtos.VerificationResponse toVerificationResponse(SupplierVerification v) {
        return new SupplierDtos.VerificationResponse(
                v.getId(), v.getSupplierOrganizationId(), v.getVerificationType(),
                v.getStatus(), v.getVerificationSource(), v.getRejectionReason(),
                v.getVerifiedAt(), v.getCreatedAt());
    }
}
