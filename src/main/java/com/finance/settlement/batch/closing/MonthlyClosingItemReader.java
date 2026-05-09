package com.finance.settlement.batch.closing;

import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MonthlyClosingItemReader {

    private final EntityManagerFactory entityManagerFactory;

    public JpaPagingItemReader<MonthlyAggregateRow> create(LocalDate monthStart, LocalDate monthEnd) {
        return new JpaPagingItemReaderBuilder<MonthlyAggregateRow>()
                .name("monthlyClosingReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("""
                        SELECT new com.finance.settlement.batch.closing.MonthlyAggregateRow(
                            s.accountNumber,
                            SUM(s.totalDeposit),
                            SUM(s.totalWithdrawal),
                            SUM(s.transactionCount),
                            SUM(s.netAmount),
                            COUNT(s)
                        )
                        FROM DailyTransactionSummary s
                        WHERE s.settlementDate BETWEEN :start AND :end
                        GROUP BY s.accountNumber
                        ORDER BY s.accountNumber
                        """)
                .parameterValues(Map.of("start", monthStart, "end", monthEnd))
                .pageSize(1000)
                .build();
    }
}
