package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditClockConfig;
import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.doReturn;

/**
 * What the reminder, digest and export suites share: the credit clock replaced by one the test moves (India time), the
 * usual support helpers, and a few readers. The real credit clock is a bean named creditClock; the services take it by
 * that name, so every rule in these suites (limits, quiet hours, due states, India days) reads the test's time.
 */
@AutoConfigureMockMvc
abstract class CreditClockedIT extends AbstractIntegrationTest {

    static final ZoneId IST = CreditClockConfig.ZONE;

    @MockBean(name = "creditClock") Clock clock;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationRelayAccess relay;

    CreditEdgeSupport e;
    CreditWalletSupport s;
    CreditLifecycleSupport l;

    @BeforeEach
    void setUpClocked() {
        e = new CreditEdgeSupport(mvc, json, jdbc);
        s = e.s;
        l = new CreditLifecycleSupport(e, jdbc, relay);
        setNow(today().atTime(12, 0));
    }

    /** The India date, as the real clock has it (the database defaults to it too). */
    static LocalDate today() {
        return LocalDate.now(IST);
    }

    /** Moves the credit clock to this India date and time. */
    void setNow(java.time.LocalDateTime istTime) {
        setNow(istTime.atZone(IST).toInstant());
    }

    void setNow(Instant instant) {
        // doReturn, not when(): background jobs also read this clock, and calling it here to stub it could race them.
        doReturn(instant).when(clock).instant();
        doReturn(IST).when(clock).getZone();
        doReturn(instant.toEpochMilli()).when(clock).millis();
    }

    Instant now() {
        return clock.instant();
    }

    void advance(Duration by) {
        setNow(clock.instant().plus(by));
    }

    ZonedDateTime nowIst() {
        return clock.instant().atZone(IST);
    }

    /** Makes the invoice due on {@code due} with {@code grace} days of grace, whatever status it has. */
    void setDue(long invoice, LocalDate due, int grace) {
        jdbc.update("update credit_invoice set due_date = ?, overdue_after = ? where id = ?", due, due.plusDays(grace),
                invoice);
    }

    String invoiceNumber(long invoice) {
        return jdbc.queryForObject("select invoice_number from credit_invoice where id = ?", String.class, invoice);
    }

    Reply remind(String token, long agreementId, Object body, String key) throws Exception {
        return e.call("POST", token, "/api/v1/credit/agreements/" + agreementId + "/reminders", key, body);
    }

    Reply remind(Line line, Object body) throws Exception {
        return remind(line.seller().token(), line.agreementId(), body, UUID.randomUUID().toString());
    }

    /** A PUT (the edge helper has GET and POST only). */
    Reply put(String token, String path, Object body) throws Exception {
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse();
        String text = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new Reply(response.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    Reply preview(String token, long agreementId, String query) throws Exception {
        return e.call("GET", token, "/api/v1/credit/agreements/" + agreementId + "/reminders/preview" + query,
                null, null);
    }

    long reminders(long agreementId, String kind) {
        return e.count("select count(*) from credit_reminder where credit_agreement_id = ? and kind = ?",
                agreementId, kind);
    }

    long reminders(long agreementId) {
        return e.count("select count(*) from credit_reminder where credit_agreement_id = ?", agreementId);
    }

    /** Outbox events of a type for an aggregate, whose payload mentions the text. */
    long events(String type, long aggregateId) {
        return l.events(type, aggregateId);
    }

    /** CREDIT_EXPORT audit rows for a line (CREDIT_AGREEMENT) or a store (SUPPLIER_STORE). */
    long exports(String entityType, long id) {
        return e.count("select count(*) from audit_log where action = 'CREDIT_EXPORT' and entity_type = ? "
                + "and entity_id = ?", entityType, id);
    }

    List<Map<String, Object>> reminderRows(long agreementId) {
        return jdbc.queryForList("select id, kind, status, channel, message, requested_at, sent_at from credit_reminder "
                + "where credit_agreement_id = ? order by id", agreementId);
    }
}
