package com.finance.settlement.config;

import com.finance.settlement.domain.Account;
import com.finance.settlement.domain.Transaction;
import com.finance.settlement.repository.AccountRepository;
import com.finance.settlement.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Slf4j
@Component
@Profile("seed")
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    private static final int ACCOUNT_COUNT = 100;
    private static final int TRANSACTIONS_PER_DAY = 1000;
    private static final int SEED_DAYS = 7;

    /**
     * 시드 anchor 날짜. 미지정 시 LocalDate.now()로 fallback.
     * 운영/포트폴리오 시연에서는 application-seed.properties에 고정값을 지정해
     * 재현 가능한 데이터를 만든다.
     */
    @Value("${seed.anchor-date:#{null}}")
    private String anchorDateStr;

    @Override
    public void run(String... args) {
        try {
            if (accountRepository.count() > 0) {
                log.info("데이터가 이미 존재합니다. Seeding 건너뜀.");
                return;
            }

            log.info("테스트 데이터 생성 시작: 계좌 {}개, 일 {}건 x {}일", ACCOUNT_COUNT, TRANSACTIONS_PER_DAY, SEED_DAYS);
            long start = System.currentTimeMillis();

            List<Account> accounts = seedAccounts();
            seedTransactions(accounts);

            log.info("테스트 데이터 생성 완료: {}ms", System.currentTimeMillis() - start);
        } finally {
            // seed 모드는 데이터 생성만 하고 즉시 종료한다 (web server 미기동 모드와 동일 효과)
            System.exit(0);
        }
    }

    @Transactional
    public List<Account> seedAccounts() {
        List<Account> accounts = new ArrayList<>();
        Account.AccountType[] types = Account.AccountType.values();

        for (int i = 1; i <= ACCOUNT_COUNT; i++) {
            accounts.add(Account.builder()
                    .accountNumber(String.format("110-123-%06d", i))
                    .ownerName("테스트고객" + i)
                    .accountType(types[i % types.length])
                    .balance(BigDecimal.valueOf(1_000_000L + (i * 50_000L)))
                    .build());
        }
        return accountRepository.saveAll(accounts);
    }

    @Transactional
    public void seedTransactions(List<Account> accounts) {
        Random random = new Random(42);
        Transaction.TransactionType[] txTypes = {
                Transaction.TransactionType.DEPOSIT,
                Transaction.TransactionType.WITHDRAWAL,
                Transaction.TransactionType.TRANSFER_IN,
                Transaction.TransactionType.TRANSFER_OUT
        };

        LocalDate anchor = anchorDateStr != null ? LocalDate.parse(anchorDateStr) : LocalDate.now();
        log.info("Seed anchor date: {} (range = {} ~ {})",
                anchor, anchor.minusDays(SEED_DAYS), anchor.minusDays(1));

        for (int day = SEED_DAYS; day >= 1; day--) {
            LocalDate txDate = anchor.minusDays(day);
            List<Transaction> batch = new ArrayList<>();

            for (int i = 0; i < TRANSACTIONS_PER_DAY; i++) {
                Account account = accounts.get(random.nextInt(accounts.size()));
                Transaction.TransactionType type = txTypes[random.nextInt(txTypes.length)];
                long amountValue = (random.nextInt(990) + 1) * 1000L;

                batch.add(Transaction.builder()
                        .transactionId(UUID.randomUUID().toString())
                        .accountNumber(account.getAccountNumber())
                        .transactionType(type)
                        .amount(BigDecimal.valueOf(amountValue))
                        .transactionDate(txDate)
                        .transactionAt(txDate.atTime(random.nextInt(24), random.nextInt(60)))
                        .status(Transaction.TransactionStatus.COMPLETED)
                        .description(type.name() + " " + txDate)
                        .build());
            }
            transactionRepository.saveAll(batch);
            log.info("{}일 거래 {}건 생성", txDate, batch.size());
        }
    }
}
