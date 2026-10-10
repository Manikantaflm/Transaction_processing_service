package com.example.txn;
import java.math.BigDecimal;
/** Account balance is maintained with exact decimal arithmetic; no floating point is used. */
public record Account(String accountId, String currency, BigDecimal balance, long lastProcessedSequence) { }
