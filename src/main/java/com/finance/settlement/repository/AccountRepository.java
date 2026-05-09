package com.finance.settlement.repository;

import com.finance.settlement.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountNumber(String accountNumber);

    @Query("SELECT a FROM Account a WHERE a.accountNumber IN :accountNumbers")
    List<Account> findAllByAccountNumbers(List<String> accountNumbers);
}
