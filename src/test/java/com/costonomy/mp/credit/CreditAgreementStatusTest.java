package com.costonomy.mp.credit;

import com.costonomy.mp.credit.domain.CreditAgreementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.costonomy.mp.credit.domain.CreditAgreementStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class CreditAgreementStatusTest {

    @Test
    @DisplayName("only rejected, expired and closed lines can be asked for again")
    void canReRequest() {
        for (CreditAgreementStatus s : CreditAgreementStatus.values()) {
            boolean expected = s == REJECTED || s == EXPIRED || s == CLOSED;
            assertThat(s.canReRequest()).as(s.name()).isEqualTo(expected);
        }
    }
}
