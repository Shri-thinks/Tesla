package com.tesla.simulation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tesla.card.Network;
import com.tesla.simulation.CardSimulationModels.AccountResponse;
import com.tesla.simulation.CardSimulationModels.ApplicationRequest;
import com.tesla.simulation.CardSimulationModels.ApplicationResponse;
import com.tesla.simulation.CardSimulationModels.CardIssueRequest;
import com.tesla.simulation.CardSimulationModels.CardResponse;
import com.tesla.simulation.CardSimulationModels.DisputeRequest;
import com.tesla.simulation.CardSimulationModels.FinancialEventRequest;
import com.tesla.simulation.CardSimulationModels.LedgerEventResponse;
import com.tesla.simulation.CardSimulationModels.TransactionInitiationRequest;
import com.tesla.simulation.CardSimulationModels.TransactionResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class CardSimulationDatabase {

    private static final Set<Class<?>> IDEMPOTENCY_TYPES = Set.of(
            ApplicationRequest.class, ApplicationResponse.class,
            CardIssueRequest.class, CardResponse.class,
            TransactionInitiationRequest.class, TransactionResponse.class,
            FinancialEventRequest.class, DisputeRequest.class, LedgerEventResponse.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CardSimulationDatabase(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void load(CardSimulationService service) {
        Map<String, ApplicationResponse> applications = new LinkedHashMap<>();
        Map<String, CardSimulationService.Account> accounts = new LinkedHashMap<>();
        Map<String, CardSimulationService.Card> cards = new LinkedHashMap<>();
        Map<String, CardSimulationService.SimulatedTransaction> transactions = new LinkedHashMap<>();
        Map<String, List<LedgerEventResponse>> ledgerEntries = new LinkedHashMap<>();
        Map<CardSimulationService.IdempotencyKey, CardSimulationService.IdempotentResult>
                idempotencyResults = new LinkedHashMap<>();

        jdbc.query("SELECT * FROM applications", (RowCallbackHandler) row -> {
            String accountReference = row.getString("account_reference");
            applications.put(row.getString("application_reference"),
                    new ApplicationResponse(row.getString("application_reference"),
                            row.getString("customer_reference"), row.getString("status"),
                            accountReference, row.getBigDecimal("approved_credit_limit"), row.getString("message")));
        });
        jdbc.query("SELECT * FROM accounts", (RowCallbackHandler) row -> {
            CardSimulationService.Account account = new CardSimulationService.Account(
                    row.getString("account_reference"), row.getString("customer_reference"),
                    row.getBigDecimal("credit_limit"));
            account.status = row.getString("status");
            account.postedBalance = row.getBigDecimal("posted_balance");
            accounts.put(account.reference, account);
            ledgerEntries.put(account.reference, new ArrayList<>());
        });
        jdbc.query("SELECT * FROM cards", (RowCallbackHandler) row -> {
            CardSimulationService.Card card = new CardSimulationService.Card(
                    row.getString("card_reference"), row.getString("account_reference"),
                    Network.valueOf(row.getString("network")), row.getBigDecimal("daily_limit"));
            card.status = row.getString("status");
            card.tokenReference = row.getString("token_reference");
            card.expiresAt = YearMonth.parse(row.getString("expires_at"));
            card.temporarilyLocked = row.getBoolean("temporarily_locked");
            cards.put(card.reference, card);
            CardSimulationService.Account account = accounts.get(card.accountReference);
            if (account == null) {
                throw new IllegalStateException("Card references a missing account: " + card.reference);
            }
            account.cardReferences.add(card.reference);
        });
        jdbc.query("SELECT * FROM simulated_transactions", (RowCallbackHandler) row -> {
            CardSimulationService.SimulatedTransaction transaction = new CardSimulationService.SimulatedTransaction(
                    row.getString("transaction_reference"), row.getString("card_reference"),
                    row.getString("merchant_reference"), row.getBigDecimal("amount"), row.getString("currency"));
            transaction.status = row.getString("status");
            transaction.decision = row.getString("decision");
            transaction.authorizationCode = row.getString("authorization_code");
            transaction.message = row.getString("message");
            transaction.authorizedAmount = row.getBigDecimal("authorized_amount");
            transaction.authorizationDate = localDate(row, "authorization_date");
            transaction.createdAt = Instant.parse(row.getString("created_at"));
            transactions.put(transaction.reference, transaction);
        });
        jdbc.query("SELECT * FROM ledger_events ORDER BY created_at, event_reference",
                (RowCallbackHandler) row -> {
                    LedgerEventResponse event = new LedgerEventResponse(
                            row.getString("event_reference"), row.getString("transaction_reference"),
                            row.getString("account_reference"), row.getString("event_type"),
                            row.getBigDecimal("amount"), row.getString("currency"), row.getString("reason"),
                            Instant.parse(row.getString("created_at")));
                    List<LedgerEventResponse> entries = ledgerEntries.get(event.accountReference());
                    if (entries == null) {
                        throw new IllegalStateException(
                                "Ledger event references a missing account: " + event.eventReference());
                    }
                    entries.add(event);
                });
        jdbc.query("SELECT * FROM idempotency_results", (RowCallbackHandler) row -> {
            Object request = readJson(row.getString("request_type"), row.getString("request_json"));
            Object result = readJson(row.getString("result_type"), row.getString("result_json"));
            CardSimulationService.IdempotencyKey key = new CardSimulationService.IdempotencyKey(
                    row.getString("scope"), row.getString("idempotency_key"));
            idempotencyResults.put(key, new CardSimulationService.IdempotentResult(request, result));
        });
        service.restoreState(applications, accounts, cards, transactions, ledgerEntries, idempotencyResults);
    }

    public void save(CardSimulationService service) {
        service.applicationsForPersistence().values().forEach(this::saveApplication);
        service.accountsForPersistence().values().forEach(this::saveAccount);
        service.cardsForPersistence().values().forEach(this::saveCard);
        service.transactionsForPersistence().values().forEach(this::saveTransaction);
        service.ledgerEntriesForPersistence().values().stream()
                .flatMap(List::stream)
                .forEach(this::saveLedgerEvent);
        service.idempotencyResultsForPersistence().forEach(this::saveIdempotencyResult);
    }

    private void saveApplication(ApplicationResponse application) {
        int updated = jdbc.update("""
                UPDATE applications SET customer_reference=?, status=?, account_reference=?,
                    approved_credit_limit=?, message=? WHERE application_reference=?
                """, application.customerReference(), application.status(), application.accountReference(),
                application.approvedCreditLimit(), application.message(), application.applicationReference());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO applications(application_reference, customer_reference, status,
                        account_reference, approved_credit_limit, message) VALUES(?,?,?,?,?,?)
                    """, application.applicationReference(), application.customerReference(), application.status(),
                    application.accountReference(), application.approvedCreditLimit(), application.message());
        }
    }

    private void saveAccount(CardSimulationService.Account account) {
        int updated = jdbc.update("""
                UPDATE accounts SET customer_reference=?, status=?, credit_limit=?, posted_balance=?
                WHERE account_reference=?
                """, account.customerReference, account.status, account.creditLimit,
                account.postedBalance, account.reference);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO accounts(account_reference, customer_reference, status, credit_limit, posted_balance)
                    VALUES(?,?,?,?,?)
                    """, account.reference, account.customerReference, account.status, account.creditLimit,
                    account.postedBalance);
        }
    }

    private void saveCard(CardSimulationService.Card card) {
        int updated = jdbc.update("""
                UPDATE cards SET account_reference=?, network=?, status=?, token_reference=?, expires_at=?,
                    daily_limit=?, temporarily_locked=? WHERE card_reference=?
                """, card.accountReference, card.network.name(), card.status, card.tokenReference,
                card.expiresAt.toString(), card.dailyLimit, card.temporarilyLocked, card.reference);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO cards(card_reference, account_reference, network, status, token_reference,
                        expires_at, daily_limit, temporarily_locked) VALUES(?,?,?,?,?,?,?,?)
                    """, card.reference, card.accountReference, card.network.name(), card.status,
                    card.tokenReference, card.expiresAt.toString(), card.dailyLimit, card.temporarilyLocked);
        }
    }

    private void saveTransaction(CardSimulationService.SimulatedTransaction transaction) {
        Date authorizationDate = transaction.authorizationDate == null
                ? null : Date.valueOf(transaction.authorizationDate);
        int updated = jdbc.update("""
                UPDATE simulated_transactions SET card_reference=?, merchant_reference=?, amount=?,
                    authorized_amount=?, currency=?, status=?, decision=?, authorization_code=?,
                    message=?, created_at=?, authorization_date=? WHERE transaction_reference=?
                """, transaction.cardReference, transaction.merchantReference, transaction.amount,
                transaction.authorizedAmount, transaction.currency, transaction.status, transaction.decision,
                transaction.authorizationCode, transaction.message, transaction.createdAt.toString(),
                authorizationDate, transaction.reference);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO simulated_transactions(transaction_reference, card_reference, merchant_reference,
                        amount, authorized_amount, currency, status, decision, authorization_code, message,
                        created_at, authorization_date) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """, transaction.reference, transaction.cardReference, transaction.merchantReference,
                    transaction.amount, transaction.authorizedAmount, transaction.currency, transaction.status,
                    transaction.decision, transaction.authorizationCode, transaction.message,
                    transaction.createdAt.toString(), authorizationDate);
        }
    }

    private void saveLedgerEvent(LedgerEventResponse event) {
        int updated = jdbc.update("""
                UPDATE ledger_events SET transaction_reference=?, account_reference=?, event_type=?, amount=?,
                    currency=?, reason=?, created_at=? WHERE event_reference=?
                """, event.transactionReference(), event.accountReference(), event.type(), event.amount(),
                event.currency(), event.reason(), event.createdAt().toString(), event.eventReference());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO ledger_events(event_reference, transaction_reference, account_reference, event_type,
                        amount, currency, reason, created_at) VALUES(?,?,?,?,?,?,?,?)
                    """, event.eventReference(), event.transactionReference(), event.accountReference(),
                    event.type(), event.amount(), event.currency(), event.reason(), event.createdAt().toString());
        }
    }

    private void saveIdempotencyResult(
            CardSimulationService.IdempotencyKey key, CardSimulationService.IdempotentResult result) {
        String requestType = result.request().getClass().getName();
        String resultType = result.result().getClass().getName();
        String requestJson = writeJson(result.request());
        String resultJson = writeJson(result.result());
        int updated = jdbc.update("""
                UPDATE idempotency_results SET request_json=?, request_type=?, result_type=?, result_json=?
                WHERE scope=? AND idempotency_key=?
                """, requestJson, requestType, resultType, resultJson, key.scope(), key.key());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO idempotency_results(scope, idempotency_key, request_json, request_type,
                        result_type, result_json) VALUES(?,?,?,?,?,?)
                    """, key.scope(), key.key(), requestJson, requestType, resultType, resultJson);
        }
    }

    private Object readJson(String typeName, String json) {
        Class<?> type = IDEMPOTENCY_TYPES.stream()
                .filter(allowedType -> allowedType.getName().equals(typeName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unsupported idempotency type: " + typeName));
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read persisted idempotency result.", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not write idempotency result.", exception);
        }
    }

    private LocalDate localDate(ResultSet row, String column) throws SQLException {
        Date date = row.getDate(column);
        return date == null ? null : date.toLocalDate();
    }
}
