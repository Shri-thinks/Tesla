package com.tesla.simulation;

import com.tesla.card.Network;
import com.tesla.simulation.CardSimulationModels.AccountResponse;
import com.tesla.simulation.CardSimulationModels.ApplicationRequest;
import com.tesla.simulation.CardSimulationModels.ApplicationResponse;
import com.tesla.simulation.CardSimulationModels.CardIssueRequest;
import com.tesla.simulation.CardSimulationModels.CardLimitRequest;
import com.tesla.simulation.CardSimulationModels.CardResponse;
import com.tesla.simulation.CardSimulationModels.ClearingRequest;
import com.tesla.simulation.CardSimulationModels.DisputeRequest;
import com.tesla.simulation.CardSimulationModels.FinancialEventRequest;
import com.tesla.simulation.CardSimulationModels.LedgerEventResponse;
import com.tesla.simulation.CardSimulationModels.PinChangeResponse;
import com.tesla.simulation.CardSimulationModels.ReconciliationResponse;
import com.tesla.simulation.CardSimulationModels.TransactionInitiationRequest;
import com.tesla.simulation.CardSimulationModels.TransactionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class CardSimulationService {

    private static final BigDecimal MAX_SIMULATED_CREDIT_LIMIT = new BigDecimal("10000.00");
    private final Map<String, ApplicationResponse> applications = new LinkedHashMap<>();
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, Card> cards = new LinkedHashMap<>();
    private final Map<String, SimulatedTransaction> transactions = new LinkedHashMap<>();
    private final Map<String, List<LedgerEventResponse>> ledgerEntries = new LinkedHashMap<>();
    private final Map<IdempotencyKey, IdempotentResult> idempotentResults = new LinkedHashMap<>();

    public synchronized ApplicationResponse apply(ApplicationRequest request) {
        return idempotent("application", request.idempotencyKey(), request, () -> {
            String applicationReference = reference("app");
            if (request.requestedCreditLimit().compareTo(MAX_SIMULATED_CREDIT_LIMIT) > 0) {
                ApplicationResponse response = new ApplicationResponse(applicationReference, request.customerReference(), "DECLINED",
                        null, BigDecimal.ZERO, "Requested limit exceeds the mock approval threshold.");
                applications.put(applicationReference, response);
                return response;
            }
            String accountReference = reference("acct");
            Account account = new Account(accountReference, request.customerReference(),
                    request.requestedCreditLimit());
            accounts.put(accountReference, account);
            ledgerEntries.put(accountReference, new ArrayList<>());
            ApplicationResponse response = new ApplicationResponse(applicationReference, request.customerReference(), "APPROVED",
                    accountReference, request.requestedCreditLimit(), "Mock application approved.");
            applications.put(applicationReference, response);
            return response;
        }, ApplicationResponse.class);
    }

    public synchronized AccountResponse account(String accountReference) {
        return accountResponse(requireAccount(accountReference));
    }

    public synchronized AccountResponse closeAccount(String accountReference) {
        Account account = requireAccount(accountReference);
        if (account.status.equals("CLOSED")) {
            throw conflict("Account is already closed.");
        }
        if (hasPendingFunds(account.reference)) {
            throw conflict("Account cannot be closed while transactions have unsettled funds.");
        }
        account.status = "CLOSED";
        for (String cardReference : account.cardReferences) {
            Card card = cards.get(cardReference);
            if (!card.status.equals("REPLACED")) {
                card.status = "CLOSED";
                card.temporarilyLocked = false;
            }
        }
        return accountResponse(account);
    }

    public synchronized CardResponse issueCard(String accountReference, CardIssueRequest request) {
        return idempotent("card-issue:" + accountReference, request.idempotencyKey(), request, () -> {
            Account account = requireOpenAccount(accountReference);
            validateLimit(account, request.dailyLimit());
            Card card = newCard(account, request.network(), request.dailyLimit());
            return cardResponse(card);
        }, CardResponse.class);
    }

    public synchronized CardResponse card(String cardReference) {
        return cardResponse(requireCard(cardReference));
    }

    public synchronized CardResponse cardOperation(String cardReference, String operation) {
        Card card = requireCard(cardReference);
        Account account = requireAccount(card.accountReference);
        if (!account.status.equals("OPEN")) {
            throw conflict("Card's account is closed.");
        }
        switch (operation) {
            case "activate" -> transition(card, "ISSUED", "ACTIVE", operation);
            case "suspend" -> transition(card, "ACTIVE", "SUSPENDED", operation);
            case "unblock" -> transition(card, "SUSPENDED", "ACTIVE", operation);
            case "block" -> {
                if (!List.of("ISSUED", "ACTIVE", "SUSPENDED").contains(card.status)) {
                    throw conflict("Cannot block a card in " + card.status + " state.");
                }
                card.status = "BLOCKED";
            }
            case "close" -> {
                if (List.of("CLOSED", "REPLACED").contains(card.status)) {
                    throw conflict("Cannot close a card in " + card.status + " state.");
                }
                card.status = "CLOSED";
                card.temporarilyLocked = false;
            }
            case "lock" -> {
                requireActive(card, operation);
                if (card.temporarilyLocked) {
                    throw conflict("Card is already temporarily locked.");
                }
                card.temporarilyLocked = true;
            }
            case "unlock" -> {
                requireActive(card, operation);
                if (!card.temporarilyLocked) {
                    throw conflict("Card is not temporarily locked.");
                }
                card.temporarilyLocked = false;
            }
            default -> throw new IllegalArgumentException("Unsupported card operation: " + operation);
        }
        return cardResponse(card);
    }

    public synchronized CardResponse replaceCard(String cardReference, CardIssueRequest request) {
        return idempotent("card-replace:" + cardReference, request.idempotencyKey(), request, () -> {
            Card oldCard = requireCard(cardReference);
            if (!List.of("ACTIVE", "SUSPENDED", "BLOCKED").contains(oldCard.status)) {
                throw conflict("Cannot replace a card in " + oldCard.status + " state.");
            }
            Account account = requireOpenAccount(oldCard.accountReference);
            validateLimit(account, request.dailyLimit());
            oldCard.status = "REPLACED";
            oldCard.temporarilyLocked = false;
            return cardResponse(newCard(account, request.network(), request.dailyLimit()));
        }, CardResponse.class);
    }

    public synchronized CardResponse updateLimit(String cardReference, CardLimitRequest request) {
        Card card = requireCard(cardReference);
        requireActive(card, "change spending limit");
        validateLimit(requireOpenAccount(card.accountReference), request.dailyLimit());
        card.dailyLimit = request.dailyLimit();
        return cardResponse(card);
    }

    public synchronized PinChangeResponse changePin(String cardReference, String securePinChangeReference) {
        Card card = requireCard(cardReference);
        requireActive(card, "change PIN");
        if (securePinChangeReference == null || securePinChangeReference.isBlank()) {
            throw new IllegalArgumentException("A secure PIN-change reference is required.");
        }
        return new PinChangeResponse(card.reference, "ACCEPTED",
                "Mock PIN change accepted by secure reference; no PIN was received or stored.");
    }

    public synchronized TransactionResponse initiate(TransactionInitiationRequest request) {
        return idempotent("transaction-initiation", request.idempotencyKey(), request, () -> {
            Card card = requireCard(request.cardReference());
            if (!requireAccount(card.accountReference).status.equals("OPEN")) {
                throw conflict("Card's account is closed.");
            }
            String reference = reference("txn");
            SimulatedTransaction transaction = new SimulatedTransaction(reference, card.reference,
                    request.merchantReference(), request.amount(), request.currency());
            transactions.put(reference, transaction);
            return transactionResponse(transaction);
        }, TransactionResponse.class);
    }

    public synchronized TransactionResponse transaction(String transactionReference) {
        return transactionResponse(requireTransaction(transactionReference));
    }

    public synchronized TransactionResponse authorize(String transactionReference) {
        SimulatedTransaction transaction = requireTransaction(transactionReference);
        requireStatus(transaction, "INITIATED", "authorize");
        Card card = requireCard(transaction.cardReference);
        Account account = requireOpenAccount(card.accountReference);
        String declineReason = authorizationDeclineReason(card, account, transaction.amount);
        if (declineReason != null) {
            transaction.status = "DECLINED";
            transaction.decision = "DECLINED";
            transaction.message = declineReason;
            return transactionResponse(transaction);
        }
        transaction.status = "AUTHORIZED";
        transaction.decision = "APPROVED";
        transaction.message = "Mock authorization approved.";
        transaction.authorizationCode = reference("auth");
        transaction.authorizedAmount = transaction.amount;
        transaction.authorizationDate = LocalDate.now();
        return transactionResponse(transaction);
    }

    public synchronized TransactionResponse reverse(String transactionReference) {
        SimulatedTransaction transaction = requireTransaction(transactionReference);
        requireStatus(transaction, "AUTHORIZED", "reverse");
        transaction.status = "REVERSED";
        return transactionResponse(transaction);
    }

    public synchronized TransactionResponse clear(String transactionReference, ClearingRequest request) {
        SimulatedTransaction transaction = requireTransaction(transactionReference);
        requireStatus(transaction, "AUTHORIZED", "clear");
        Card card = requireCard(transaction.cardReference);
        Account account = requireOpenAccount(card.accountReference);
        BigDecimal availableWithoutThisHold = account.creditLimit
                .subtract(account.postedBalance)
                .subtract(reservedFunds(account.reference, transaction.reference));
        if (request.amount().compareTo(availableWithoutThisHold) > 0) {
            throw conflict("Clearing amount exceeds available credit.");
        }
        transaction.amount = request.amount();
        transaction.status = "CLEARED";
        return transactionResponse(transaction);
    }

    public synchronized TransactionResponse settle(String transactionReference) {
        SimulatedTransaction transaction = requireTransaction(transactionReference);
        requireStatus(transaction, "CLEARED", "settle");
        transaction.status = "SETTLED";
        return transactionResponse(transaction);
    }

    public synchronized TransactionResponse post(String transactionReference) {
        SimulatedTransaction transaction = requireTransaction(transactionReference);
        requireStatus(transaction, "SETTLED", "post");
        Card card = requireCard(transaction.cardReference);
        Account account = requireOpenAccount(card.accountReference);
        account.postedBalance = account.postedBalance.add(transaction.amount);
        addLedgerEntry(account.reference, transaction.reference, "POSTING", transaction.amount,
                transaction.currency, "Transaction posted.");
        transaction.status = "POSTED";
        return transactionResponse(transaction);
    }

    public synchronized LedgerEventResponse refund(String transactionReference, FinancialEventRequest request) {
        return financialEvent(transactionReference, "refund", request, false);
    }

    public synchronized LedgerEventResponse chargeback(String transactionReference, FinancialEventRequest request) {
        return financialEvent(transactionReference, "chargeback", request, false);
    }

    public synchronized LedgerEventResponse fee(String transactionReference, FinancialEventRequest request) {
        return financialEvent(transactionReference, "fee", request, true);
    }

    public synchronized LedgerEventResponse adjustment(
            String transactionReference, FinancialEventRequest request) {
        return financialEvent(transactionReference, "adjustment", request, true);
    }

    public synchronized LedgerEventResponse dispute(String transactionReference, DisputeRequest request) {
        return idempotent("dispute:" + transactionReference, request.idempotencyKey(), request, () -> {
            SimulatedTransaction transaction = requireTransaction(transactionReference);
            requireStatus(transaction, "POSTED", "dispute");
            Card card = requireCard(transaction.cardReference);
            return addLedgerEntry(card.accountReference, transaction.reference, "DISPUTE",
                    BigDecimal.ZERO, transaction.currency, request.reason());
        }, LedgerEventResponse.class);
    }

    public synchronized List<TransactionResponse> accountTransactions(String accountReference) {
        requireAccount(accountReference);
        return transactions.values().stream()
                .filter(transaction -> cards.get(transaction.cardReference).accountReference.equals(accountReference))
                .map(this::transactionResponse)
                .toList();
    }

    public synchronized ReconciliationResponse reconcile(String accountReference) {
        Account account = requireAccount(accountReference);
        List<LedgerEventResponse> entries = List.copyOf(ledgerEntries.get(accountReference));
        BigDecimal ledgerBalance = entries.stream()
                .map(LedgerEventResponse::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal difference = account.postedBalance.subtract(ledgerBalance);
        return new ReconciliationResponse(account.reference, account.postedBalance, ledgerBalance,
                difference, difference.compareTo(BigDecimal.ZERO) == 0, entries);
    }

    private LedgerEventResponse financialEvent(
            String transactionReference,
            String eventType,
            FinancialEventRequest request,
            boolean increasesBalance) {
        return idempotent(eventType + ":" + transactionReference, request.idempotencyKey(), request, () -> {
            SimulatedTransaction transaction = requireTransaction(transactionReference);
            requireStatus(transaction, "POSTED", eventType);
            Card card = requireCard(transaction.cardReference);
            Account account = requireOpenAccount(card.accountReference);
            if (!increasesBalance) {
                BigDecimal alreadyReturned = ledgerEntries.get(account.reference).stream()
                        .filter(entry -> entry.transactionReference().equals(transaction.reference))
                        .filter(entry -> entry.type().equals("REFUND") || entry.type().equals("CHARGEBACK"))
                        .map(entry -> entry.amount().abs())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (alreadyReturned.add(request.amount()).compareTo(transaction.amount) > 0) {
                    throw conflict("Refund or chargeback total cannot exceed the posted transaction amount.");
                }
                account.postedBalance = account.postedBalance.subtract(request.amount());
            } else {
                account.postedBalance = account.postedBalance.add(request.amount());
            }
            String type = eventType.toUpperCase();
            BigDecimal signedAmount = increasesBalance ? request.amount() : request.amount().negate();
            return addLedgerEntry(account.reference, transaction.reference, type, signedAmount,
                    transaction.currency, request.reason());
        }, LedgerEventResponse.class);
    }

    private String authorizationDeclineReason(Card card, Account account, BigDecimal amount) {
        if (!card.status.equals("ACTIVE")) {
            return "Card is not active.";
        }
        if (card.temporarilyLocked) {
            return "Card is temporarily locked.";
        }
        BigDecimal dailySpend = transactions.values().stream()
                .filter(transaction -> transaction.cardReference.equals(card.reference))
                .filter(transaction -> transaction.authorizationDate != null
                        && transaction.authorizationDate.equals(LocalDate.now()))
                .filter(transaction -> List.of("AUTHORIZED", "CLEARED", "SETTLED", "POSTED").contains(transaction.status))
                .map(transaction -> transaction.authorizedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (dailySpend.add(amount).compareTo(card.dailyLimit) > 0) {
            return "Daily spending limit exceeded.";
        }
        BigDecimal availableCredit = account.creditLimit
                .subtract(account.postedBalance)
                .subtract(reservedFunds(account.reference, null));
        if (amount.compareTo(availableCredit) > 0) {
            return "Insufficient available credit.";
        }
        return null;
    }

    private BigDecimal reservedFunds(String accountReference, String exceptTransactionReference) {
        return transactions.values().stream()
                .filter(transaction -> !transaction.reference.equals(exceptTransactionReference))
                .filter(transaction -> cards.get(transaction.cardReference).accountReference.equals(accountReference))
                .filter(transaction -> List.of("AUTHORIZED", "CLEARED", "SETTLED").contains(transaction.status))
                .map(transaction -> transaction.status.equals("AUTHORIZED")
                        ? transaction.authorizedAmount : transaction.amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean hasPendingFunds(String accountReference) {
        return reservedFunds(accountReference, null).compareTo(BigDecimal.ZERO) > 0;
    }

    private void validateLimit(Account account, BigDecimal limit) {
        if (limit.compareTo(account.creditLimit) > 0) {
            throw new IllegalArgumentException("Daily limit cannot exceed the account credit limit.");
        }
    }

    private Card newCard(Account account, Network network, BigDecimal dailyLimit) {
        Card card = new Card(reference("card"), account.reference, network, dailyLimit);
        cards.put(card.reference, card);
        account.cardReferences.add(card.reference);
        return card;
    }

    private AccountResponse accountResponse(Account account) {
        BigDecimal availableCredit = account.creditLimit
                .subtract(account.postedBalance)
                .subtract(reservedFunds(account.reference, null));
        return new AccountResponse(account.reference, account.customerReference, account.status,
                account.creditLimit, account.postedBalance, availableCredit, List.copyOf(account.cardReferences));
    }

    private CardResponse cardResponse(Card card) {
        return new CardResponse(card.reference, card.accountReference, card.network, card.status,
                card.tokenReference, card.expiresAt, card.dailyLimit, card.temporarilyLocked);
    }

    private TransactionResponse transactionResponse(SimulatedTransaction transaction) {
        return new TransactionResponse(transaction.reference, transaction.cardReference,
                transaction.merchantReference, transaction.amount, transaction.currency,
                transaction.status, transaction.decision, transaction.authorizationCode,
                transaction.message, transaction.createdAt);
    }

    private LedgerEventResponse addLedgerEntry(
            String accountReference,
            String transactionReference,
            String type,
            BigDecimal amount,
            String currency,
            String reason) {
        LedgerEventResponse entry = new LedgerEventResponse(reference("evt"), transactionReference,
                accountReference, type, amount, currency, reason, Instant.now());
        ledgerEntries.get(accountReference).add(entry);
        return entry;
    }

    private void transition(Card card, String requiredState, String nextState, String operation) {
        if (!card.status.equals(requiredState)) {
            throw conflict("Cannot " + operation + " a card in " + card.status + " state.");
        }
        card.status = nextState;
    }

    private void requireActive(Card card, String operation) {
        if (!card.status.equals("ACTIVE")) {
            throw conflict("Cannot " + operation + " a card in " + card.status + " state.");
        }
    }

    private void requireStatus(SimulatedTransaction transaction, String required, String operation) {
        if (!transaction.status.equals(required)) {
            throw conflict("Cannot " + operation + " a transaction in " + transaction.status + " state.");
        }
    }

    private Account requireOpenAccount(String reference) {
        Account account = requireAccount(reference);
        if (!account.status.equals("OPEN")) {
            throw conflict("Account is not open.");
        }
        return account;
    }

    private Account requireAccount(String reference) {
        Account account = accounts.get(reference);
        if (account == null) {
            throw notFound("Account", reference);
        }
        return account;
    }

    private Card requireCard(String reference) {
        Card card = cards.get(reference);
        if (card == null) {
            throw notFound("Card", reference);
        }
        return card;
    }

    private SimulatedTransaction requireTransaction(String reference) {
        SimulatedTransaction transaction = transactions.get(reference);
        if (transaction == null) {
            throw notFound("Transaction", reference);
        }
        return transaction;
    }

    private <T> T idempotent(
            String scope, String key, Object request, Supplier<T> action, Class<T> resultType) {
        IdempotencyKey registryKey = new IdempotencyKey(scope, key);
        IdempotentResult existing = idempotentResults.get(registryKey);
        if (existing != null) {
            if (!existing.request().equals(request)) {
                throw conflict("Idempotency key was already used with a different request.");
            }
            return resultType.cast(existing.result());
        }
        T result = action.get();
        idempotentResults.put(registryKey, new IdempotentResult(request, result));
        return result;
    }

    private String reference(String prefix) {
        return prefix + "_" + UUID.randomUUID();
    }

    private SimulationConflictException conflict(String message) {
        return new SimulationConflictException(message);
    }

    private ResponseStatusException notFound(String type, String reference) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, type + " not found: " + reference);
    }

    Map<String, ApplicationResponse> applicationsForPersistence() {
        return applications;
    }

    Map<String, Account> accountsForPersistence() {
        return accounts;
    }

    Map<String, Card> cardsForPersistence() {
        return cards;
    }

    Map<String, SimulatedTransaction> transactionsForPersistence() {
        return transactions;
    }

    Map<String, List<LedgerEventResponse>> ledgerEntriesForPersistence() {
        return ledgerEntries;
    }

    Map<IdempotencyKey, IdempotentResult> idempotencyResultsForPersistence() {
        return idempotentResults;
    }

    void restoreState(
            Map<String, ApplicationResponse> loadedApplications,
            Map<String, Account> loadedAccounts,
            Map<String, Card> loadedCards,
            Map<String, SimulatedTransaction> loadedTransactions,
            Map<String, List<LedgerEventResponse>> loadedLedgerEntries,
            Map<IdempotencyKey, IdempotentResult> loadedIdempotencyResults) {
        applications.clear();
        applications.putAll(loadedApplications);
        accounts.clear();
        accounts.putAll(loadedAccounts);
        cards.clear();
        cards.putAll(loadedCards);
        transactions.clear();
        transactions.putAll(loadedTransactions);
        ledgerEntries.clear();
        ledgerEntries.putAll(loadedLedgerEntries);
        idempotentResults.clear();
        idempotentResults.putAll(loadedIdempotencyResults);
    }

    static final class Account {
        final String reference;
        final String customerReference;
        final BigDecimal creditLimit;
        final List<String> cardReferences = new ArrayList<>();
        String status = "OPEN";
        BigDecimal postedBalance = BigDecimal.ZERO;

        Account(String reference, String customerReference, BigDecimal creditLimit) {
            this.reference = reference;
            this.customerReference = customerReference;
            this.creditLimit = creditLimit;
        }
    }

    static final class Card {
        final String reference;
        final String accountReference;
        final Network network;
        String tokenReference;
        YearMonth expiresAt;
        String status = "ISSUED";
        BigDecimal dailyLimit;
        boolean temporarilyLocked;

        Card(String reference, String accountReference, Network network, BigDecimal dailyLimit) {
            this.reference = reference;
            this.accountReference = accountReference;
            this.network = network;
            this.dailyLimit = dailyLimit;
            this.tokenReference = "mock-token-" + UUID.randomUUID();
            this.expiresAt = YearMonth.now().plusYears(4);
        }
    }

    static final class SimulatedTransaction {
        final String reference;
        final String cardReference;
        final String merchantReference;
        final String currency;
        Instant createdAt;
        BigDecimal amount;
        BigDecimal authorizedAmount;
        LocalDate authorizationDate;
        String status = "INITIATED";
        String decision = "PENDING";
        String authorizationCode;
        String message = "Awaiting authorization.";

        SimulatedTransaction(
                String reference, String cardReference, String merchantReference, BigDecimal amount, String currency) {
            this.reference = reference;
            this.cardReference = cardReference;
            this.merchantReference = merchantReference;
            this.currency = currency;
            this.amount = amount;
            this.createdAt = Instant.now();
        }
    }

    record IdempotencyKey(String scope, String key) {
    }

    record IdempotentResult(Object request, Object result) {
    }
}
