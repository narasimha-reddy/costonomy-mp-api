package com.costonomy.mp.chat.repository;

import com.costonomy.mp.chat.domain.ChatThread;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatThreadRepository extends JpaRepository<ChatThread, Long> {

    Optional<ChatThread> findByOutletIdAndSupplierStoreId(Long outletId, Long supplierStoreId);

    /**
     * An outlet's inbox, most recent first.
     *
     * <p>Threads with nothing in them sort last rather than being hidden: one is
     * a conversation somebody opened and has not written in, and dropping it
     * would make the Start Chat button look as though it had failed.
     */
    List<ChatThread> findByOutletIdOrderByLastMessageAtDescIdDesc(Long outletId);

    List<ChatThread> findBySupplierStoreIdOrderByLastMessageAtDescIdDesc(Long supplierStoreId);
}
