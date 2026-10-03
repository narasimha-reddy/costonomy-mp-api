package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.service.AdminDeliveryExportService;
import com.costonomy.mp.delivery.web.dto.DeliveryExportRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminDeliveryExportServiceTest {

    @Test
    @DisplayName("writeCsv writes expected header and correctly escaped rows")
    void writeCsvFormatsRowsCorrectly() {
        var row = new DeliveryExportRow(
                101L,
                201L,
                1L,
                2L,
                "COSTONOMY",
                "DELIVERED",
                "PIDGE",
                "pidge_del_999",
                new BigDecimal("120.50"),
                "INR",
                "TWO_WHEELER",
                new BigDecimal("5.50"),
                new BigDecimal("0.02"),
                "Ramesh Kumar",
                "+919876543210",
                35,
                1,
                null,
                "No failure, \"smooth\" delivery",
                Instant.parse("2026-09-20T10:00:00Z"),
                Instant.parse("2026-09-20T10:02:00Z"),
                Instant.parse("2026-09-20T10:05:00Z"),
                Instant.parse("2026-09-20T10:15:00Z"),
                Instant.parse("2026-09-20T10:40:00Z"),
                null
        );

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        AdminDeliveryExportService.writeCsv(List.of(row), pw);

        String csv = sw.toString();
        assertThat(csv).startsWith("deliveryId,supplierOrderId,outletId,supplierStoreId,mode,status,providerCode,providerDeliveryId");
        assertThat(csv).contains("101,201,1,2,COSTONOMY,DELIVERED,PIDGE,pidge_del_999,120.50,INR,TWO_WHEELER");
        // Verify CSV escaping with quotes
        assertThat(csv).contains("\"No failure, \"\"smooth\"\" delivery\"");
    }
}
