package com.stardataparadise.sdk;

import java.sql.SQLException;

/** A Server or transport error with an optional durable-outcome lookup token. */
public final class SdpException extends SQLException {
    private final boolean unknownOutcome;
    private final boolean retryable;
    private final String requestToken;
    private final String correlationId;
    private final int line;
    private final int column;

    SdpException(String reason, String state, int code, boolean unknownOutcome, boolean retryable,
                 String requestToken, String correlationId, int line, int column) {
        super(reason, state, code);
        this.unknownOutcome = unknownOutcome;
        this.retryable = retryable && !unknownOutcome;
        this.requestToken = requestToken;
        this.correlationId = correlationId;
        this.line = line;
        this.column = column;
    }

    public boolean isOutcomeUnknown() { return unknownOutcome; }
    public boolean isRetryable() { return retryable; }
    public String requestToken() { return requestToken; }
    public String correlationId() { return correlationId; }
    public int line() { return line; }
    public int column() { return column; }
}
