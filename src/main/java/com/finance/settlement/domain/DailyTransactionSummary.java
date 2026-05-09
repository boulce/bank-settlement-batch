package com.finance.settlement.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "daily_transaction_summaries",
        uniqueConstraints = @UniqueConstraint(columnNames = {"account_number", "settlement_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyTransactionSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, length = 20)
    private String accountNumber;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "total_deposit", nullable = false, precision = 20, scale = 2)
    private BigDecimal totalDeposit;

    @Column(name = "total_withdrawal", nullable = false, precision = 20, scale = 2)
    private BigDecimal totalWithdrawal;

    @Column(name = "transaction_count", nullable = false)
    private Integer transactionCount;

    @Column(name = "net_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal netAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SummaryStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public enum SummaryStatus {
        COMPLETED,
        VERIFIED
    }

    @Builder
    public DailyTransactionSummary(String accountNumber, LocalDate settlementDate,
                                   BigDecimal totalDeposit, BigDecimal totalWithdrawal,
                                   Integer transactionCount, BigDecimal netAmount,
                                   SummaryStatus status) {
        this.accountNumber = accountNumber;
        this.settlementDate = settlementDate;
        this.totalDeposit = totalDeposit;
        this.totalWithdrawal = totalWithdrawal;
        this.transactionCount = transactionCount;
        this.netAmount = netAmount;
        this.status = status;
    }
}
