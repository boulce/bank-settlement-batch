package com.finance.settlement.batch.aggregation;

import com.finance.settlement.domain.DailyTransactionSummary;
import com.finance.settlement.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * AggregateRow → DailyTransactionSummary 1:1 변환.
 * 이미 SQL-level GROUP BY로 계좌별 집계가 끝난 상태라 상태(state)를 갖지 않는다.
 * 알 수 없는 계좌가 들어오면 IllegalStateException → faultTolerant skip 정책에 의해 최대 10건까지 스킵.
 */
@Slf4j
@Component
@StepScope
@RequiredArgsConstructor
public class TransactionAggregationItemProcessor implements ItemProcessor<AggregateRow, DailyTransactionSummary> {

    private final AccountRepository accountRepository;

    @Value("#{jobParameters['settlementDate']}")
    private String settlementDateStr;

    @Override
    public DailyTransactionSummary process(@NonNull AggregateRow row) {
        if (!accountRepository.existsByAccountNumber(row.getAccountNumber())) {
            throw new IllegalStateException("Unknown account: " + row.getAccountNumber());
        }

        BigDecimal deposit = row.getTotalDeposit() != null ? row.getTotalDeposit() : BigDecimal.ZERO;
        BigDecimal withdrawal = row.getTotalWithdrawal() != null ? row.getTotalWithdrawal() : BigDecimal.ZERO;

        return DailyTransactionSummary.builder()
                .accountNumber(row.getAccountNumber())
                .settlementDate(LocalDate.parse(settlementDateStr))
                .totalDeposit(deposit)
                .totalWithdrawal(withdrawal)
                .transactionCount(row.getTransactionCount().intValue())
                .netAmount(deposit.subtract(withdrawal))
                .status(DailyTransactionSummary.SummaryStatus.COMPLETED)
                .build();
    }
}
