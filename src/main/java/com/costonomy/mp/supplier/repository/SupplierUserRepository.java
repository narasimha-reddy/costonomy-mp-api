package com.costonomy.mp.supplier.repository;

import com.costonomy.mp.supplier.domain.SupplierUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupplierUserRepository extends JpaRepository<SupplierUser, Long> {
    Optional<SupplierUser> findBySupplierOrganizationIdAndUserId(Long organizationId, Long userId);
    List<SupplierUser> findBySupplierOrganizationIdAndStatus(Long organizationId, String status);
    List<SupplierUser> findByUserIdAndStatus(Long userId, String status);
}
