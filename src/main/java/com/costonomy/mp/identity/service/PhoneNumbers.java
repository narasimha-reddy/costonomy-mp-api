package com.costonomy.mp.identity.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;

import java.util.Map;

/**
 * Normalises phone numbers to E.164.
 *
 * <p>{@code users.phone} is uniquely indexed, so normalisation is what makes that
 * constraint mean "one row per person". Without it {@code 9999000001},
 * {@code 09999000001}, {@code +91 99990 00001} and {@code 91-9999000001} are four
 * users, and a restaurant's purchase manager can end up with two accounts holding
 * different permissions.
 *
 * <p>India-first, since that is the launch market, but the country is a parameter
 * rather than an assumption so adding a market is a config change. A number that
 * already carries a {@code +} prefix is taken at face value and only validated.
 *
 * <p>This is deliberately a small hand-rolled implementation rather than
 * libphonenumber: we need exactly one operation, and the rules for the markets we
 * serve fit in the table below. Swap it for libphonenumber when the second
 * country arrives.
 */
public final class PhoneNumbers {

    /** ISO country → (dialling code, expected national significant digits). */
    private static final Map<String, CountryRule> RULES = Map.of(
            "IN", new CountryRule("91", 10),
            "AE", new CountryRule("971", 9),
            "SG", new CountryRule("65", 8));

    public static final String DEFAULT_COUNTRY = "IN";

    private record CountryRule(String diallingCode, int nationalDigits) {
    }

    private PhoneNumbers() {
    }

    public static String normalize(String raw) {
        return normalize(raw, DEFAULT_COUNTRY);
    }

    /**
     * @return E.164, e.g. {@code +919999000001}
     * @throws BusinessException {@code VALIDATION_ERROR} if it cannot be a valid number
     */
    public static String normalize(String raw, String country) {
        if (raw == null || raw.isBlank()) {
            throw invalid();
        }

        // Strip everything a human might type: spaces, dashes, brackets, dots.
        String cleaned = raw.replaceAll("[\\s()\\-.]", "");

        CountryRule rule = RULES.get(country == null ? DEFAULT_COUNTRY : country.toUpperCase());
        if (rule == null) {
            throw invalid();
        }

        if (cleaned.startsWith("+")) {
            String digits = cleaned.substring(1);
            if (!digits.matches("\\d{8,15}")) {
                throw invalid();
            }
            return "+" + digits;
        }

        // 00 is the other international prefix in common use.
        if (cleaned.startsWith("00")) {
            return normalize("+" + cleaned.substring(2), country);
        }

        if (!cleaned.matches("\\d+")) {
            throw invalid();
        }

        // A single leading zero is the national trunk prefix, not part of the number.
        if (cleaned.length() == rule.nationalDigits() + 1 && cleaned.startsWith("0")) {
            cleaned = cleaned.substring(1);
        }

        // Already carries the country code but no '+'.
        if (cleaned.length() == rule.diallingCode().length() + rule.nationalDigits()
                && cleaned.startsWith(rule.diallingCode())) {
            return "+" + cleaned;
        }

        if (cleaned.length() == rule.nationalDigits()) {
            return "+" + rule.diallingCode() + cleaned;
        }

        throw invalid();
    }

    /** {@code +919999000001} → {@code +91******0001}. For logs and audit (doc 09 §6). */
    public static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "******" + phone.substring(phone.length() - 4);
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_ERROR,
                "That doesn't look like a valid mobile number.");
    }
}
