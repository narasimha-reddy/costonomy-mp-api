package com.costonomy.mp.wallet.invoice.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

/**
 * How the reading and the review are kept in their JSON columns (D-115). MySQL's JSON type turns a decimal number into
 * a double ({@code 2640.00} comes back as {@code 2640.0}, exact only to about 15 digits), so money is written as a
 * string ({@code "2640.00"}), which MySQL keeps as it is. Reading accepts both, so rows written before this still
 * read. The API answer is written by the app's own mapper, as numbers with the scale they were stored with, so a GET
 * and the PUT that saved it give the same figures.
 */
final class InvoiceJson {

    private InvoiceJson() {
    }

    static ObjectMapper storage(ObjectMapper base) {
        ObjectMapper copy = base.copy();
        copy.configOverride(BigDecimal.class).setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING));
        return copy;
    }
}
