package com.finance.settlement.repository;

import com.finance.settlement.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @Query("SELECT t FROM Transaction t WHERE t.transactionDate = :date AND t.status = com.finance.settlement.domain.Transaction.TransactionStatus.COMPLETED ORDER BY t.accountNumber")
    List<Transaction> findCompletedByDate(LocalDate date);

    long countByTransactionDateAndStatus(LocalDate date, Transaction.TransactionStatus status);
}
