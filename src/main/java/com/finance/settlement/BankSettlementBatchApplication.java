package com.finance.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BankSettlementBatchApplication {
    public static void main(String[] args) {
        SpringApplication.run(BankSettlementBatchApplication.class, args);
    }
}
