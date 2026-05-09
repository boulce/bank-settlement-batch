package com.finance.settlement.repository;

import com.finance.settlement.domain.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    @Query("SELECT COALESCE(SUM(j.amount), 0) FROM JournalEntry j WHERE j.entryDate = :date AND j.entryType = 'DEBIT'")
    BigDecimal sumDebitByDate(LocalDate date);

    @Query("SELECT COALESCE(SUM(j.amount), 0) FROM JournalEntry j WHERE j.entryDate = :date AND j.entryType = 'CREDIT'")
    BigDecimal sumCreditByDate(LocalDate date);

    @Modifying
    @Transactional
    long deleteByEntryDate(LocalDate date);
}
