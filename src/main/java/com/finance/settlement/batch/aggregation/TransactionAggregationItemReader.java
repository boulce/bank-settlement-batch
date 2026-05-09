package com.finance.settlement.batch.aggregation;

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
public class TransactionAggregationItemReader {

    private final EntityManagerFactory entityManagerFactory;

    public JpaPagingItemReader<Transaction> create(LocalDate settlementDate) {
        return new JpaPagingItemReaderBuilder<Transaction>()
                .name("transactionReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("""
                        SELECT t FROM Transaction t
                        WHERE t.transactionDate = :date
                          AND t.status = com.finance.settlement.domain.Transaction.TransactionStatus.COMPLETED
                        ORDER BY t.accountNumber, t.id
                        """)
                .parameterValues(Map.of("date", settlementDate))
                .pageSize(1000)
                .build();
    }
}
