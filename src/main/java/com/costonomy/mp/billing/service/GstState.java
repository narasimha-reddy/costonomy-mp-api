package com.costonomy.mp.billing.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The GST state and union-territory codes, the two digits that open every GSTIN and name the place of supply.
 *
 * <p>Two jobs and no fallback: reading the state a registration belongs to from its GSTIN, and turning the
 * state name an outlet was entered with into a code. A name that matches nothing returns empty, and the invoice
 * is refused for lacking a place of supply; it is never guessed. The list is transcribed from the GST code
 * list and is on the adviser's checklist in D-133.
 */
public enum GstState {

    JAMMU_AND_KASHMIR("01", "Jammu and Kashmir"),
    HIMACHAL_PRADESH("02", "Himachal Pradesh"),
    PUNJAB("03", "Punjab"),
    CHANDIGARH("04", "Chandigarh"),
    UTTARAKHAND("05", "Uttarakhand", "Uttaranchal"),
    HARYANA("06", "Haryana"),
    DELHI("07", "Delhi", "NCT of Delhi", "New Delhi"),
    RAJASTHAN("08", "Rajasthan"),
    UTTAR_PRADESH("09", "Uttar Pradesh"),
    BIHAR("10", "Bihar"),
    SIKKIM("11", "Sikkim"),
    ARUNACHAL_PRADESH("12", "Arunachal Pradesh"),
    NAGALAND("13", "Nagaland"),
    MANIPUR("14", "Manipur"),
    MIZORAM("15", "Mizoram"),
    TRIPURA("16", "Tripura"),
    MEGHALAYA("17", "Meghalaya"),
    ASSAM("18", "Assam"),
    WEST_BENGAL("19", "West Bengal"),
    JHARKHAND("20", "Jharkhand"),
    ODISHA("21", "Odisha", "Orissa"),
    CHHATTISGARH("22", "Chhattisgarh"),
    MADHYA_PRADESH("23", "Madhya Pradesh"),
    GUJARAT("24", "Gujarat"),
    DADRA_NAGAR_HAVELI_DAMAN_DIU("26", "Dadra and Nagar Haveli and Daman and Diu", "Daman and Diu",
            "Dadra and Nagar Haveli"),
    MAHARASHTRA("27", "Maharashtra"),
    KARNATAKA("29", "Karnataka"),
    GOA("30", "Goa"),
    LAKSHADWEEP("31", "Lakshadweep"),
    KERALA("32", "Kerala"),
    TAMIL_NADU("33", "Tamil Nadu"),
    PUDUCHERRY("34", "Puducherry", "Pondicherry"),
    ANDAMAN_AND_NICOBAR("35", "Andaman and Nicobar Islands"),
    TELANGANA("36", "Telangana"),
    ANDHRA_PRADESH("37", "Andhra Pradesh"),
    LADAKH("38", "Ladakh");

    private final String code;
    private final String name;
    private final String[] aliases;

    GstState(String code, String name, String... aliases) {
        this.code = code;
        this.name = name;
        this.aliases = aliases;
    }

    public String code() {
        return code;
    }

    public String stateName() {
        return name;
    }

    /** "36-Telangana": how a place of supply is written on the document. */
    public String placeOfSupply() {
        return code + "-" + name;
    }

    private static final Map<String, GstState> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toMap(GstState::code, Function.identity()));

    private static final Map<String, GstState> BY_NAME = Arrays.stream(values())
            .flatMap(s -> java.util.stream.Stream.concat(java.util.stream.Stream.of(s.name), Arrays.stream(s.aliases))
                    .map(n -> Map.entry(normalise(n), s)))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

    public static Optional<GstState> ofCode(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(BY_CODE.get(code.trim()));
    }

    /** The state a GSTIN was registered in: its first two digits. Empty when they are not a known code. */
    public static Optional<GstState> ofGstin(String gstin) {
        if (gstin == null || gstin.trim().length() < 2) {
            return Optional.empty();
        }
        return ofCode(gstin.trim().substring(0, 2));
    }

    /** A state as an outlet or store was entered with it: exact name or a known alias, ignoring case and "&". */
    public static Optional<GstState> ofName(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(BY_NAME.get(normalise(name)));
    }

    private static String normalise(String name) {
        return name.toLowerCase(Locale.ROOT).replace("&", " and ").replaceAll("[^a-z]+", " ").trim();
    }
}
