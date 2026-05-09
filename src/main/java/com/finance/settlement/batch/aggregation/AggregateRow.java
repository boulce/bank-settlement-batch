package com.finance.settlement.batch.aggregation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * JPQL 집계 쿼리 결과 row.
 * Reader가 SQL-level GROUP BY로 계좌별 집계를 미리 끝낸 뒤 이 DTO로 받아오고,
 * Processor는 단순 1:1 매핑만 수행한다.
 */
@Getter
@RequiredArgsConstructor
public class AggregateRow {
    private final String accountNumber;
    private final BigDecimal totalDeposit;
    private final BigDecimal totalWithdrawal;
    private final Long transactionCount;
}
