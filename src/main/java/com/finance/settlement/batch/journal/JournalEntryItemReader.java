package com.finance.settlement.batch.journal;

import com.finance.settlement.domain.Transaction;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JournalEntryItemReader {

    private final EntityManagerFactory entityManagerFactory;

    public JpaPagingItemReader<Transaction> create(LocalDate entryDate) {
        return new JpaPagingItemReaderBuilder<Transaction>()
                .name("journalReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("""
                        SELECT t FROM Transaction t
                        WHERE t.transactionDate = :date
                          AND t.status = com.finance.settlement.domain.Transaction.TransactionStatus.COMPLETED
                        ORDER BY t.id
                        """)
                .parameterValues(Map.of("date", entryDate))
                .pageSize(1000)
                .build();
    }
}
