package com.costonomy.mp.wallet;

import com.costonomy.mp.wallet.service.WalletEntryDetailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WalletEntryDetailMaskTest {

    @Test
    @DisplayName("a VPA keeps the first two characters of its handle and its bank, nothing between")
    void masks() {
        assertThat(WalletEntryDetailService.maskVpa("sriram@okhdfc")).isEqualTo("sr••••@okhdfc");
        assertThat(WalletEntryDetailService.maskVpa("9876543210@ybl")).isEqualTo("98••••@ybl");
        // A two-character handle must not be shown whole.
        assertThat(WalletEntryDetailService.maskVpa("ab@upi")).isEqualTo("a••••@upi");
        assertThat(WalletEntryDetailService.maskVpa(null)).isNull();
        assertThat(WalletEntryDetailService.maskVpa("nohandle")).doesNotContain("nohandle");
    }
}
