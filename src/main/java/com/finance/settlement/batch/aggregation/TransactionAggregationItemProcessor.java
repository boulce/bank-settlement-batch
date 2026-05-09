package com.finance.settlement.batch.aggregation;

import com.finance.settlement.domain.Account;
import com.finance.settlement.domain.DailyTransactionSummary;
import com.finance.settlement.domain.Transaction;
import com.finance.settlement.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionAggregationItemProcessor implements ItemProcessor<Transaction, DailyTransactionSummary> {

    private final AccountRepository accountRepository;

    private final Map<String, DailyTransactionSummary> summaryCache = new HashMap<>();
    private LocalDate currentDate;

    public void setCurrentDate(LocalDate date) {
        this.currentDate = date;
        this.summaryCache.clear();
    }

    @Override
    public DailyTransactionSummary process(@NonNull Transaction transaction) {
        String accountNumber = transaction.getAccountNumber();

        summaryCache.computeIfAbsent(accountNumber, k -> {
            Account account = accountRepository.findByAccountNumber(k)
                    .orElseThrow(() -> new IllegalStateException("계좌 없음: " + k));
            return DailyTransactionSummary.builder()
                    .accountNumber(k)
                    .settlementDate(currentDate)
                    .totalDeposit(BigDecimal.ZERO)
                    .totalWithdrawal(BigDecimal.ZERO)
                    .transactionCount(0)
                    .netAmount(BigDecimal.ZERO)
                    .openingBalance(account.getBalance())
                    .closingBalance(account.getBalance())
                    .status(DailyTransactionSummary.SummaryStatus.IN_PROGRESS)
                    .build();
        });

        accumulate(summaryCache.get(accountNumber), transaction);
        return summaryCache.get(accountNumber);
    }

    private void accumulate(DailyTransactionSummary summary, Transaction tx) {
        BigDecimal amount = tx.getAmount();
        boolean isInflow = tx.getTransactionType() == Transaction.TransactionType.DEPOSIT
                || tx.getTransactionType() == Transaction.TransactionType.TRANSFER_IN;

        summaryCache.put(summary.getAccountNumber(),
                DailyTransactionSummary.builder()
                        .accountNumber(summary.getAccountNumber())
                        .settlementDate(summary.getSettlementDate())
                        .totalDeposit(isInflow ? summary.getTotalDeposit().add(amount) : summary.getTotalDeposit())
                        .totalWithdrawal(!isInflow ? summary.getTotalWithdrawal().add(amount) : summary.getTotalWithdrawal())
                        .transactionCount(summary.getTransactionCount() + 1)
                        .netAmount(isInflow ? summary.getNetAmount().add(amount) : summary.getNetAmount().subtract(amount))
                        .openingBalance(summary.getOpeningBalance())
                        .closingBalance(isInflow ? summary.getClosingBalance().add(amount) : summary.getClosingBalance().subtract(amount))
                        .status(DailyTransactionSummary.SummaryStatus.IN_PROGRESS)
                        .build());
    }

    public Map<String, DailyTransactionSummary> getSummaryCache() {
        return summaryCache;
    }
}
