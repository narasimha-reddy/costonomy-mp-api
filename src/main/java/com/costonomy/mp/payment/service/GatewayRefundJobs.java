package com.costonomy.mp.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** The timer of gateway refunds (D-204). Reads the database, not memory, so a restart strands nothing. */
@Service
@RequiredArgsConstructor
@Slf4j
public class GatewayRefundJobs {

    private final GatewayRefundService refunds;

    @Scheduled(fixedDelayString = "${costonomy.mp.payments.gateway-refund.poll:PT30S}",
            initialDelayString = "${costonomy.mp.payments.gateway-refund.initial-delay:PT0S}")
    @SchedulerLock(name = "gateway-refund-sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void run() {
        var pass = refunds.sweep();
        if (pass.sent() + pass.leasesRecovered() + pass.reconciled() > 0) {
            log.info("Gateway refunds: {} sent, {} leases recovered, {} reconciled",
                    pass.sent(), pass.leasesRecovered(), pass.reconciled());
        }
    }
}
