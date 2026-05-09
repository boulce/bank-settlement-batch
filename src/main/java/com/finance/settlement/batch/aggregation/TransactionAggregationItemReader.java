package com.finance.settlement.batch.aggregation;

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

    public JpaPagingItemReader<AggregateRow> create(LocalDate settlementDate) {
        return new JpaPagingItemReaderBuilder<AggregateRow>()
                .name("aggregateReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("""
                        SELECT new com.finance.settlement.batch.aggregation.AggregateRow(
                            t.accountNumber,
                            SUM(CASE
                                WHEN t.transactionType = com.finance.settlement.domain.Transaction.TransactionType.DEPOSIT
                                  OR t.transactionType = com.finance.settlement.domain.Transaction.TransactionType.TRANSFER_IN
                                THEN t.amount ELSE 0 END),
                            SUM(CASE
                                WHEN t.transactionType = com.finance.settlement.domain.Transaction.TransactionType.WITHDRAWAL
                                  OR t.transactionType = com.finance.settlement.domain.Transaction.TransactionType.TRANSFER_OUT
                                  OR t.transactionType = com.finance.settlement.domain.Transaction.TransactionType.FEE
                                THEN t.amount ELSE 0 END),
                            COUNT(t)
                        )
                        FROM Transaction t
                        WHERE t.transactionDate = :date
                          AND t.status = com.finance.settlement.domain.Transaction.TransactionStatus.COMPLETED
                        GROUP BY t.accountNumber
                        ORDER BY t.accountNumber
                        """)
                .parameterValues(Map.of("date", settlementDate))
                .pageSize(1000)
                .build();
    }
}
