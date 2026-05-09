package com.finance.settlement.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_tx_account_date", columnList = "account_number, transaction_date"),
        @Index(name = "idx_tx_status_date", columnList = "status, transaction_date")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, unique = true, length = 36)
    private String transactionId;

    @Column(name = "account_number", nullable = false, length = 20)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20)
    private TransactionType transactionType;

    @Column(name = "amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "transaction_at", nullable = false)
    private LocalDateTime transactionAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "description", length = 200)
    private String description;

    public enum TransactionType {
        DEPOSIT,
        WITHDRAWAL,
        TRANSFER_OUT,
        TRANSFER_IN,
        FEE
    }

    public enum TransactionStatus {
        PENDING,
        COMPLETED,
        FAILED,
        SETTLED
    }

    @Builder
    public Transaction(String transactionId, String accountNumber, TransactionType transactionType,
                       BigDecimal amount, LocalDate transactionDate, LocalDateTime transactionAt,
                       TransactionStatus status, String description) {
        this.transactionId = transactionId;
        this.accountNumber = accountNumber;
        this.transactionType = transactionType;
        this.amount = amount;
        this.transactionDate = transactionDate;
        this.transactionAt = transactionAt;
        this.status = status;
        this.description = description;
    }

    public void markAsSettled() {
        this.status = TransactionStatus.SETTLED;
    }
}
