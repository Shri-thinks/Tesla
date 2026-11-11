package com.tesla.card;

public class InvalidCardTransitionException extends RuntimeException {

    public InvalidCardTransitionException(String message) {
        super(message);
    }
}
