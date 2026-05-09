package com.finance.settlement.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 계좌별 월별 정산 요약.
 * 일별 summary(daily_transaction_summaries)를 한 달 단위로 다시 합산한 결과.
 */
@Entity
@Table(name = "monthly_account_summaries",
        uniqueConstraints = @UniqueConstraint(columnNames = {"account_number", "year_month_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthlyAccountSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, length = 20)
    private String accountNumber;

    /** YYYY-MM 형식. 예: "2026-05" */
    @Column(name = "year_month_key", nullable = false, length = 7)
    private String yearMonthKey;

    @Column(name = "total_deposit", nullable = false, precision = 20, scale = 2)
    private BigDecimal totalDeposit;

    @Column(name = "total_withdrawal", nullable = false, precision = 20, scale = 2)
    private BigDecimal totalWithdrawal;

    @Column(name = "transaction_count", nullable = false)
    private Integer transactionCount;

    @Column(name = "net_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal netAmount;

    /** 그 달 정산이 실제로 발생한 일수 (= 그 달의 daily summary row 수) */
    @Column(name = "settlement_days", nullable = false)
    private Integer settlementDays;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @Builder
    public MonthlyAccountSummary(String accountNumber, String yearMonthKey,
                                 BigDecimal totalDeposit, BigDecimal totalWithdrawal,
                                 Integer transactionCount, BigDecimal netAmount,
                                 Integer settlementDays) {
        this.accountNumber = accountNumber;
        this.yearMonthKey = yearMonthKey;
        this.totalDeposit = totalDeposit;
        this.totalWithdrawal = totalWithdrawal;
        this.transactionCount = transactionCount;
        this.netAmount = netAmount;
        this.settlementDays = settlementDays;
    }
}
