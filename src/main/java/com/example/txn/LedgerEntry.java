package com.example.txn;
import java.math.BigDecimal;
import java.time.Instant;
/** Immutable audit row created exactly once with the successful balance update. */
public record LedgerEntry(String transactionId, String accountId, BigDecimal signedAmount,
                          String currency, long sequenceNumber, Instant processedAt) { }
