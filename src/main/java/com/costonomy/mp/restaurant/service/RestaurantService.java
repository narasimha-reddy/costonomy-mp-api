package com.costonomy.mp.restaurant.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.Roles;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.restaurant.domain.Outlet;
import com.costonomy.mp.restaurant.domain.Restaurant;
import com.costonomy.mp.restaurant.repository.OutletRepository;
import com.costonomy.mp.restaurant.repository.RestaurantRepository;
import com.costonomy.mp.identity.common.CommonDirectory;
import com.costonomy.mp.identity.common.CommonSync;
import com.costonomy.security.client.CommonApiClient;
import com.costonomy.mp.restaurant.web.dto.RestaurantDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final AccessControlService accessControl;
    private final CommonDirectory directory;
    private final CommonSync commonSync;
    private final CommonApiClient common;
    private final AuditService auditService;

    /**
     * Create a restaurant, and make the creator its owner.
     *
     * <p>The business is created in costonomy-common-api, enrolled in the marketplace; common makes the creator its
     * owner (REST_OWNER). Marketplace then copies it under the same id and keeps its own fields (GSTIN).
     */
    @Transactional
    public RestaurantDtos.RestaurantResponse create(
            Long actorId, RestaurantDtos.CreateRestaurantRequest request) {

        long restaurantId = directory.createOrg(actorId, new CommonDirectory.Business("RESTAURANT",
                request.name(), request.legalName(), request.gstin(), null, null, null));
        commonSync.syncOrg(restaurantId);
        commonSync.accessChanged(actorId);

        var restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new NotFoundException("Restaurant", restaurantId));
        restaurant.setLegalName(request.legalName());
        restaurant.setGstin(request.gstin());
        restaurant.setCreatedBy(actorId);
        restaurantRepository.save(restaurant);

        auditService.record(actorId, Roles.REST_OWNER, "RESTAURANT_CREATED", "RESTAURANT",
                restaurantId, null, "ACTIVE", null, "API");

        if (request.firstOutlet() != null) {
            createOutlet(actorId, restaurantId, request.firstOutlet());
        }

        return get(actorId, restaurantId);
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

        long outletId = directory.createOutlet(actorId, restaurantId, "RESTAURANT", new CommonDirectory.Place(
                request.name(), request.addressLine1(), request.addressLine2(), request.landmark(), request.city(),
                request.state(), request.pincode(), request.latitude(), request.longitude(), request.googlePlaceId(),
                request.formattedAddress(), request.contactName(), request.contactPhone()));
        commonSync.syncOutlet(outletId);
        commonSync.accessChanged(actorId);

        // Common keeps the shared address fields; the marketplace-only ones (and the state as typed) stay here.
        var outlet = outletRepository.findById(outletId)
                .orElseThrow(() -> new NotFoundException("Outlet", outletId));
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
     * Give someone a restaurant role at an outlet.
     *
     * <p>The invitation is made in costonomy-common-api, which owns membership: someone with an account gets the
     * role straight away, a new number when it first signs in to the app.
     */
    @Transactional
    public RestaurantDtos.OutletUserResponse addOutletUser(
            Long actorId, Long outletId, RestaurantDtos.AddOutletUserRequest request) {

        accessControl.requireScoped(actorId, Permissions.OUTLET_USER_MANAGE,
                ScopeType.OUTLET, outletId, "Outlet");

        Long restaurantId = restaurantIdOf(outletId);

        // Which roles may be granted is costonomy-common-api's call (D-75): only this business's roles, Owner
        // only business-wide and only by an owner, and nothing beyond what the actor holds.
        var invited = directory.invite(actorId, restaurantId, request.phone(), request.country(), request.name(),
                request.roleId(), outletId);
        if (invited.userId() != null) {
            commonSync.ensureUser(invited.userId());
            commonSync.accessChanged(invited.userId());
        }

        return new RestaurantDtos.OutletUserResponse(
                invited.userId(), invited.phone(), invited.name(),
                invited.userId() == null ? "INVITED" : "ACTIVE",
                invited.roleName() == null ? List.of() : List.of(invited.roleName()),
                invited.userId() == null ? List.of()
                        : List.copyOf(accessControl.permissionsAt(invited.userId(), ScopeType.OUTLET, outletId)));
    }

    /** Who can act on this outlet, and with what. */
    @Transactional(readOnly = true)
    public List<RestaurantDtos.OutletUserResponse> outletUsers(Long actorId, Long outletId) {
        accessControl.requireScoped(actorId, Permissions.OUTLET_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");

        Long restaurantId = restaurantIdOf(outletId);
        return common.members(restaurantId, outletId, null, null).stream()
                .map(member -> new RestaurantDtos.OutletUserResponse(
                        member.userId(), member.phone(), member.name(), "ACTIVE", List.of(),
                        List.copyOf(accessControl.permissionsAt(member.userId(), ScopeType.OUTLET, outletId))))
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
}
