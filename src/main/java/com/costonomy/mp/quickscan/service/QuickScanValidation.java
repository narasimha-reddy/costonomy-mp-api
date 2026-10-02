package com.costonomy.mp.quickscan.service;

import java.util.regex.Pattern;

/**
 * The shape of a UPI virtual payment address. D-106.
 *
 * <p>{@code local-part@handle}: a local part of letters, digits, dots, hyphens
 * and underscores (2–256 characters, matching NPCI's published bounds), an
 * {@code @}, then a handle starting with a letter (the PSP code, e.g.
 * {@code okhdfcbank}). This is a shape check only — it says nothing about
 * whether the handle or the account behind it exists, which only a payout
 * attempt can answer.
 */
final class QuickScanValidation {

    private QuickScanValidation() {
    }

    static final Pattern VPA = Pattern.compile("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z][a-zA-Z0-9]{1,63}$");

    static boolean isValidVpa(String vpa) {
        return vpa != null && VPA.matcher(vpa).matches();
    }
}
