package com.tesla.simulation;

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
import com.tesla.simulation.CardSimulationModels.PinChangeRequest;
import com.tesla.simulation.CardSimulationModels.PinChangeResponse;
import com.tesla.simulation.CardSimulationModels.ReconciliationResponse;
import com.tesla.simulation.CardSimulationModels.TransactionInitiationRequest;
import com.tesla.simulation.CardSimulationModels.TransactionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CardSimulationController {

    private final CardSimulationService simulationService;

    public CardSimulationController(CardSimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/api/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationResponse apply(@Valid @RequestBody ApplicationRequest request) {
        return simulationService.apply(request);
    }

    @GetMapping("/api/accounts/{accountReference}")
    public AccountResponse account(@PathVariable String accountReference) {
        return simulationService.account(accountReference);
    }

    @PostMapping("/api/accounts/{accountReference}/close")
    public AccountResponse closeAccount(@PathVariable String accountReference) {
        return simulationService.closeAccount(accountReference);
    }

    @PostMapping("/api/accounts/{accountReference}/cards")
    @ResponseStatus(HttpStatus.CREATED)
    public CardResponse issueCard(
            @PathVariable String accountReference,
            @Valid @RequestBody CardIssueRequest request) {
        return simulationService.issueCard(accountReference, request);
    }

    @GetMapping("/api/cards/{cardReference}")
    public CardResponse card(@PathVariable String cardReference) {
        return simulationService.card(cardReference);
    }

    @PostMapping("/api/cards/{cardReference}/activate")
    public CardResponse activate(@PathVariable String cardReference) {
        return simulationService.cardOperation(cardReference, "activate");
    }

    @PostMapping("/api/cards/{cardReference}/suspend")
    public CardResponse suspend(@PathVariable String cardReference) {
        return simulationService.cardOperation(cardReference, "suspend");
    }

    @PostMapping("/api/cards/{cardReference}/unblock")
    public CardResponse unblock(@PathVariable String cardReference) {
        return simulationService.cardOperation(cardReference, "unblock");
    }

    @PostMapping("/api/cards/{cardReference}/block")
    public CardResponse block(@PathVariable String cardReference) {
        return simulationService.cardOperation(cardReference, "block");
    }

    @PostMapping("/api/cards/{cardReference}/close")
    public CardResponse closeCard(@PathVariable String cardReference) {
        return simulationService.cardOperation(cardReference, "close");
    }

    @PostMapping("/api/cards/{cardReference}/lock")
    public CardResponse lock(@PathVariable String cardReference) {
        return simulationService.cardOperation(cardReference, "lock");
    }

    @PostMapping("/api/cards/{cardReference}/unlock")
    public CardResponse unlock(@PathVariable String cardReference) {
        return simulationService.cardOperation(cardReference, "unlock");
    }

    @PostMapping("/api/cards/{cardReference}/replace")
    public CardResponse replace(
            @PathVariable String cardReference,
            @Valid @RequestBody CardIssueRequest request) {
        return simulationService.replaceCard(cardReference, request);
    }

    @PutMapping("/api/cards/{cardReference}/limits")
    public CardResponse updateLimit(
            @PathVariable String cardReference,
            @Valid @RequestBody CardLimitRequest request) {
        return simulationService.updateLimit(cardReference, request);
    }

    @PostMapping("/api/cards/{cardReference}/pin-change")
    public PinChangeResponse changePin(
            @PathVariable String cardReference,
            @Valid @RequestBody PinChangeRequest request) {
        return simulationService.changePin(cardReference, request.securePinChangeReference());
    }

    @PostMapping("/api/transactions/initiate")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse initiate(@Valid @RequestBody TransactionInitiationRequest request) {
        return simulationService.initiate(request);
    }

    @GetMapping("/api/transactions/{transactionReference}")
    public TransactionResponse transaction(@PathVariable String transactionReference) {
        return simulationService.transaction(transactionReference);
    }

    @PostMapping("/api/transactions/{transactionReference}/authorize")
    public TransactionResponse authorize(@PathVariable String transactionReference) {
        return simulationService.authorize(transactionReference);
    }

    @PostMapping("/api/transactions/{transactionReference}/reverse")
    public TransactionResponse reverse(@PathVariable String transactionReference) {
        return simulationService.reverse(transactionReference);
    }

    @PostMapping("/api/transactions/{transactionReference}/clear")
    public TransactionResponse clear(
            @PathVariable String transactionReference,
            @Valid @RequestBody ClearingRequest request) {
        return simulationService.clear(transactionReference, request);
    }

    @PostMapping("/api/transactions/{transactionReference}/settle")
    public TransactionResponse settle(@PathVariable String transactionReference) {
        return simulationService.settle(transactionReference);
    }

    @PostMapping("/api/transactions/{transactionReference}/post")
    public TransactionResponse post(@PathVariable String transactionReference) {
        return simulationService.post(transactionReference);
    }

    @PostMapping("/api/transactions/{transactionReference}/refund")
    public LedgerEventResponse refund(
            @PathVariable String transactionReference,
            @Valid @RequestBody FinancialEventRequest request) {
        return simulationService.refund(transactionReference, request);
    }

    @PostMapping("/api/transactions/{transactionReference}/chargeback")
    public LedgerEventResponse chargeback(
            @PathVariable String transactionReference,
            @Valid @RequestBody FinancialEventRequest request) {
        return simulationService.chargeback(transactionReference, request);
    }

    @PostMapping("/api/transactions/{transactionReference}/disputes")
    public LedgerEventResponse dispute(
            @PathVariable String transactionReference,
            @Valid @RequestBody DisputeRequest request) {
        return simulationService.dispute(transactionReference, request);
    }

    @PostMapping("/api/transactions/{transactionReference}/fees")
    public LedgerEventResponse fee(
            @PathVariable String transactionReference,
            @Valid @RequestBody FinancialEventRequest request) {
        return simulationService.fee(transactionReference, request);
    }

    @PostMapping("/api/transactions/{transactionReference}/adjustments")
    public LedgerEventResponse adjustment(
            @PathVariable String transactionReference,
            @Valid @RequestBody FinancialEventRequest request) {
        return simulationService.adjustment(transactionReference, request);
    }

    @GetMapping("/api/accounts/{accountReference}/transactions")
    public List<TransactionResponse> accountTransactions(@PathVariable String accountReference) {
        return simulationService.accountTransactions(accountReference);
    }

    @GetMapping("/api/accounts/{accountReference}/reconciliation")
    public ReconciliationResponse reconcile(@PathVariable String accountReference) {
        return simulationService.reconcile(accountReference);
    }
}
