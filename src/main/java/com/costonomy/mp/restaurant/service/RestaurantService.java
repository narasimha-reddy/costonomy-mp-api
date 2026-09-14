package com.costonomy.mp.restaurant.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.Roles;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.access.service.RoleGrantService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.restaurant.domain.Outlet;
import com.costonomy.mp.restaurant.domain.Restaurant;
import com.costonomy.mp.restaurant.domain.RestaurantUser;
import com.costonomy.mp.restaurant.repository.OutletRepository;
import com.costonomy.mp.restaurant.repository.RestaurantRepository;
import com.costonomy.mp.restaurant.repository.RestaurantUserRepository;
import com.costonomy.mp.identity.service.UserDirectoryService;
import com.costonomy.mp.restaurant.web.dto.RestaurantDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Restaurants, outlets and their members.
 *
 * <p>Every read and write here is scoped. There is no "get restaurant by id" that
 * does not first establish the caller may see it — doc 03 §16 and doc 09 §3 make
 * that the rule rather than a per-endpoint decision.
 */
@Service
@RequiredArgsConstructor
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final OutletRepository outletRepository;
    private final RestaurantUserRepository membershipRepository;
    private final UserDirectoryService userDirectory;
    private final AccessControlService accessControl;
    private final RoleGrantService roleGrants;
    private final AuditService auditService;

    /**
     * Create a restaurant, and make the creator its owner.
     *
     * <p>The owner grant is not optional bookkeeping — without it the creator
     * could not read back the restaurant they just created, because every
     * subsequent call is authorized against {@code user_role}. Creating the
     * organisation and granting the first role have to be one transaction, or a
     * partial failure leaves an orphan nobody can administer.
     */
    @Transactional
    public RestaurantDtos.RestaurantResponse create(
            Long actorId, RestaurantDtos.CreateRestaurantRequest request) {

        var restaurant = new Restaurant();
        restaurant.setName(request.name());
        restaurant.setLegalName(request.legalName());
        restaurant.setGstin(request.gstin());
        restaurant.setCreatedBy(actorId);
        restaurantRepository.save(restaurant);

        var membership = new RestaurantUser();
        membership.setRestaurantId(restaurant.getId());
        membership.setUserId(actorId);
        membership.setStatus("ACTIVE");
        membership.setJoinedAt(Instant.now());
        membershipRepository.save(membership);

        roleGrants.grant(actorId, Roles.REST_OWNER, ScopeType.RESTAURANT, restaurant.getId(), actorId);

        auditService.record(actorId, Roles.REST_OWNER, "RESTAURANT_CREATED", "RESTAURANT",
                restaurant.getId(), null, "ACTIVE", null, "API");

        if (request.firstOutlet() != null) {
            createOutlet(actorId, restaurant.getId(), request.firstOutlet());
        }

        return get(actorId, restaurant.getId());
    }

    @Transactional(readOnly = true)
    public RestaurantDtos.RestaurantResponse get(Long actorId, Long restaurantId) {
        accessControl.requireScoped(actorId, Permissions.RESTAURANT_VIEW,
                ScopeType.RESTAURANT, restaurantId, "Restaurant");

        var restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new NotFoundException("Restaurant", restaurantId));

        var outlets = outletRepository.findByRestaurantId(restaurantId).stream()
                .map(RestaurantService::toOutletResponse)
                .toList();

        return new RestaurantDtos.RestaurantResponse(
                restaurant.getId(), restaurant.getName(), restaurant.getLegalName(),
                restaurant.getGstin(), restaurant.getStatus(), outlets);
    }

    @Transactional
    public RestaurantDtos.RestaurantResponse update(
            Long actorId, Long restaurantId, RestaurantDtos.UpdateRestaurantRequest request) {

        accessControl.requireScoped(actorId, Permissions.RESTAURANT_EDIT,
                ScopeType.RESTAURANT, restaurantId, "Restaurant");

        var restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new NotFoundException("Restaurant", restaurantId));

        // Null means "leave alone"; PATCH semantics, so a client sending three
        // fields does not blank the rest.
        if (request.name() != null) restaurant.setName(request.name());
        if (request.legalName() != null) restaurant.setLegalName(request.legalName());
        if (request.gstin() != null) restaurant.setGstin(request.gstin());
        restaurantRepository.save(restaurant);

        auditService.record(actorId, null, "RESTAURANT_UPDATED", "RESTAURANT",
                restaurantId, null, null, null, "API");

        return get(actorId, restaurantId);
    }

    @Transactional
    public RestaurantDtos.OutletResponse createOutlet(
            Long actorId, Long restaurantId, RestaurantDtos.CreateOutletRequest request) {

        accessControl.requireScoped(actorId, Permissions.RESTAURANT_EDIT,
                ScopeType.RESTAURANT, restaurantId, "Restaurant");

        if (!restaurantRepository.existsById(restaurantId)) {
            throw new NotFoundException("Restaurant", restaurantId);
        }

        var outlet = new Outlet();
        outlet.setRestaurantId(restaurantId);
        apply(outlet, request);
        outletRepository.save(outlet);

        auditService.record(actorId, null, "OUTLET_CREATED", "OUTLET",
                outlet.getId(), null, "ACTIVE", null, "API");

        return toOutletResponse(outlet);
    }

    @Transactional(readOnly = true)
    public RestaurantDtos.OutletResponse getOutlet(Long actorId, Long outletId) {
        accessControl.requireScoped(actorId, Permissions.OUTLET_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");

        return outletRepository.findById(outletId)
                .map(RestaurantService::toOutletResponse)
                .orElseThrow(() -> new NotFoundException("Outlet", outletId));
    }

    @Transactional
    public RestaurantDtos.OutletResponse updateOutlet(
            Long actorId, Long outletId, RestaurantDtos.UpdateOutletRequest request) {

        accessControl.requireScoped(actorId, Permissions.OUTLET_EDIT,
                ScopeType.OUTLET, outletId, "Outlet");

        var outlet = outletRepository.findById(outletId)
                .orElseThrow(() -> new NotFoundException("Outlet", outletId));

        if (request.name() != null) outlet.setName(request.name());
        if (request.addressLine1() != null) outlet.setAddressLine1(request.addressLine1());
        if (request.addressLine2() != null) outlet.setAddressLine2(request.addressLine2());
        if (request.landmark() != null) outlet.setLandmark(request.landmark());
        if (request.city() != null) outlet.setCity(request.city());
        if (request.state() != null) outlet.setState(request.state());
        if (request.pincode() != null) outlet.setPincode(request.pincode());
        if (request.latitude() != null) outlet.setLatitude(request.latitude());
        if (request.longitude() != null) outlet.setLongitude(request.longitude());
        if (request.googlePlaceId() != null) outlet.setGooglePlaceId(request.googlePlaceId());
        if (request.formattedAddress() != null) outlet.setFormattedAddress(request.formattedAddress());
        if (request.contactName() != null) outlet.setContactName(request.contactName());
        if (request.contactPhone() != null) outlet.setContactPhone(request.contactPhone());
        if (request.deliveryInstructions() != null) {
            outlet.setDeliveryInstructions(request.deliveryInstructions());
        }
        if (request.status() != null) outlet.setStatus(request.status());

        outletRepository.save(outlet);

        auditService.record(actorId, null, "OUTLET_UPDATED", "OUTLET",
                outletId, null, outlet.getStatus(), null, "API");

        return toOutletResponse(outlet);
    }

    @Transactional(readOnly = true)
    public List<RestaurantDtos.OutletResponse> outletsOf(Long actorId, Long restaurantId) {
        accessControl.requireScoped(actorId, Permissions.RESTAURANT_VIEW,
                ScopeType.RESTAURANT, restaurantId, "Restaurant");

        return outletRepository.findByRestaurantId(restaurantId).stream()
                .map(RestaurantService::toOutletResponse)
                .toList();
    }

    /**
     * Add a member to an outlet and grant them a role there.
     *
     * <p>Membership and role are written together. Granting a role without the
     * membership row would leave someone with permissions who does not appear in
     * "who belongs to this restaurant"; creating the membership without the role
     * would leave an invited user who can see nothing and cannot be told why.
     *
     * <p>The invitee need not have an account yet — a phone number is enough
     * ({@link UserDirectoryService#findOrInviteByPhone}). The placeholder user is
     * unverified until they complete an OTP themselves.
     */
    @Transactional
    public RestaurantDtos.OutletUserResponse addOutletUser(
            Long actorId, Long outletId, RestaurantDtos.AddOutletUserRequest request) {

        accessControl.requireScoped(actorId, Permissions.OUTLET_USER_MANAGE,
                ScopeType.OUTLET, outletId, "Outlet");

        Long restaurantId = restaurantIdOf(outletId);

        // Only restaurant-side roles can be granted here. Without this check an
        // outlet admin could grant SUP_OWNER or an internal operations role and
        // quietly escalate straight out of their own tenant.
        if (!request.roleCode().startsWith("REST_")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That role can't be granted on an outlet.");
        }

        var user = userDirectory.findOrInviteByPhone(request.phone(), request.country(), request.name());

        ensureMembership(restaurantId, user.getId(), actorId);
        roleGrants.grant(user.getId(), request.roleCode(), ScopeType.OUTLET, outletId, actorId);

        return new RestaurantDtos.OutletUserResponse(
                user.getId(), user.getPhone(), user.getName(), "ACTIVE",
                List.of(request.roleCode()),
                List.copyOf(accessControl.permissionsAt(user.getId(), ScopeType.OUTLET, outletId)));
    }

    /** Who can act on this outlet, and with what. */
    @Transactional(readOnly = true)
    public List<RestaurantDtos.OutletUserResponse> outletUsers(Long actorId, Long outletId) {
        accessControl.requireScoped(actorId, Permissions.OUTLET_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");

        Long restaurantId = restaurantIdOf(outletId);
        var members = membershipRepository.findByRestaurantIdAndStatus(restaurantId, "ACTIVE");
        var users = userDirectory.byIds(members.stream().map(RestaurantUser::getUserId).toList());

        return members.stream()
                .map(member -> {
                    var user = users.get(member.getUserId());
                    var permissions = accessControl.permissionsAt(
                            member.getUserId(), ScopeType.OUTLET, outletId);
                    return new RestaurantDtos.OutletUserResponse(
                            member.getUserId(),
                            user == null ? null : user.getPhone(),
                            user == null ? null : user.getName(),
                            member.getStatus(),
                            List.of(),
                            List.copyOf(permissions));
                })
                // A restaurant-level member with no permissions on *this* outlet is
                // not a member of this outlet for any practical purpose.
                .filter(response -> !response.permissions().isEmpty())
                .toList();
    }

    Long restaurantIdOf(Long outletId) {
        return outletRepository.findById(outletId)
                .map(Outlet::getRestaurantId)
                .orElseThrow(() -> new NotFoundException("Outlet", outletId));
    }

    void ensureMembership(Long restaurantId, Long userId, Long invitedBy) {
        membershipRepository.findByRestaurantIdAndUserId(restaurantId, userId)
                .ifPresentOrElse(existing -> {
                    if (!"ACTIVE".equals(existing.getStatus())) {
                        existing.setStatus("ACTIVE");
                        existing.setJoinedAt(Instant.now());
                        existing.setRemovedAt(null);
                        membershipRepository.save(existing);
                    }
                }, () -> {
                    var membership = new RestaurantUser();
                    membership.setRestaurantId(restaurantId);
                    membership.setUserId(userId);
                    membership.setStatus("ACTIVE");
                    membership.setInvitedBy(invitedBy);
                    membership.setInvitedAt(Instant.now());
                    membership.setJoinedAt(Instant.now());
                    membershipRepository.save(membership);
                });
    }

    private static void apply(Outlet outlet, RestaurantDtos.CreateOutletRequest request) {
        outlet.setName(request.name());
        outlet.setAddressLine1(request.addressLine1());
        outlet.setAddressLine2(request.addressLine2());
        outlet.setLandmark(request.landmark());
        outlet.setCity(request.city());
        outlet.setState(request.state());
        outlet.setPincode(request.pincode());
        outlet.setLatitude(request.latitude());
        outlet.setLongitude(request.longitude());
        outlet.setGooglePlaceId(request.googlePlaceId());
        outlet.setFormattedAddress(request.formattedAddress());
        outlet.setContactName(request.contactName());
        outlet.setContactPhone(request.contactPhone());
        outlet.setDeliveryInstructions(request.deliveryInstructions());
    }

    static RestaurantDtos.OutletResponse toOutletResponse(Outlet outlet) {
        return new RestaurantDtos.OutletResponse(
                outlet.getId(), outlet.getRestaurantId(), outlet.getName(),
                outlet.getAddressLine1(), outlet.getAddressLine2(), outlet.getLandmark(),
                outlet.getCity(), outlet.getState(), outlet.getPincode(),
                outlet.getLatitude(), outlet.getLongitude(),
                outlet.getContactName(), outlet.getContactPhone(),
                outlet.getDeliveryInstructions(), outlet.getStatus());
    }

    static BusinessException invalidRole(String roleCode) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "Unknown role: " + roleCode);
    }
}
