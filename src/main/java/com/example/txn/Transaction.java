package com.example.txn;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable business payload plus service-managed processing state. */
public record Transaction(String transactionId, String requestId, String idempotencyKey,
                          String accountId, Type type, BigDecimal amount, String currency,
                          long sequenceNumber, Instant eventTime, Status status,
                          int retryCount, String failureReason, String outcome,
                          int simulatedFailuresRemaining) {
    public enum Type { CREDIT, DEBIT, REVERSAL }
    public enum Status { RECEIVED, PROCESSING, PROCESSED, DUPLICATE, PENDING, FAILED, RETRY_PENDING }

    public Transaction withStatus(Status s, String reason, int retries, int remaining) {
        return new Transaction(transactionId, requestId, idempotencyKey, accountId, type, amount,
                currency, sequenceNumber, eventTime, s, retries, reason, outcome, remaining);
    }
    public Transaction withOutcome(String value) {
        return new Transaction(transactionId, requestId, idempotencyKey, accountId, type, amount,
                currency, sequenceNumber, eventTime, status, retryCount, failureReason, value,
                simulatedFailuresRemaining);
    }
    public Transaction withRetry(int retries, int remaining, Status s, String reason) {
        return new Transaction(transactionId, requestId, idempotencyKey, accountId, type, amount,
                currency, sequenceNumber, eventTime, s, retries, reason, outcome, remaining);
    }
}
