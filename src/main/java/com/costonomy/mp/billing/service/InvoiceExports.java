package com.costonomy.mp.billing.service;

import com.costonomy.mp.billing.web.dto.BillingDtos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tally voucher XML and GSTR-1 CSV for one invoice. <b>Unverified drafts</b> (D-133): the ledger names are assumed,
 * and neither format has been imported into Tally or the GST offline tool. They are behind the tax-invoice feature
 * flag and every response says so. A tax adviser must check them before anyone files from them.
 */
public final class InvoiceExports {

    public static final String DRAFT_LABEL = "UNVERIFIED DRAFT: not imported into Tally or the GST offline tool; "
            + "ledger names are assumed. Have a tax adviser check before use.";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter GSTR_DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private InvoiceExports() {
    }

    public static String tallyXml(BillingDtos.TaxInvoiceResponse invoice) {
        String date = LocalDate.ofInstant(invoice.issuedAt(), IST).format(DateTimeFormatter.BASIC_ISO_DATE);
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!-- ").append(DRAFT_LABEL).append(" -->\n");
        sb.append("<ENVELOPE>\n  <HEADER>\n    <TALLYREQUEST>Import Data</TALLYREQUEST>\n  </HEADER>\n  <BODY>\n");
        sb.append("    <IMPORTDATA>\n      <REQUESTDESC>\n        <REPORTNAME>Vouchers</REPORTNAME>\n      </REQUESTDESC>\n");
        sb.append("      <REQUESTDATA>\n        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n");
        sb.append("          <VOUCHER VCHTYPE=\"Sales\" ACTION=\"Create\">\n");
        sb.append("            <DATE>").append(date).append("</DATE>\n");
        sb.append("            <VOUCHERTYPENAME>Sales</VOUCHERTYPENAME>\n");
        sb.append("            <VOUCHERNUMBER>").append(xml(invoice.invoiceNumber())).append("</VOUCHERNUMBER>\n");
        sb.append("            <PARTYLEDGERNAME>").append(xml(invoice.buyerName())).append("</PARTYLEDGERNAME>\n");
        sb.append("            <PLACEOFSUPPLY>").append(xml(invoice.placeOfSupply())).append("</PLACEOFSUPPLY>\n");
        ledger(sb, invoice.buyerName(), "Yes", "-" + invoice.totalAmount().toPlainString());
        ledger(sb, "Sales Account", "No", invoice.taxableAmount().toPlainString());
        if (invoice.cgstAmount().signum() > 0) {
            ledger(sb, "CGST Output", "No", invoice.cgstAmount().toPlainString());
        }
        if (invoice.sgstAmount().signum() > 0) {
            ledger(sb, "SGST Output", "No", invoice.sgstAmount().toPlainString());
        }
        if (invoice.igstAmount().signum() > 0) {
            ledger(sb, "IGST Output", "No", invoice.igstAmount().toPlainString());
        }
        if (invoice.deliveryFee().signum() > 0) {
            ledger(sb, "Freight & Carriage", "No", invoice.deliveryFee().toPlainString());
        }
        sb.append("          </VOUCHER>\n        </TALLYMESSAGE>\n      </REQUESTDATA>\n    </IMPORTDATA>\n  </BODY>\n</ENVELOPE>\n");
        return sb.toString();
    }

    private static void ledger(StringBuilder sb, String name, String deemedPositive, String amount) {
        sb.append("            <ALLLEDGERENTRIES.LIST>\n");
        sb.append("              <LEDGERNAME>").append(xml(name)).append("</LEDGERNAME>\n");
        sb.append("              <ISDEEMEDPOSITIVE>").append(deemedPositive).append("</ISDEEMEDPOSITIVE>\n");
        sb.append("              <AMOUNT>").append(amount).append("</AMOUNT>\n");
        sb.append("            </ALLLEDGERENTRIES.LIST>\n");
    }

    /**
     * One row per invoice and tax rate, not per item. A buyer with a GSTIN is a B2B supply; one without is B2CS and
     * is reported by place of supply and rate, with no invoice row.
     */
    public static String gstr1Csv(BillingDtos.TaxInvoiceResponse invoice) {
        Map<BigDecimal, BigDecimal> taxableByRate = new LinkedHashMap<>();
        for (var item : invoice.items()) {
            taxableByRate.merge(item.gstRate().stripTrailingZeros(), item.taxableValue(), BigDecimal::add);
        }
        var sb = new StringBuilder();
        boolean b2b = invoice.buyerGstin() != null && !invoice.buyerGstin().isBlank();
        if (b2b) {
            String date = LocalDate.ofInstant(invoice.issuedAt(), IST).format(GSTR_DATE);
            sb.append("GSTIN/UIN of Recipient,Receiver Name,Invoice Number,Invoice date,Invoice Value,Place Of Supply,"
                    + "Reverse Charge,Applicable % of Tax Rate,Invoice Type,E-Commerce GSTIN,Rate,Taxable Value,Cess Amount\n");
            taxableByRate.forEach((rate, taxable) -> {
                sb.append(csv(invoice.buyerGstin())).append(',').append(csv(invoice.buyerName())).append(',')
                        .append(csv(invoice.invoiceNumber())).append(',').append(date).append(',')
                        .append(invoice.totalAmount().toPlainString()).append(',')
                        .append(csv(invoice.placeOfSupply())).append(",N,,Regular,,")
                        .append(rate.toPlainString()).append(',').append(taxable.toPlainString()).append(",0.00\n");
            });
        } else {
            sb.append("Type,Place Of Supply,Applicable % of Tax Rate,Rate,Taxable Value,Cess Amount,E-Commerce GSTIN\n");
            taxableByRate.forEach((rate, taxable) -> sb.append("OE,").append(csv(invoice.placeOfSupply())).append(",,")
                    .append(rate.toPlainString()).append(',').append(taxable.toPlainString()).append(",0.00,\n"));
        }
        return sb.toString();
    }

    private static String xml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String csv(String text) {
        return "\"" + (text == null ? "" : text.replace("\"", "\"\"")) + "\"";
    }
}
