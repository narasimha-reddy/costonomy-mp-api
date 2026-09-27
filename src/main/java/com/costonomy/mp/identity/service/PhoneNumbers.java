package com.costonomy.mp.identity.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
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
        String effectiveCountry = (country == null || country.isBlank()) ? DEFAULT_COUNTRY : country.toUpperCase();
        log.info("[PHONE_EVENT_SEQ 1/3: NORMALIZE_REQUESTED] Phone normalization requested: raw='{}', country='{}' (effectiveCountry='{}')",
                mask(raw), country, effectiveCountry);

        if (raw == null || raw.isBlank()) {
            throw invalid("Raw input is null or blank (country=" + country + ")");
        }

        // Strip everything a human might type: spaces, dashes, brackets, dots.
        String cleaned = raw.replaceAll("[\\s()\\-.]", "");
        log.debug("[PHONE_EVENT_SEQ 2/3: SANITIZED] Cleaned phone input: raw='{}' -> cleaned='{}'", mask(raw), mask(cleaned));

        CountryRule rule = RULES.get(effectiveCountry);
        if (rule == null) {
            throw invalid("Unsupported country '" + country + "'. Supported countries: " + RULES.keySet());
        }

        if (cleaned.startsWith("+")) {
            String digits = cleaned.substring(1);
            if (!digits.matches("\\d{8,15}")) {
                throw invalid("International number with '+' has " + digits.length()
                        + " digits, expected 8-15 (masked=" + mask(digits) + ")");
            }
            String result = "+" + digits;
            log.info("[PHONE_EVENT_SEQ 3/3: NORMALIZED] Normalized via explicit '+' prefix: input='{}' -> result='{}' (country='{}')",
                    mask(raw), mask(result), effectiveCountry);
            return result;
        }

        // 00 is the other international prefix in common use.
        if (cleaned.startsWith("00")) {
            log.info("[PHONE_EVENT_SEQ 2/3: PREFIX_TRANSFORM] Detected international prefix '00', rewriting as '+' and re-normalizing: raw='{}'",
                    mask(raw));
            return normalize("+" + cleaned.substring(2), country);
        }

        if (!cleaned.matches("\\d+")) {
            throw invalid("Cleaned number contains non-digit characters (cleaned=" + mask(cleaned) + ")");
        }

        // A single leading zero is the national trunk prefix, not part of the number.
        if (cleaned.length() == rule.nationalDigits() + 1 && cleaned.startsWith("0")) {
            log.info("[PHONE_EVENT_SEQ 2/3: TRUNK_PREFIX_STRIPPED] Stripped leading national trunk prefix '0' (country='{}'): '{}' -> '{}'",
                    effectiveCountry, mask(cleaned), mask(cleaned.substring(1)));
            cleaned = cleaned.substring(1);
        }

        // Already carries the country code but no '+'.
        if (cleaned.length() == rule.diallingCode().length() + rule.nationalDigits()
                && cleaned.startsWith(rule.diallingCode())) {
            String result = "+" + cleaned;
            log.info("[PHONE_EVENT_SEQ 3/3: NORMALIZED] Matched country dialling code without '+': input='{}' -> result='{}' (country='{}')",
                    mask(raw), mask(result), effectiveCountry);
            return result;
        }

        if (cleaned.length() == rule.nationalDigits()) {
            String result = "+" + rule.diallingCode() + cleaned;
            log.info("[PHONE_EVENT_SEQ 3/3: NORMALIZED] Matched bare national format ({} digits): input='{}' -> result='{}' (country='{}')",
                    rule.nationalDigits(), mask(raw), mask(result), effectiveCountry);
            return result;
        }

        throw invalid("Length " + cleaned.length() + " does not match expected national digits ("
                + rule.nationalDigits() + ") or dialling code + national digits ("
                + (rule.diallingCode().length() + rule.nationalDigits()) + ") for country '" + effectiveCountry
                + "' (cleaned=" + mask(cleaned) + ")");
    }

    /** {@code +919999000001} → {@code +91******0001}. For logs and audit (doc 09 §6). */
    public static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "******" + phone.substring(phone.length() - 4);
    }

    private static BusinessException invalid(String reason) {
        log.warn("[PHONE_EVENT_SEQ: NORMALIZATION_FAILED] Rejecting phone number: {}", reason);
        return new BusinessException(ErrorCode.VALIDATION_ERROR,
                "That doesn't look like a valid mobile number.");
    }
}
