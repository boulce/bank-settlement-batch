CREATE TABLE accounts (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    account_number  VARCHAR(20)     NOT NULL,
    owner_name      VARCHAR(50)     NOT NULL,
    account_type    VARCHAR(20)     NOT NULL,
    balance         DECIMAL(20, 2)  NOT NULL DEFAULT 0.00,
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_number (account_number)
);

CREATE TABLE transactions (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    transaction_id      VARCHAR(36)     NOT NULL,
    account_number      VARCHAR(20)     NOT NULL,
    transaction_type    VARCHAR(20)     NOT NULL,
    amount              DECIMAL(20, 2)  NOT NULL,
    transaction_date    DATE            NOT NULL,
    transaction_at      DATETIME        NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    description         VARCHAR(200),
    PRIMARY KEY (id),
    UNIQUE KEY uk_transaction_id (transaction_id),
    INDEX idx_tx_account_date (account_number, transaction_date),
    INDEX idx_tx_status_date (status, transaction_date)
);

CREATE TABLE daily_transaction_summaries (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    account_number      VARCHAR(20)     NOT NULL,
    settlement_date     DATE            NOT NULL,
    total_deposit       DECIMAL(20, 2)  NOT NULL DEFAULT 0.00,
    total_withdrawal    DECIMAL(20, 2)  NOT NULL DEFAULT 0.00,
    transaction_count   INT             NOT NULL DEFAULT 0,
    net_amount          DECIMAL(20, 2)  NOT NULL DEFAULT 0.00,
    status              VARCHAR(20)     NOT NULL DEFAULT 'COMPLETED',
    created_at          DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_date (account_number, settlement_date)
);

CREATE TABLE journal_entries (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    transaction_id  VARCHAR(36)     NOT NULL,
    entry_date      DATE            NOT NULL,
    account_code    VARCHAR(10)     NOT NULL,
    account_name    VARCHAR(50)     NOT NULL,
    entry_type      VARCHAR(10)     NOT NULL,
    amount          DECIMAL(20, 2)  NOT NULL,
    description     VARCHAR(200),
    created_at      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_je_entry_date (entry_date),
    INDEX idx_je_transaction_id (transaction_id)
);
