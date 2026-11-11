package com.tesla.card;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CardLifecycleRequest(
        @NotNull Network network,
        @NotBlank String externalCardReference,
        @NotBlank String customerReference,
        @NotBlank String idempotencyKey) {
}
