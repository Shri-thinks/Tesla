package com.tesla.card;

public record CardLifecycleResponse(
        Network network,
        String externalCardReference,
        CardOperation operation,
        String status,
        String message) {
}
