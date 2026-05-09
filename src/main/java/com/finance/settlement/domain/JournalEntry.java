package com.finance.settlement.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 회계 분개 — 모든 거래를 차변(Debit)/대변(Credit)으로 기록
 * 규칙: 차변 합계 = 대변 합계 (복식부기)
 */
@Entity
@Table(name = "journal_entries", indexes = {
        @Index(name = "idx_je_entry_date", columnList = "entry_date"),
        @Index(name = "idx_je_transaction_id", columnList = "transaction_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, length = 36)
    private String transactionId;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "account_code", nullable = false, length = 10)
    private String accountCode;

    @Column(name = "account_name", nullable = false, length = 50)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 10)
    private EntryType entryType;

    @Column(name = "amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public enum EntryType {
        DEBIT,
        CREDIT
    }

    @Builder
    public JournalEntry(String transactionId, LocalDate entryDate, String accountCode,
                        String accountName, EntryType entryType, BigDecimal amount, String description) {
        this.transactionId = transactionId;
        this.entryDate = entryDate;
        this.accountCode = accountCode;
        this.accountName = accountName;
        this.entryType = entryType;
        this.amount = amount;
        this.description = description;
    }
}
