package com.costonomy.mp.notification.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.notification.service.AnalyticsService;
import com.costonomy.mp.notification.service.NotificationPreferences;
import com.costonomy.mp.notification.service.NotificationService;
import com.costonomy.mp.notification.web.dto.NotificationDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The inbox, its settings, and analytics ingest. Doc 04 §18, doc 08 §7.
 *
 * <p>Everything here is scoped to the caller by their own id. There is no user
 * parameter anywhere, which is the simplest possible answer to "can I read someone
 * else's notifications".
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationService notifications;
    private final NotificationPreferences preferences;
    private final AnalyticsService analytics;

    @GetMapping("/notifications")
    @Operation(
            summary = "Your notifications",
            description = """
                    Newest first, grouped by `category` for §23A.27's sections. `unreadCount`
                    is the true total rather than this page's — the badge is server-backed so
                    it agrees across a user's devices.
                    """)
    public ApiResponse<NotificationDtos.InboxResponse> inbox(
            @RequestParam(required = false, defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(notifications.inbox(
                ActorContext.requireUserId(), unreadOnly, limit));
    }

    @PostMapping("/notifications/{id}/read")
    @Operation(summary = "Mark one as read")
    public ApiResponse<NotificationDtos.NotificationResponse> markRead(@PathVariable Long id) {
        return ApiResponse.ok(notifications.markRead(ActorContext.requireUserId(), id));
    }

    @PostMapping("/notifications/read-all")
    @Operation(summary = "Mark everything as read")
    public ApiResponse<Map<String, Object>> markAllRead() {
        long marked = notifications.markAllRead(ActorContext.requireUserId());
        return ApiResponse.ok(Map.of("marked", marked));
    }

    @GetMapping("/notification-preferences")
    @Operation(
            summary = "Your notification settings",
            description = """
                    Every category and outbound channel, including the defaults nobody has
                    changed — a settings screen should not have to invent the rest.

                    Critical notifications (order rejections, payment failures, credit
                    overdue) are sent regardless of these settings, per doc 08 §5.
                    """)
    public ApiResponse<List<NotificationDtos.PreferenceResponse>> preferences() {
        return ApiResponse.ok(preferences.forUser(ActorContext.requireUserId()));
    }

    @PatchMapping("/notification-preferences")
    @Operation(summary = "Change your notification settings")
    public ApiResponse<List<NotificationDtos.PreferenceResponse>> updatePreferences(
            @Valid @RequestBody NotificationDtos.UpdatePreferencesRequest request) {
        return ApiResponse.ok(preferences.update(
                ActorContext.requireUserId(), request.preferences()));
    }

    @PostMapping("/analytics/events")
    @Operation(
            summary = "Record client analytics events",
            description = """
                    Batched, and idempotent on `clientEventId` — a resent batch is counted
                    once, because a double-counted event quietly inflates every funnel
                    metric.

                    Properties are filtered server-side: anything named like a secret is
                    dropped rather than stored (doc 08 §8). Do not rely on that — do not
                    send them.
                    """)
    public ApiResponse<NotificationDtos.AnalyticsBatchResponse> analytics(
            @Valid @RequestBody NotificationDtos.AnalyticsBatchRequest request) {
        return ApiResponse.ok(analytics.ingest(ActorContext.requireUserId(), request));
    }
}
