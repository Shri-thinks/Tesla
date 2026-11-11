package com.tesla.simulation;

import com.tesla.card.Network;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

public final class CardSimulationModels {

    private CardSimulationModels() {
    }

    public record ApplicationRequest(
            @NotBlank String customerReference,
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal requestedCreditLimit,
            @NotBlank String idempotencyKey) {
    }

    public record ApplicationResponse(
            String applicationReference,
            String customerReference,
            String status,
            String accountReference,
            BigDecimal approvedCreditLimit,
            String message) {
    }

    public record CardIssueRequest(
            @NotNull Network network,
            @NotBlank String idempotencyKey,
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal dailyLimit) {
    }

    public record CardResponse(
            String cardReference,
            String accountReference,
            Network network,
            String status,
            String tokenReference,
            YearMonth expiresAt,
            BigDecimal dailyLimit,
            boolean temporarilyLocked) {
    }

    public record CardLimitRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal dailyLimit) {
    }

    public record PinChangeRequest(@NotBlank String securePinChangeReference) {
    }

    public record PinChangeResponse(String cardReference, String status, String message) {
    }

    public record AccountResponse(
            String accountReference,
            String customerReference,
            String status,
            BigDecimal creditLimit,
            BigDecimal postedBalance,
            BigDecimal availableCredit,
            List<String> cardReferences) {
    }

    public record TransactionInitiationRequest(
            @NotBlank String cardReference,
            @NotBlank String merchantReference,
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotBlank String idempotencyKey) {
    }

    public record TransactionResponse(
            String transactionReference,
            String cardReference,
            String merchantReference,
            BigDecimal amount,
            String currency,
            String status,
            String decision,
            String authorizationCode,
            String message,
            Instant createdAt) {
    }

    public record ClearingRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount) {
    }

    public record FinancialEventRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount,
            @NotBlank String reason,
            @NotBlank String idempotencyKey) {
    }

    public record DisputeRequest(@NotBlank String reason, @NotBlank String idempotencyKey) {
    }

    public record LedgerEventResponse(
            String eventReference,
            String transactionReference,
            String accountReference,
            String type,
            BigDecimal amount,
            String currency,
            String reason,
            Instant createdAt) {
    }

    public record ReconciliationResponse(
            String accountReference,
            BigDecimal postedBalance,
            BigDecimal ledgerBalance,
            BigDecimal difference,
            boolean reconciled,
            List<LedgerEventResponse> ledgerEntries) {
    }
}
