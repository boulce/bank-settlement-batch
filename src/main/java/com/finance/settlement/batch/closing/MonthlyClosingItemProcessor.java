package com.finance.settlement.batch.closing;

import com.finance.settlement.domain.MonthlyAccountSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@StepScope
public class MonthlyClosingItemProcessor implements ItemProcessor<MonthlyAggregateRow, MonthlyAccountSummary> {

    @Value("#{jobParameters['yearMonth']}")
    private String yearMonth;

    @Override
    public MonthlyAccountSummary process(@NonNull MonthlyAggregateRow row) {
        return MonthlyAccountSummary.builder()
                .accountNumber(row.getAccountNumber())
                .yearMonthKey(yearMonth)
                .totalDeposit(row.getTotalDeposit())
                .totalWithdrawal(row.getTotalWithdrawal())
                .transactionCount(row.getTransactionCount().intValue())
                .netAmount(row.getNetAmount())
                .settlementDays(row.getSettlementDays().intValue())
                .build();
    }
}
