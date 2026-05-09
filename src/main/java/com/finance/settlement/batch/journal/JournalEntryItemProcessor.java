package com.finance.settlement.batch.journal;

import com.finance.settlement.domain.JournalEntry;
import com.finance.settlement.domain.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Transaction 1건 → JournalEntry 2건(차변+대변)의 1:N 변환.
 * Stateless. 거래 타입을 모르면 IllegalStateException → faultTolerant skip.
 */
@Slf4j
@Component
public class JournalEntryItemProcessor implements ItemProcessor<Transaction, List<JournalEntry>> {

    @Override
    public List<JournalEntry> process(@NonNull Transaction tx) {
        if (tx.getTransactionType() == null) {
            throw new IllegalStateException("Transaction type missing for tx " + tx.getTransactionId());
        }

        AccountCodeMapping.Pair mapping = AccountCodeMapping.forType(tx.getTransactionType());

        JournalEntry debit = JournalEntry.builder()
                .transactionId(tx.getTransactionId())
                .entryDate(tx.getTransactionDate())
                .accountCode(mapping.debitCode())
                .accountName(mapping.debitName())
                .entryType(JournalEntry.EntryType.DEBIT)
                .amount(tx.getAmount())
                .description(tx.getTransactionType() + " 차변 / " + tx.getAccountNumber())
                .build();

        JournalEntry credit = JournalEntry.builder()
                .transactionId(tx.getTransactionId())
                .entryDate(tx.getTransactionDate())
                .accountCode(mapping.creditCode())
                .accountName(mapping.creditName())
                .entryType(JournalEntry.EntryType.CREDIT)
                .amount(tx.getAmount())
                .description(tx.getTransactionType() + " 대변 / " + tx.getAccountNumber())
                .build();

        return List.of(debit, credit);
    }
}
