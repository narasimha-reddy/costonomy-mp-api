package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.repository.CourierPayableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Read-only id queries for the payment job, in their own short transactions. */
@Component
@RequiredArgsConstructor
public class CourierPayableRepositoryAccess {

    private final CourierPayableRepository payables;

    @Transactional(readOnly = true)
    public List<Long> readyToPay(Instant now, int limit) {
        return payables.readyToPay(now, limit);
    }

    @Transactional(readOnly = true)
    public List<Long> expiredLeases(Instant now, int limit) {
        return payables.expiredLeases(now, limit);
    }

    @Transactional(readOnly = true)
    public List<Long> ambiguous(Instant now, int limit) {
        return payables.ambiguousToReconcile(now, limit);
    }
}
