package com.finance.settlement.repository;

import com.finance.settlement.domain.DailyTransactionSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyTransactionSummaryRepository extends JpaRepository<DailyTransactionSummary, Long> {

    Optional<DailyTransactionSummary> findByAccountNumberAndSettlementDate(String accountNumber, LocalDate date);

    List<DailyTransactionSummary> findBySettlementDate(LocalDate date);
}
