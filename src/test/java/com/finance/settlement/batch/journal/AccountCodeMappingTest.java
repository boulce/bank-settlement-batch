package com.finance.settlement.batch.journal;

import com.finance.settlement.domain.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class AccountCodeMappingTest {

    @ParameterizedTest
    @EnumSource(Transaction.TransactionType.class)
    void everyTransactionTypeHasMapping(Transaction.TransactionType type) {
        AccountCodeMapping.Pair pair = AccountCodeMapping.forType(type);
        assertNotNull(pair);
        assertNotNull(pair.debitCode());
        assertNotNull(pair.creditCode());
        assertNotEquals(pair.debitCode(), pair.creditCode(),
                "차변/대변 계정코드가 동일하면 분개가 무의미함: " + type);
    }

    @Test
    void depositMapping() {
        var p = AccountCodeMapping.forType(Transaction.TransactionType.DEPOSIT);
        assertEquals("101", p.debitCode());
        assertEquals("201", p.creditCode());
    }

    @Test
    void withdrawalMapping_isInverseOfDeposit() {
        var deposit = AccountCodeMapping.forType(Transaction.TransactionType.DEPOSIT);
        var withdrawal = AccountCodeMapping.forType(Transaction.TransactionType.WITHDRAWAL);
        assertEquals(deposit.debitCode(), withdrawal.creditCode());
        assertEquals(deposit.creditCode(), withdrawal.debitCode());
    }

    @Test
    void transferOut_isInverseOfTransferIn() {
        var in = AccountCodeMapping.forType(Transaction.TransactionType.TRANSFER_IN);
        var out = AccountCodeMapping.forType(Transaction.TransactionType.TRANSFER_OUT);
        assertEquals(in.debitCode(), out.creditCode());
        assertEquals(in.creditCode(), out.debitCode());
    }

    @Test
    void feeMapping_creditsRevenueAccount() {
        var p = AccountCodeMapping.forType(Transaction.TransactionType.FEE);
        assertEquals("201", p.debitCode());
        assertEquals("401", p.creditCode());
    }
}
