package com.example.txn;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Business rules are independent of HTTP so they can be tested without external libraries. */
public final class TransactionService {
    private static final int MAX_RETRIES = 3;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final StateStore store;
    private final boolean allowFaultInjection;

    public TransactionService(StateStore store, boolean allowFaultInjection) {
        this.store = store;
        this.allowFaultInjection = allowFaultInjection;
    }

    /** Validates and processes one transaction synchronously. */
    public synchronized Transaction submit(TransactionRequest request) throws IOException {
        validate(request);
        Optional<Transaction> byKey = store.byKey(request.idempotencyKey());
        if (byKey.isPresent()) {
            return duplicateOrConflict(byKey.get(), request, "Idempotency key");
        }

        Optional<Transaction> byId = store.byId(request.transactionId());
        if (byId.isPresent()) {
            return duplicateOrConflict(byId.get(), request, "Transaction ID");
        }

        rejectSequenceCollision(request);
        Account account = store.account(request.accountId())
                .orElse(new Account(request.accountId(), request.currency(), BigDecimal.ZERO, 0));

        if (!account.currency().equals(request.currency())) {
            // Preserve a rejected request for audit without changing the existing account.
            Transaction failed = toRecord(request, Transaction.Status.FAILED, 0,
                    "Currency mismatch for account", "REJECTED", 0);
            store.putTransaction(failed);
            return failed;
        }

        Transaction received = toRecord(request, Transaction.Status.RECEIVED, 0,
                null, "ACCEPTED", configuredFailures(request));
        store.putTransaction(received);

        long expected = account.lastProcessedSequence() + 1;
        if (request.sequenceNumber() > expected) {
            Transaction pending = received.withStatus(Transaction.Status.PENDING,
                    "Waiting for sequence " + expected, 0, received.simulatedFailuresRemaining());
            store.putTransaction(pending);
            return pending;
        }
        if (request.sequenceNumber() < expected) {
            Transaction stale = received.withStatus(Transaction.Status.FAILED,
                    "Stale sequence; next expected sequence is " + expected, 0, 0);
            store.putTransaction(stale);
            return stale;
        }

        Transaction result = process(received, account);
        drainPending(request.accountId());
        return result;
    }

    /** Durably accepts work; the background worker later processes the RECEIVED record. */
    public synchronized Transaction enqueue(TransactionRequest request) throws IOException {
        validate(request);
        Optional<Transaction> byKey = store.byKey(request.idempotencyKey());
        if (byKey.isPresent()) {
            return duplicateOrConflict(byKey.get(), request, "Idempotency key");
        }
        Optional<Transaction> byId = store.byId(request.transactionId());
        if (byId.isPresent()) {
            return duplicateOrConflict(byId.get(), request, "Transaction ID");
        }

        rejectSequenceCollision(request);
        Transaction received = toRecord(request, Transaction.Status.RECEIVED, 0,
                null, "ACCEPTED", configuredFailures(request));
        store.putTransaction(received);
        return received;
    }

