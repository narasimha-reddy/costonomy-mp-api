package com.costonomy.mp.realtime.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.realtime.domain.RealtimeTicket;
import com.costonomy.mp.realtime.repository.RealtimeEventRepository;
import com.costonomy.mp.realtime.repository.RealtimeTicketStore;
import com.costonomy.mp.realtime.web.dto.RealtimeDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Issues and spends WebSocket handshake tickets. Doc 09 §4.
 *
 * <p><b>Why a ticket and not the access token.</b> A browser's WebSocket API
 * cannot set headers, so a token would have to travel in the query string — and
 * query strings are logged by servers, proxies and error reporters. Doc 09 forbids
 * logging a token, and a URL is the one place that promise cannot be kept. A
 * ticket is issued over an authenticated request, lives for seconds, is accepted
 * once, and is stored only as a hash.
 *
 * <p>The channels are snapshotted onto the ticket so the client knows what it may
 * join without guessing — but the handshake re-derives them from live grants
 * anyway, so a grant revoked in between takes effect immediately (doc 46).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtimeTicketService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RealtimeTicketStore store;
    private final RealtimeEntitlements entitlements;
    private final RealtimeEventRepository events;
    private final ObjectMapper json;

    /**
     * Seconds, not minutes. The ticket exists to cross one handshake; anything
     * longer is a bearer credential sitting in a client's memory for no reason.
     */
    @Value("${costonomy.mp.realtime.ticket-ttl:30s}")
    private Duration ticketTtl;

    @Value("${costonomy.mp.realtime.socket-path:/api/v1/realtime/socket}")
    private String socketPath;

    @Transactional
    public RealtimeDtos.TicketResponse issue(Long userId, String deviceId) {
        var channels = entitlements.channelNamesFor(userId);
        if (channels.isEmpty()) {
            // Nothing to listen to. Refused rather than handed a socket that will
            // never carry a message, which looks to a client exactly like one that
            // is broken.
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "You don't have access to any live updates yet.");
        }

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        Instant now = Instant.now();
        var record = new RealtimeTicket();
        record.setTicketHash(hash(ticket));
        record.setUserId(userId);
        record.setChannelsJson(writeChannels(channels));
        record.setIssuedAt(now);
        record.setExpiresAt(now.plus(ticketTtl));
        record.setDeviceId(deviceId);
        store.save(record);

        // Start at the newest event rather than at zero: opening the app should
        // not replay history the user has already seen on another device.
        Long cursor = events.latestCursor(channels);

        return new RealtimeDtos.TicketResponse(ticket, socketPath,
                record.getExpiresAt(), cursor == null ? 0L : cursor, channels);
    }

    /**
     * Spend a ticket presented at the handshake.
     *
     * @return the user it belongs to, or null if it was spent, expired or never
     *         existed — all three are the same answer to whoever is asking
     */
    public Long consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        var claimed = store.claim(hash(ticket));
        return claimed == null ? null : claimed.getUserId();
    }

    private String writeChannels(List<String> channels) {
        try {
            return json.writeValueAsString(channels);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not serialise channels", ex);
        }
    }

    /** SHA-256, like refresh tokens. The table is not a list of working credentials. */
    private String hash(String ticket) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(ticket.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
