CREATE TABLE applications (
    application_reference VARCHAR(80) PRIMARY KEY,
    customer_reference VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    account_reference VARCHAR(80),
    approved_credit_limit NUMERIC(10, 2) NOT NULL,
    message VARCHAR(250) NOT NULL
);

CREATE TABLE accounts (
    account_reference VARCHAR(80) PRIMARY KEY,
    customer_reference VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    credit_limit NUMERIC(10, 2) NOT NULL,
    posted_balance NUMERIC(10, 2) NOT NULL DEFAULT 0
);

CREATE TABLE cards (
    card_reference VARCHAR(80) PRIMARY KEY,
    account_reference VARCHAR(80) NOT NULL REFERENCES accounts(account_reference),
    network VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    token_reference VARCHAR(100) NOT NULL UNIQUE,
    expires_at VARCHAR(7) NOT NULL,
    daily_limit NUMERIC(10, 2) NOT NULL,
    temporarily_locked BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE simulated_transactions (
    transaction_reference VARCHAR(80) PRIMARY KEY,
    card_reference VARCHAR(80) NOT NULL REFERENCES cards(card_reference),
    merchant_reference VARCHAR(100) NOT NULL,
    amount NUMERIC(10, 2) NOT NULL,
    authorized_amount NUMERIC(10, 2),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    decision VARCHAR(20) NOT NULL,
    authorization_code VARCHAR(80),
    message VARCHAR(250),
    created_at VARCHAR(40) NOT NULL,
    authorization_date DATE
);

CREATE TABLE ledger_events (
    event_reference VARCHAR(80) PRIMARY KEY,
    transaction_reference VARCHAR(80) NOT NULL REFERENCES simulated_transactions(transaction_reference),
    account_reference VARCHAR(80) NOT NULL REFERENCES accounts(account_reference),
    event_type VARCHAR(20) NOT NULL,
    amount NUMERIC(10, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    reason VARCHAR(250) NOT NULL,
    created_at VARCHAR(40) NOT NULL
);

CREATE TABLE idempotency_results (
    scope VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    request_json TEXT NOT NULL,
    request_type VARCHAR(160) NOT NULL,
    result_type VARCHAR(160) NOT NULL,
    result_json TEXT NOT NULL,
    PRIMARY KEY (scope, idempotency_key)
);

CREATE INDEX idx_cards_account ON cards(account_reference);
CREATE INDEX idx_transactions_card ON simulated_transactions(card_reference);
CREATE INDEX idx_transactions_status ON simulated_transactions(status);
CREATE INDEX idx_ledger_account ON ledger_events(account_reference);
CREATE INDEX idx_ledger_transaction ON ledger_events(transaction_reference);
