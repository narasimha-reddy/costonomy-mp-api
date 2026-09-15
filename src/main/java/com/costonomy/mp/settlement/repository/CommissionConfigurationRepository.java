package com.costonomy.mp.settlement.repository;

import com.costonomy.mp.settlement.domain.CommissionConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommissionConfigurationRepository
        extends JpaRepository<CommissionConfiguration, Long> {

    List<CommissionConfiguration> findByStatusOrderByIdDesc(String status);
}
