package com.tesla.card;

import java.util.Locale;

public enum CardOperation {
    ISSUE,
    ACTIVATE,
    SUSPEND,
    UNBLOCK,
    REPLACE,
    CLOSE;

    public static CardOperation fromPath(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported card operation: " + value);
        }
    }
}
