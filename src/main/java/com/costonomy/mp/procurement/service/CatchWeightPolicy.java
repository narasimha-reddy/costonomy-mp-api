package com.costonomy.mp.procurement.service;

import com.costonomy.mp.procurement.domain.CatchWeight;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * How far a scale reading may be from the accepted quantity (D-128). Configuration, because the right
 * band differs by product and will be tuned with suppliers; the defaults are the product owner's.
 */
@Component
@ConfigurationProperties(prefix = "costonomy.mp.catch-weight")
@Data
public class CatchWeightPolicy {

    /** A reading more than this far below the accepted quantity is refused (percent). */
    private BigDecimal maxUnderPercent = BigDecimal.valueOf(20);

    /** A reading more than this far above it is refused (percent). Within it, only the accepted quantity is billed. */
    private BigDecimal maxOverPercent = BigDecimal.valueOf(10);

    public CatchWeight.Policy asPolicy() {
        return new CatchWeight.Policy(maxUnderPercent, maxOverPercent);
    }
}
