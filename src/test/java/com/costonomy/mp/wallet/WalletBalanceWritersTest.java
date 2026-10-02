package com.costonomy.mp.wallet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A wallet's balance moves only through {@code WalletService}'s atomic updates, each with its ledger
 * row (D-104, D-110). The reversal that puts a withdrawal back, the refund job and the operations
 * actions all reach the wallet through {@code RefundWalletPort}; none may hold the wallet repository
 * or write a balance itself, because a balance that changed with nothing to explain it is a figure
 * nobody can dispute. A source scan, so a new caller that goes around the service fails here, not in
 * an incident.
 */
class WalletBalanceWritersTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    @Test
    @DisplayName("only WalletService uses the wallet repository, so only it can move a balance")
    void onlyWalletServiceHoldsTheWalletRepository() throws IOException {
        var holders = sources().stream()
                .filter(path -> read(path).contains("WalletRepository"))
                .map(path -> path.getFileName().toString())
                .toList();

        assertThat(holders).containsExactlyInAnyOrder("WalletRepository.java", "WalletService.java");
    }

    @Test
    @DisplayName("nothing sets a balance on a wallet entity")
    void nobodySetsABalance() throws IOException {
        var offenders = sources().stream()
                .filter(path -> read(path).contains(".setBalance("))
                .map(Path::toString)
                .toList();

        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("the balance is only ever changed by the repository's two guarded statements, credit and debit")
    void onlyTwoStatementsChangeABalance() throws IOException {
        String repository = read(MAIN.resolve("com/costonomy/mp/wallet/repository/WalletRepository.java"));

        assertThat(repository.split("set w\\.balance", -1).length - 1).isEqualTo(2);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
