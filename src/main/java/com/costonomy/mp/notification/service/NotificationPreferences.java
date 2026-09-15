package com.costonomy.mp.notification.service;

import com.costonomy.mp.notification.domain.NotificationCategory;
import com.costonomy.mp.notification.domain.NotificationChannel;
import com.costonomy.mp.notification.domain.NotificationPreference;
import com.costonomy.mp.notification.repository.NotificationPreferenceRepository;
import com.costonomy.mp.notification.web.dto.NotificationDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * What a user has chosen to mute. Doc 08 §5.
 *
 * <p>Two rules, and the second is the one that matters:
 *
 * <p><b>Absent means enabled.</b> A row exists only where something was turned off.
 * The opposite default fails quietly — a restaurant who never learns their order
 * was rejected, and never knew there was a setting to turn on.
 *
 * <p><b>Critical notifications ignore preferences entirely.</b> Doc 08 §5:
 * "critical operational/financial notifications may be mandatory according to
 * policy", and the policy here is that they are. A supplier who has muted order
 * notifications still gets told an order is waiting, because the alternative is an
 * order that expires while they sit next to a silent phone and a restaurant that
 * gets nothing. The mute still applies to everything non-critical in that category.
 */
@Service
@RequiredArgsConstructor
public class NotificationPreferences {

    private final NotificationPreferenceRepository preferences;

    /** Whether this channel may be used for this user. */
    @Transactional(readOnly = true)
    public boolean allows(Long userId, NotificationCategory category,
                          NotificationChannel channel, boolean critical) {
        if (critical) {
            return true;
        }
        return preferences.findByUserIdAndCategoryAndChannel(userId, category, channel)
                .map(NotificationPreference::getEnabled)
                .orElse(true);
    }

    /**
     * Everything, including the defaults nobody has changed.
     *
     * <p>Returning only the stored rows would make a settings screen show a
     * handful of switches and leave the rest to be invented by the client.
     */
    @Transactional(readOnly = true)
    public List<NotificationDtos.PreferenceResponse> forUser(Long userId) {
        var stored = preferences.findByUserId(userId);
        var all = new ArrayList<NotificationDtos.PreferenceResponse>();

        for (NotificationCategory category : NotificationCategory.values()) {
            for (NotificationChannel channel : NotificationChannel.values()) {
                if (!channel.isOutbound()) {
                    // The inbox is not a preference. Muting a category should stop a
                    // phone buzzing, not erase the record that something happened.
                    continue;
                }
                boolean enabled = stored.stream()
                        .filter(preference -> preference.getCategory() == category
                                && preference.getChannel() == channel)
                        .findFirst()
                        .map(NotificationPreference::getEnabled)
                        .orElse(true);
                all.add(new NotificationDtos.PreferenceResponse(category, channel, enabled));
            }
        }
        return all;
    }

    @Transactional
    public List<NotificationDtos.PreferenceResponse> update(
            Long userId, List<NotificationDtos.UpdatePreferenceRequest> updates) {

        for (var update : updates) {
            var preference = preferences
                    .findByUserIdAndCategoryAndChannel(userId, update.category(), update.channel())
                    .orElseGet(() -> {
                        var fresh = new NotificationPreference();
                        fresh.setUserId(userId);
                        fresh.setCategory(update.category());
                        fresh.setChannel(update.channel());
                        return fresh;
                    });
            preference.setEnabled(update.enabled());
            preferences.save(preference);
        }
        return forUser(userId);
    }
}
