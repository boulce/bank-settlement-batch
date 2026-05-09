package com.finance.settlement.repository;

import com.finance.settlement.domain.MonthlyAccountSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface MonthlyAccountSummaryRepository extends JpaRepository<MonthlyAccountSummary, Long> {

    @Modifying
    @Transactional
    long deleteByYearMonthKey(String yearMonthKey);
}
