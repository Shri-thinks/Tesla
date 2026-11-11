package com.tesla.card;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

public class MockCardNetworkClient implements CardNetworkClient {

    private final ConcurrentMap<String, String> cardStates = new ConcurrentHashMap<>();

    @Override
    public CardLifecycleResponse execute(CardOperation operation, CardLifecycleRequest request) {
        String key = request.network() + ":" + request.externalCardReference();
        AtomicReference<String> resultingState = new AtomicReference<>();
        cardStates.compute(key, (ignored, currentState) -> {
            String next = nextState(operation, currentState, request.externalCardReference());
            resultingState.set(next);
            return next;
        });

        return new CardLifecycleResponse(
                request.network(),
                request.externalCardReference(),
                operation,
                resultingState.get(),
                "Mock " + request.network() + " adapter completed " + operation.name().toLowerCase() + ".");
    }

    private String nextState(CardOperation operation, String currentState, String cardReference) {
        if (operation == CardOperation.ISSUE && currentState == null) {
            return "ISSUED";
        }

        if (currentState == null) {
            throw new InvalidCardTransitionException("Card " + cardReference + " has not been issued.");
        }

        return switch (operation) {
            case ISSUE -> throw invalid(operation, currentState);
            case ACTIVATE -> transition(operation, currentState, "ISSUED", "ACTIVE");
            case SUSPEND -> transition(operation, currentState, "ACTIVE", "SUSPENDED");
            case UNBLOCK -> transition(operation, currentState, "SUSPENDED", "ACTIVE");
            case REPLACE -> {
                if (!currentState.equals("ACTIVE") && !currentState.equals("SUSPENDED")) {
                    throw invalid(operation, currentState);
                }
                yield "REPLACED";
            }
            case CLOSE -> {
                if (currentState.equals("CLOSED") || currentState.equals("REPLACED")) {
                    throw invalid(operation, currentState);
                }
                yield "CLOSED";
            }
        };
    }

    private String transition(CardOperation operation, String currentState, String requiredState, String nextState) {
        if (!currentState.equals(requiredState)) {
            throw invalid(operation, currentState);
        }
        return nextState;
    }

    private InvalidCardTransitionException invalid(CardOperation operation, String currentState) {
        return new InvalidCardTransitionException(
                "Cannot " + operation.name().toLowerCase() + " a card in " + currentState + " state.");
    }
}
