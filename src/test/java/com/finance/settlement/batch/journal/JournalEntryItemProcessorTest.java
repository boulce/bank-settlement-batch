package com.finance.settlement.batch.journal;

import com.finance.settlement.domain.JournalEntry;
import com.finance.settlement.domain.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JournalEntryItemProcessorTest {

    private final JournalEntryItemProcessor processor = new JournalEntryItemProcessor();

    @Test
    void processEmitsExactlyOneDebitAndOneCredit() {
        Transaction tx = newTransaction(Transaction.TransactionType.DEPOSIT, BigDecimal.valueOf(50_000));

        List<JournalEntry> entries = processor.process(tx);

        assertEquals(2, entries.size());
        long debitCount = entries.stream().filter(e -> e.getEntryType() == JournalEntry.EntryType.DEBIT).count();
        long creditCount = entries.stream().filter(e -> e.getEntryType() == JournalEntry.EntryType.CREDIT).count();
        assertEquals(1, debitCount);
        assertEquals(1, creditCount);
    }

    @Test
    void debitAndCreditAmountsAreEqual_invariant() {
        Transaction tx = newTransaction(Transaction.TransactionType.WITHDRAWAL, new BigDecimal("12345.67"));

        List<JournalEntry> entries = processor.process(tx);

        BigDecimal debit = entries.stream()
                .filter(e -> e.getEntryType() == JournalEntry.EntryType.DEBIT)
                .map(JournalEntry::getAmount).findFirst().orElseThrow();
        BigDecimal credit = entries.stream()
                .filter(e -> e.getEntryType() == JournalEntry.EntryType.CREDIT)
                .map(JournalEntry::getAmount).findFirst().orElseThrow();
        assertEquals(0, debit.compareTo(credit), "복식부기: 차변 = 대변");
        assertEquals(0, tx.getAmount().compareTo(debit), "원장의 금액과도 일치");
    }

    @Test
    void everyTransactionTypeProducesValidPair() {
        for (Transaction.TransactionType type : Transaction.TransactionType.values()) {
            Transaction tx = newTransaction(type, BigDecimal.valueOf(100_000));
            List<JournalEntry> entries = processor.process(tx);
            assertEquals(2, entries.size(), "type " + type + ": 정확히 2 entry");

            JournalEntry debit = entries.stream()
                    .filter(e -> e.getEntryType() == JournalEntry.EntryType.DEBIT)
                    .findFirst().orElseThrow();
            JournalEntry credit = entries.stream()
                    .filter(e -> e.getEntryType() == JournalEntry.EntryType.CREDIT)
                    .findFirst().orElseThrow();
            assertNotEquals(debit.getAccountCode(), credit.getAccountCode(),
                    "type " + type + ": 차변·대변 계정이 서로 달라야 함");
            assertEquals(tx.getTransactionId(), debit.getTransactionId());
            assertEquals(tx.getTransactionId(), credit.getTransactionId());
            assertEquals(tx.getTransactionDate(), debit.getEntryDate());
        }
    }

    @Test
    void nullTransactionTypeThrowsForSkip() {
        Transaction tx = Transaction.builder()
                .transactionId("t-broken")
                .accountNumber("110-123-000001")
                .transactionType(null)
                .amount(BigDecimal.TEN)
                .transactionDate(LocalDate.of(2026, 5, 5))
                .transactionAt(LocalDateTime.of(2026, 5, 5, 9, 0))
                .status(Transaction.TransactionStatus.COMPLETED)
                .build();

        assertThrows(IllegalStateException.class, () -> processor.process(tx),
                "transaction_type 없으면 IllegalStateException → faultTolerant skip 대상");
    }

    private static Transaction newTransaction(Transaction.TransactionType type, BigDecimal amount) {
        return Transaction.builder()
                .transactionId("tx-" + System.nanoTime())
                .accountNumber("110-123-000001")
                .transactionType(type)
                .amount(amount)
                .transactionDate(LocalDate.of(2026, 5, 5))
                .transactionAt(LocalDateTime.of(2026, 5, 5, 9, 0))
                .status(Transaction.TransactionStatus.COMPLETED)
                .description("test")
                .build();
    }
}
