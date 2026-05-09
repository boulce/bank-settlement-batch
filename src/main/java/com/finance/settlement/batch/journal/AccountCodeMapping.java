package com.finance.settlement.batch.journal;

import com.finance.settlement.domain.Transaction;

/**
 * 거래 타입 → 회계 계정코드 매핑.
 * 실제 은행은 외부 룰 테이블로 관리하지만, 본 프로젝트는 단순화를 위해 코드 내부에 둔다.
 */
public final class AccountCodeMapping {

    public record Pair(String debitCode, String debitName, String creditCode, String creditName) {}

    private AccountCodeMapping() {}

    public static Pair forType(Transaction.TransactionType type) {
        return switch (type) {
            case DEPOSIT      -> new Pair("101", "현금",      "201", "고객예금");
            case WITHDRAWAL   -> new Pair("201", "고객예금",  "101", "현금");
            case TRANSFER_IN  -> new Pair("501", "이체대기",  "201", "고객예금");
            case TRANSFER_OUT -> new Pair("201", "고객예금",  "501", "이체대기");
            case FEE          -> new Pair("201", "고객예금",  "401", "수수료수익");
        };
    }
}
