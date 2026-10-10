package com.example.txn;
import java.math.BigDecimal;
import java.time.Instant;
/** External request model. Fault injection is explicitly opt-in for local assessment use only. */
public record TransactionRequest(String transactionId, String requestId, String idempotencyKey,
        String accountId, Transaction.Type type, BigDecimal amount, String currency,
        long sequenceNumber, Instant eventTime, int transientFailuresToSimulate) { }
