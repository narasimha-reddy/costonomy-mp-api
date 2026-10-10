package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who inside the right outlet may move money (P03, P13). A refusal is a 404 like every restaurant-side credit
 * endpoint, and a refusal must leave nothing behind: no money, no claim, no idempotency row.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "costonomy.mp.credit.wallet-repay.enabled=true")
class CreditPermissionsIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.costonomy.mp.access.service.RolePermissionCatalog catalog;

    private CreditEdgeSupport e;
    private CreditWalletSupport s;

    @BeforeEach
    void setUp() {
        e = new CreditEdgeSupport(mvc, json, jdbc);
        s = e.s;
    }

    /** A user of the same outlet whose role may see credit but not repay it. */
    private String viewerWithoutRepay(long outletId) throws Exception {
        String phone = ApiClient.freshPhone();
        String token = s.api.login(phone);
        long userId = e.userId(token);
        String code = "TEST_CREDIT_VIEWER_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into role (code, name, scope, description, status, created_at, updated_at, version) "
                + "values (?, 'Credit viewer', 'RESTAURANT', 'test: sees credit, cannot repay', 'ACTIVE', "
                + "now(6), now(6), 0)", code);
        jdbc.update("insert into role_permission (role_id, permission_id, created_at) "
                + "select r.id, p.id, now(6) from role r join permission p on p.code = 'CREDIT_VIEW' where r.code = ?",
                code);
        catalog.refresh(); // the role to permission map is cached once per JVM
        jdbc.update("""
                insert into user_role (user_id, role_id, scope_type, scope_id, status, granted_at, created_at,
                                       updated_at, version)
                select ?, r.id, 'OUTLET', ?, 'ACTIVE', now(6), now(6), now(6), 0 from role r where r.code = ?
                """, userId, outletId, code);
        return token;
    }

    private long idempotencyRows(String token) throws Exception {
        return e.count("select count(*) from idempotency_record where actor_id = ?", e.userId(token));
    }

    @Test
    @DisplayName("P03: a same-outlet role with CREDIT_VIEW but not CREDIT_REPAY gets 404 on wallet repay, claim and withdraw; nothing moves")
    void roleWithoutCreditRepayCannotMoveMoney() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        s.topUp(line.buyer(), "20000.00");
        // A claim the owner made, for the withdraw attempt.
        var claimed = e.claim(line.buyer().token(), invoice, "100.00");
        assertThat(claimed.status()).describedAs(claimed.body().toString()).isEqualTo(201);
        long claimId = claimed.data().get("id").asLong();

        String viewer = viewerWithoutRepay(line.buyer().outletId());
        // Positive control: the role really does see the credit line, so the 404 below is the permission, not the tenant.
        assertThat(e.call("GET", viewer, "/api/v1/credit/agreements/" + line.agreementId(), null, null).status())
                .isEqualTo(200);
        var before = s.snapshot(line);
        long claimsBefore = e.count("select count(*) from credit_payment_claim where credit_invoice_id = ?", invoice);

        var repay = s.repay(viewer, line.agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "1000.00"));
        var newClaim = e.claim(viewer, invoice, "50.00");
        var withdrawn = e.withdrawClaim(viewer, claimId);

        assertThat(repay.status()).describedAs(repay.body().toString()).isEqualTo(404);
        assertThat(newClaim.status()).describedAs(newClaim.body().toString()).isEqualTo(404);
        assertThat(withdrawn.status()).describedAs(withdrawn.body().toString()).isEqualTo(404);
        var after = s.snapshot(line);
        assertThat(after).isEqualTo(before);
        assertThat(e.count("select count(*) from credit_payment_claim where credit_invoice_id = ?", invoice))
                .isEqualTo(claimsBefore);
        assertThat(jdbc.queryForObject("select status from credit_payment_claim where id = ?", String.class, claimId))
                .describedAs("the owner's claim was not withdrawn").isEqualTo("SUBMITTED");
        assertThat(idempotencyRows(viewer)).describedAs("a refusal leaves no idempotency row").isZero();
    }

    @Test
    @DisplayName("P13: a grant revoked while the token is still valid: the next repay is a 404 and nothing moves")
    void grantRevokedMidSessionRepayIs404() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        s.topUp(line.buyer(), "20000.00");
        String staffPhone = ApiClient.freshPhone();
        s.api.post(line.buyer().token(), "/api/v1/outlets/" + line.buyer().outletId() + "/users",
                Map.of("phone", staffPhone, "roleCode", "REST_FINANCE_STAFF"));
        String staff = s.api.login(staffPhone);

        var first = s.repay(staff, line.agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "1000.00", "invoiceIds", List.of(invoice)));
        assertThat(first.status()).describedAs(first.body().toString()).isEqualTo(201);

        jdbc.update("update user_role ur join users u on u.id = ur.user_id set ur.status = 'REVOKED' "
                + "where u.phone = ?", "+91" + staffPhone);
        var before = s.snapshot(line);
        long rowsBefore = idempotencyRows(staff);

        var refused = s.repay(staff, line.agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "1000.00", "invoiceIds", List.of(invoice)));
        var claimRefused = e.claim(staff, invoice, "10.00");

        assertThat(refused.status()).describedAs(refused.body().toString()).isEqualTo(404);
        assertThat(claimRefused.status()).isEqualTo(404);
        assertThat(s.snapshot(line)).isEqualTo(before);
        assertThat(idempotencyRows(staff)).isEqualTo(rowsBefore);
    }

    // ── B1: who on the supplier side may collect (CREDIT_COLLECT, V80) ───────────────────────────────────

    /** A user of the seller's own organisation holding {@code roleCode} on the line's store. */
    private String staff(CreditWalletSupport.Line line, String roleCode) throws Exception {
        long orgId = e.count("select supplier_organization_id from supplier_store where id = ?",
                line.seller().storeId());
        String phone = ApiClient.freshPhone();
        s.api.post(line.seller().token(), "/api/v1/suppliers/" + orgId + "/users",
                Map.of("phone", phone, "roleCode", roleCode, "storeId", line.seller().storeId()));
        return s.api.login(phone);
    }

    private long paid(long invoice) {
        return e.count("select count(*) from credit_payment where credit_invoice_id = ?", invoice);
    }

    private String status(long agreementId) {
        return jdbc.queryForObject("select status from credit_agreement where id = ?", String.class, agreementId);
    }

    @Test
    @DisplayName("PM05: owner, admin, finance and store manager record a payment; a salesperson and another store's owner get 404")
    void recordPaymentMatrix() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        var other = s.creditLine("200000");

        var refused = new java.util.LinkedHashMap<String, String>();
        refused.put("salesperson", staff(line, "SUP_SALESPERSON"));
        refused.put("operations", staff(line, "SUP_OPERATIONS_STAFF"));
        refused.put("another store's owner", other.seller().token());
        for (var who : refused.entrySet()) {
            var reply = s.recordPayment(new CreditWalletSupport.Seller(who.getValue(), line.seller().storeId()),
                    invoice, "100.00");
            assertThat(reply.status()).describedAs(who.getKey() + " " + reply.body()).isEqualTo(404);
        }
        assertThat(paid(invoice)).describedAs("a refusal records nothing").isZero();

        var allowed = new java.util.LinkedHashMap<String, String>();
        allowed.put("owner", line.seller().token());
        allowed.put("admin", staff(line, "SUP_ADMIN"));
        allowed.put("finance", staff(line, "SUP_FINANCE_STAFF"));
        allowed.put("store manager", staff(line, "SUP_STORE_MANAGER"));
        long expected = 0;
        for (var who : allowed.entrySet()) {
            var reply = s.recordPayment(new CreditWalletSupport.Seller(who.getValue(), line.seller().storeId()),
                    invoice, "100.00");
            assertThat(reply.status()).describedAs(who.getKey() + " " + reply.body()).isEqualTo(200);
            assertThat(paid(invoice)).isEqualTo(++expected);
        }
    }

    @Test
    @DisplayName("PM05: confirm and reject a claim follow the same matrix")
    void claimDecisionMatrix() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        String buyer = line.buyer().token();

        long toConfirm = e.claim(buyer, invoice, "100.00").data().get("id").asLong();
        long toReject = e.claim(buyer, invoice, "100.00").data().get("id").asLong();
        String sales = staff(line, "SUP_SALESPERSON");
        String strangers = s.creditLine("200000").seller().token();
        for (String token : List.of(sales, strangers)) {
            assertThat(e.confirm(token, toConfirm, null).status()).isEqualTo(404);
            assertThat(e.call("POST", token, "/api/v1/credit/claims/" + toReject + "/reject", null,
                    Map.of("reason", "Not received")).status()).isEqualTo(404);
        }
        assertThat(jdbc.queryForList("select status from credit_payment_claim where id in (?, ?)", String.class,
                toConfirm, toReject)).containsOnly("SUBMITTED");

        String manager = staff(line, "SUP_STORE_MANAGER");
        var confirmed = e.confirm(manager, toConfirm, null);
        assertThat(confirmed.status()).describedAs(confirmed.body().toString()).isEqualTo(200);
        var rejected = e.call("POST", manager, "/api/v1/credit/claims/" + toReject + "/reject", null,
                Map.of("reason", "Not received"));
        assertThat(rejected.status()).describedAs(rejected.body().toString()).isEqualTo(200);

        // Finance, admin and owner too.
        for (String token : List.of(staff(line, "SUP_FINANCE_STAFF"), staff(line, "SUP_ADMIN"),
                line.seller().token())) {
            long a = e.claim(buyer, invoice, "50.00").data().get("id").asLong();
            long b = e.claim(buyer, invoice, "50.00").data().get("id").asLong();
            assertThat(e.confirm(token, a, null).status()).isEqualTo(200);
            assertThat(e.call("POST", token, "/api/v1/credit/claims/" + b + "/reject", null,
                    Map.of("reason", "Not received")).status()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("PM05: suspend stays CREDIT_MODIFY only, so the store manager cannot suspend but finance can")
    void suspendStaysCreditModify() throws Exception {
        var line = s.creditLine("200000");
        var asManager = e.call("POST", staff(line, "SUP_STORE_MANAGER"),
                "/api/v1/credit/agreements/" + line.agreementId() + "/suspend", null, Map.of("reason", "Review"));
        assertThat(asManager.status()).isEqualTo(404);
        assertThat(status(line.agreementId())).isEqualTo("ACTIVE");
        var asSales = e.call("POST", staff(line, "SUP_SALESPERSON"),
                "/api/v1/credit/agreements/" + line.agreementId() + "/suspend", null, Map.of("reason", "Review"));
        assertThat(asSales.status()).isEqualTo(404);
        var asFinance = e.call("POST", staff(line, "SUP_FINANCE_STAFF"),
                "/api/v1/credit/agreements/" + line.agreementId() + "/suspend", null, Map.of("reason", "Review"));
        assertThat(asFinance.status()).isEqualTo(200);
        assertThat(status(line.agreementId())).isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("PM05/PM06: V80 grants CREDIT_COLLECT to four roles and CREDIT_WRITE_OFF to owner and admin only")
    void v57Grants() {
        catalog.refresh();
        for (String role : List.of("SUP_OWNER", "SUP_ADMIN", "SUP_FINANCE_STAFF", "SUP_STORE_MANAGER")) {
            assertThat(catalog.permissionsForRole(role)).describedAs(role).contains("CREDIT_COLLECT");
        }
        for (String role : List.of("SUP_SALESPERSON", "SUP_OPERATIONS_STAFF")) {
            assertThat(catalog.permissionsForRole(role)).describedAs(role).doesNotContain("CREDIT_COLLECT");
        }
        assertThat(catalog.permissionsForRole("SUP_OWNER")).contains("CREDIT_WRITE_OFF");
        assertThat(catalog.permissionsForRole("SUP_ADMIN")).contains("CREDIT_WRITE_OFF");
        for (String role : List.of("SUP_FINANCE_STAFF", "SUP_STORE_MANAGER", "SUP_SALESPERSON")) {
            assertThat(catalog.permissionsForRole(role)).describedAs(role).doesNotContain("CREDIT_WRITE_OFF");
        }
    }
}
