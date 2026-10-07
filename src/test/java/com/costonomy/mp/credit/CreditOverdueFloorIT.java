package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.service.CreditJobs;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A supplier who lifts an overdue-sweep suspension by hand has decided to carry what is overdue now (SU05, SU06, D-163).
 * The sweep must not undo that within the hour; it acts again only on overdue beyond what was there at that moment.
 */
@AutoConfigureMockMvc
class CreditOverdueFloorIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CreditJobs creditJobs;

    private CreditEdgeSupport e;
    private CreditWalletSupport s;

    @BeforeEach
    void setUp() {
        e = new CreditEdgeSupport(mvc, json, jdbc);
        s = e.s;
    }

    /** A line with ₹40,000 overdue and a ₹10,000 tolerance, already suspended by the sweep. */
    private record Suspended(Line line, long invoice) {
    }

    private Suspended systemSuspended() throws Exception {
        var line = s.creditLine("200000", Map.of("maxOverdueAmount", "10000"));
        long invoice = s.invoice(line, "400", 100);
        s.age(invoice, 10);
        creditJobs.sweepOverdue();
        assertThat(row(line, "status")).isEqualTo("SUSPENDED");
        assertThat(row(line, "suspension_source")).isEqualTo("SYSTEM");
        return new Suspended(line, invoice);
    }

    private Object row(Line line, String column) {
        return jdbc.queryForMap("select " + column + " from credit_agreement where id = ?", line.agreementId())
                .get(column);
    }

    private BigDecimal floor(Line line) {
        return (BigDecimal) row(line, "overdue_floor");
    }

    private void reinstate(Line line) throws Exception {
        var reply = e.call("POST", line.seller().token(),
                "/api/v1/credit/agreements/" + line.agreementId() + "/reinstate", null, Map.of());
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
    }

    @Test
    @DisplayName("SU05: a manual reinstate of a SYSTEM suspension stores the overdue at that moment, and the sweep leaves the line ACTIVE")
    void reinstateThenSweepStaysActive() throws Exception {
        var x = systemSuspended();
        assertThat(floor(x.line())).describedAs("no floor before a manual reinstate").isNull();

        reinstate(x.line());
        assertThat(floor(x.line())).isEqualByComparingTo("40000");
        creditJobs.sweepOverdue();
        creditJobs.sweepOverdue();

        assertThat(row(x.line(), "status")).isEqualTo("ACTIVE");
        assertThat(floor(x.line())).isEqualByComparingTo("40000");
    }

    @Test
    @DisplayName("SU06: new overdue beyond the floor suspends the line again, by SYSTEM; overdue within it does not")
    void newOverdueBeyondFloorSuspendsAgain() throws Exception {
        var x = systemSuspended();
        reinstate(x.line());

        // Part of the old debt is paid: overdue falls below the floor, nothing happens.
        assertThat(s.recordPayment(x.line().seller(), x.invoice(), "1000.00").status()).isEqualTo(200);
        creditJobs.sweepOverdue();
        assertThat(row(x.line(), "status")).isEqualTo("ACTIVE");

        // A new ₹2,000 invoice goes overdue: 39,000 + 2,000 is above the 40,000 floor.
        long second = s.invoice(x.line(), "20", 100);
        s.age(second, 10);
        creditJobs.sweepOverdue();

        assertThat(row(x.line(), "status")).isEqualTo("SUSPENDED");
        assertThat(row(x.line(), "suspension_source")).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("the floor is cleared when overdue returns to zero, not on a part payment")
    void floorClearedWhenOverdueIsZero() throws Exception {
        var x = systemSuspended();
        reinstate(x.line());

        assertThat(s.recordPayment(x.line().seller(), x.invoice(), "10000.00").status()).isEqualTo(200);
        assertThat(floor(x.line())).describedAs("30,000 still overdue").isEqualByComparingTo("40000");

        assertThat(s.recordPayment(x.line().seller(), x.invoice(), "30000.00").status()).isEqualTo(200);
        assertThat(floor(x.line())).describedAs("nothing overdue any more").isNull();

        // Back to the plain rule: new overdue above the ₹10,000 tolerance suspends.
        long next = s.invoice(x.line(), "150", 100);
        s.age(next, 10);
        creditJobs.sweepOverdue();
        assertThat(row(x.line(), "status")).isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("a supplier's own suspension stores no floor, and the plain rule still applies after it is lifted")
    void supplierSuspensionIsUnaffected() throws Exception {
        var line = s.creditLine("200000", Map.of("maxOverdueAmount", "10000"));
        long invoice = s.invoice(line, "400", 100);
        assertThat(e.suspend(line, "Account under review").status()).isEqualTo(200);
        s.age(invoice, 10);
        creditJobs.sweepOverdue(); // already SUSPENDED: untouched
        assertThat(row(line, "suspension_source")).isEqualTo("SUPPLIER");

        reinstate(line);
        assertThat(floor(line)).describedAs("only a SYSTEM suspension leaves a floor").isNull();

        creditJobs.sweepOverdue();
        assertThat(row(line, "status")).isEqualTo("SUSPENDED");
        assertThat(row(line, "suspension_source")).isEqualTo("SYSTEM");
    }
}
