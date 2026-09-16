package com.costonomy.mp.notification.web.dto;

import com.costonomy.mp.notification.domain.NotificationCategory;
import com.costonomy.mp.notification.domain.NotificationChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    /**
     * One notification, as the inbox shows it.
     *
     * @param targetType what tapping it opens, as a route the app resolves —
     *                   never a URL the server built, because the app owns its own
     *                   navigation and a server-built link breaks on every redesign
     */
    public record NotificationResponse(
            Long id,
            NotificationCategory category,
            String eventType,
            String title,
            String body,
            String targetType,
            Long targetId,
            /**
             * Which side of the trade was told: {@code OUTLET} or
             * {@code SUPPLIER_STORE}.
             *
             * <p>Sent because the client cannot work it out. A screen that routes
             * by *the viewer's* role sends a supplier to a restaurant URL, and
             * someone who is both a supplier and a restaurant has no role to route
             * by at all. The notification already knew who it was for — this stops
             * that being thrown away.
             */
            String audience,
            boolean critical,
            boolean read,
            Instant createdAt) {
    }

    /**
     * @param unreadCount server-backed, because §23A.27 requires it — a badge
     *                    counted on one phone disagrees with the same user's other
     */
    public record InboxResponse(
            long unreadCount,
            List<NotificationResponse> notifications) {
    }

    public record PreferenceResponse(
            NotificationCategory category,
            NotificationChannel channel,
            boolean enabled) {
    }

    public record UpdatePreferenceRequest(
            @NotNull NotificationCategory category,
            @NotNull NotificationChannel channel,
            @NotNull Boolean enabled) {
    }

    public record UpdatePreferencesRequest(
            @NotEmpty @Valid List<UpdatePreferenceRequest> preferences) {
    }

    // ── Analytics ────────────────────────────────────────────────────────

    public record AnalyticsBatchRequest(
            @NotEmpty(message = "Send at least one event")
            @Size(max = 200, message = "Send at most 200 events at a time")
            @Valid List<AnalyticsEventRequest> events) {
    }

    /**
     * One analytics event from a client.
     *
     * @param clientEventId the client's id for this occurrence, so a retried batch
     *                      does not double-count
     * @param occurredAt    device time. The app batches, so this can be well before
     *                      it was uploaded.
     * @param properties    free-form, and filtered server-side: any property whose
     *                      name looks like a secret is dropped rather than stored
     *                      (doc 08 §8). A client bug must not become a compliance
     *                      incident.
     */
    public record AnalyticsEventRequest(
            @Size(max = 100) String clientEventId,
            @NotBlank(message = "Name the event") @Size(max = 100) String eventName,
            Long outletId,
            Long supplierStoreId,
            @Size(max = 100) String sessionId,
            @Size(max = 16) String platform,
            @Size(max = 32) String appVersion,
            Map<String, Object> properties,
            Instant occurredAt) {
    }

    /**
     * @param accepted how many rows were written
     * @param dropped  duplicates the client had already sent. Reported rather than
     *                 hidden, so a client that is double-sending can find out.
     */
    public record AnalyticsBatchResponse(int accepted, int dropped) {
    }
}
