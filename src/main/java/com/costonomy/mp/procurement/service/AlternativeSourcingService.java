package com.costonomy.mp.procurement.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.repository.CanonicalProductRepository;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.discovery.service.RecommendationService;
import com.costonomy.mp.procurement.domain.Requirement;
import com.costonomy.mp.procurement.domain.RequirementItem;
import com.costonomy.mp.procurement.repository.RequirementItemRepository;
import com.costonomy.mp.procurement.repository.RequirementRepository;
import com.costonomy.mp.procurement.web.dto.ProcurementDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finding another supplier for what is still needed. Doc 15, doc 04 §9, §23A.15.
 *
 * <p>The recovery half of guardrail 14. A supplier rejected, timed out or accepted
 * less; the shortfall is still on the requirement, and this is how the restaurant
 * acts on it without rebuilding anything.
 *
 * <p>Doc 15 is explicit that the restaurant must not have to recreate the
 * requirement. So this ranks alternatives against the <b>remaining</b> quantity of
 * each item, and returns items that nobody can currently serve with a reason
 * rather than omitting them — §23A.15: an unmet item is shown and explained, never
 * silently dropped.
 *
 * <p>Doc 01 §10 rule 15: the restaurant chooses. Nothing here places an order.
 */
@Service
@RequiredArgsConstructor
public class AlternativeSourcingService {

    private final RequirementRepository requirements;
    private final RequirementItemRepository requirementItems;
    private final CanonicalProductRepository products;
    private final RecommendationService recommendations;
    private final AccessControlService accessControl;

    @Transactional(readOnly = true)
    public ProcurementDtos.AlternativesResponse findSuppliers(Long actorId, Long requirementId) {
        Requirement requirement = requirements.findById(requirementId)
                .orElseThrow(() -> new NotFoundException("Requirement", requirementId));

        accessControl.requireScoped(actorId, Permissions.OUTLET_VIEW,
                ScopeType.OUTLET, requirement.getOutletId(), "Requirement");

        var items = requirementItems.findByRequirementId(requirementId);

        Map<Long, String> productNames = new HashMap<>();
        products.findAllById(items.stream()
                        .map(RequirementItem::getCanonicalProductId).distinct().toList())
                .forEach(product -> productNames.put(product.getId(), product.getName()));

        List<ProcurementDtos.RequirementAlternative> alternatives = new ArrayList<>();

        for (RequirementItem item : items) {
            var remaining = item.remainingQuantity();
            if (remaining.signum() <= 0) {
                // Already covered. Nothing to source.
                continue;
            }

            // Ranked against what is *still* needed, not the original quantity —
            // a supplier who can cover the remaining 8 kg is a valid answer even if
            // they could never have covered the original 20.
            var ranked = recommendations.recommendForProduct(
                    actorId, item.getCanonicalProductId(), requirement.getOutletId(), remaining);

            alternatives.add(new ProcurementDtos.RequirementAlternative(
                    item.getId(), item.getCanonicalProductId(),
                    productNames.get(item.getCanonicalProductId()),
                    remaining, item.getUnit(),
                    List.copyOf(ranked.offers()),
                    ranked.unservedReason()));
        }

        return new ProcurementDtos.AlternativesResponse(requirementId, alternatives);
    }
}
