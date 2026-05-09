package com.finance.settlement.batch.closing;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

@Getter
@RequiredArgsConstructor
public class MonthlyAggregateRow {
    private final String accountNumber;
    private final BigDecimal totalDeposit;
    private final BigDecimal totalWithdrawal;
    private final Long transactionCount;
    private final BigDecimal netAmount;
    private final Long settlementDays;
}