    /** Called by the scheduled worker; PROCESSING is also recoverable after a process restart. */
    public synchronized Transaction processReceived(String transactionId) throws IOException {
        Transaction transaction = store.byId(transactionId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Transaction not found"));
        if (transaction.status() != Transaction.Status.RECEIVED
                && transaction.status() != Transaction.Status.PROCESSING) {
            return transaction;
        }

        Account account = store.account(transaction.accountId())
                .orElse(new Account(transaction.accountId(), transaction.currency(), BigDecimal.ZERO, 0));
        if (!account.currency().equals(transaction.currency())) {
            return persistStatus(transaction, Transaction.Status.FAILED, "Currency mismatch for account");
        }

        long expected = account.lastProcessedSequence() + 1;
        if (transaction.sequenceNumber() > expected) {
            return persistStatus(transaction, Transaction.Status.PENDING, "Waiting for sequence " + expected);
        }
        if (transaction.sequenceNumber() < expected) {
            return persistStatus(transaction, Transaction.Status.FAILED, "Stale sequence; next expected sequence is " + expected);
        }

        Transaction result = process(transaction, account);
        drainPending(transaction.accountId());
        return result;
    }

    /** Retries only retryable records; no injected failures are applied during explicit recovery. */
    public synchronized Transaction retry(String transactionId) throws IOException {
        Transaction transaction = store.byId(transactionId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Transaction not found"));
        boolean exhaustedTransientFailure = transaction.status() == Transaction.Status.FAILED
                && transaction.failureReason() != null
                && transaction.failureReason().startsWith("Retry limit exhausted");
        if (transaction.status() != Transaction.Status.RETRY_PENDING && !exhaustedTransientFailure) {
            throw new IllegalStateException("Only retryable transactions can be retried");
        }

        Account account = store.account(transaction.accountId())
                .orElse(new Account(transaction.accountId(), transaction.currency(), BigDecimal.ZERO, 0));
        long expected = account.lastProcessedSequence() + 1;
        if (transaction.sequenceNumber() != expected) {
            throw new IllegalStateException("Transaction cannot be retried until sequence " + expected + " is resolved");
        }

        // Preserve retryCount for operational reporting, but clear the injected failure count.
        Transaction reset = transaction.withRetry(transaction.retryCount(), 0,
                Transaction.Status.RECEIVED, null);
        store.putTransaction(reset);
        Transaction result = process(reset, account);
        if (result.status() == Transaction.Status.PROCESSED) {
            drainPending(transaction.accountId());
        }
        return result;
    }

    private int configuredFailures(TransactionRequest request) {
        return allowFaultInjection ? request.transientFailuresToSimulate() : 0;
    }

    private void rejectSequenceCollision(TransactionRequest request) {
        if (store.bySequence(request.accountId(), request.sequenceNumber()).isPresent()) {
            throw new ConflictException("Sequence number is already assigned to another transaction for this account");
        }
    }

    private Transaction process(Transaction original, Account account) throws IOException {
        Transaction transaction = original.withStatus(Transaction.Status.PROCESSING, null,
                original.retryCount(), original.simulatedFailuresRemaining());
        store.putTransaction(transaction);

        int remainingFailures = transaction.simulatedFailuresRemaining();
        int failuresAtStart = remainingFailures;
        int retries = transaction.retryCount();

        // Retry only the transient step. No balance or ledger mutation occurs until it succeeds.
        while (remainingFailures > 0 && retries < MAX_RETRIES) {
            remainingFailures--;
            retries++;
            transaction = transaction.withRetry(retries, remainingFailures,
                    Transaction.Status.RETRY_PENDING, "Transient processing failure");
            store.putTransaction(transaction);
        }

        boolean retryLimitReached = remainingFailures > 0
                || (retries >= MAX_RETRIES && failuresAtStart > 0);
        if (retryLimitReached) {
            Transaction failed = transaction.withStatus(Transaction.Status.FAILED,
                    "Retry limit exhausted after " + MAX_RETRIES + " attempts", retries, remainingFailures);
            store.putTransaction(failed);
            return failed;
        }

        // A pending event can be drained after an earlier sequence establishes account currency.
        if (!account.currency().equals(transaction.currency())) {
            Transaction failed = transaction.withStatus(Transaction.Status.FAILED,
                    "Currency mismatch for account", retries, 0);
            store.putTransaction(failed);
            return failed;
        }

        BigDecimal signedAmount = switch (transaction.type()) {
            case CREDIT, REVERSAL -> transaction.amount();
            case DEBIT -> transaction.amount().negate();
        };
        BigDecimal updatedBalance = account.balance().add(signedAmount);
        if (updatedBalance.compareTo(BigDecimal.ZERO) < 0) {
            Transaction failed = transaction.withStatus(Transaction.Status.FAILED,
                    "Insufficient funds", retries, 0);
            store.putTransaction(failed);
            return failed;
        }

        Account updatedAccount = new Account(account.accountId(), account.currency(),
                updatedBalance, transaction.sequenceNumber());
        Transaction processed = transaction.withStatus(Transaction.Status.PROCESSED, null, retries, 0)
                .withOutcome("PROCESSED");
        LedgerEntry entry = new LedgerEntry(transaction.transactionId(), transaction.accountId(),
                signedAmount, transaction.currency(), transaction.sequenceNumber(), Instant.now());

        // Balance, transaction state, and unique ledger entry share one atomic snapshot commit.
        store.commit(processed, updatedAccount, entry);
        return processed;
    }

    private void drainPending(String accountId) throws IOException {
        while (true) {
            Account account = store.account(accountId).orElse(null);
            if (account == null) {
                return;
            }
            long expected = account.lastProcessedSequence() + 1;
            Optional<Transaction> next = store.all().stream()
                    .filter(t -> t.accountId().equals(accountId)
                            && t.sequenceNumber() == expected
                            && t.status() == Transaction.Status.PENDING)
                    .findFirst();
            if (next.isEmpty()) {
                return;
            }

            Transaction result = process(next.get(), account);
            if (result.status() != Transaction.Status.PROCESSED) {
                return; // Do not silently skip a failed sequence and corrupt ordering.
            }
        }
    }

    private Transaction persistStatus(Transaction transaction, Transaction.Status status, String reason)
            throws IOException {
        Transaction updated = transaction.withStatus(status, reason, transaction.retryCount(),
                transaction.simulatedFailuresRemaining());
        store.putTransaction(updated);
        return updated;
    }

    private Transaction duplicateOrConflict(Transaction existing, TransactionRequest request, String identifier) throws IOException {
        if (!existing.transactionId().equals(request.transactionId())) {
            throw new ConflictException(identifier + " was reused with a different payload");
        }
        // Transaction ID is the unique identifier. Reusing the same transaction ID is a duplicate even if the amount or other metadata differs.
        store.recordDuplicateSubmission();
        return existing.withOutcome("DUPLICATE");
    }

    private Transaction toRecord(TransactionRequest request, Transaction.Status status, int retries,
                                 String failureReason, String outcome, int simulatedFailures) {
        return new Transaction(request.transactionId(), request.requestId(), request.idempotencyKey(),
                request.accountId(), request.type(), request.amount(), request.currency(),
                request.sequenceNumber(), request.eventTime(), status, retries, failureReason,
                outcome, simulatedFailures);
    }

    private void validate(TransactionRequest request) {
        if (request == null) {
            throw new ValidationException("Request is required");
        }
        checkId(request.transactionId(), "transactionId");
        checkId(request.requestId(), "requestId");
        checkId(request.idempotencyKey(), "idempotencyKey");
        checkId(request.accountId(), "accountId");
        if (request.type() == null) {
            throw new ValidationException("transactionType is required");
        }
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2
                || amount.precision() - amount.scale() > 14) {
            throw new ValidationException("amount must be positive with at most 2 decimal places and 14 integer digits");
        }
        if (request.currency() == null || !CURRENCY.matcher(request.currency()).matches()) {
            throw new ValidationException("currency must be a 3-letter uppercase ISO-style code");
        }
        if (request.sequenceNumber() <= 0) {
            throw new ValidationException("sequenceNumber must be positive");
        }
        if (request.eventTime() == null) {
            throw new ValidationException("eventTime is required");
        }
        if (request.transientFailuresToSimulate() < 0 || request.transientFailuresToSimulate() > 10) {
            throw new ValidationException("transientFailuresToSimulate must be between 0 and 10");
        }
    }

    private void checkId(String value, String field) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new ValidationException(field + " is required and must be 1-80 safe characters");
        }
    }

    public List<Transaction> all() {
        return store.all();
    }

    public Map<String, Long> summary() {
        return store.summary();
    }

    public List<LedgerEntry> ledgerEntries() {
        return store.ledgerEntries();
    }

    public Optional<Account> balance(String accountId) {
        return store.account(accountId);
    }
}
