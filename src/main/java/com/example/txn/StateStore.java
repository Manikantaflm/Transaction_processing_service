package com.example.txn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.Base64;

/**
 * Small dependency-free durable store for local assessment use. Each mutation writes a complete
 * snapshot to a temporary file and atomically renames it, preventing half-written snapshots.
 * A process lock serializes operations. For multi-instance production use, replace with a DB and
 * row-level locking; this file store intentionally does not claim distributed coordination.
 */
public final class StateStore {
    private final Path file;
    private final Map<String, Transaction> transactions = new LinkedHashMap<>();
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, LedgerEntry> ledger = new LinkedHashMap<>();
    private long duplicateSubmissionCount;

    public StateStore(Path file) throws IOException { this.file = file; load(); }
    public synchronized Optional<Transaction> byId(String id) { return Optional.ofNullable(transactions.get(id)); }
    public synchronized Optional<Transaction> byKey(String key) {
        return transactions.values().stream().filter(t -> t.idempotencyKey().equals(key)).findFirst();
    }
    public synchronized Optional<Transaction> bySequence(String account, long seq) {
        return transactions.values().stream().filter(t -> t.accountId().equals(account) && t.sequenceNumber() == seq).findFirst();
    }
    public synchronized List<Transaction> all() { return List.copyOf(transactions.values()); }
    public synchronized Optional<Account> account(String id) { return Optional.ofNullable(accounts.get(id)); }
    public synchronized List<LedgerEntry> ledgerEntries() { return List.copyOf(ledger.values()); }
    public synchronized void putTransaction(Transaction t) throws IOException {
        Transaction old = transactions.put(t.transactionId(), t);
        try { persist(); } catch (IOException ex) { if (old == null) transactions.remove(t.transactionId()); else transactions.put(t.transactionId(), old); throw ex; }
    }
    public synchronized void recordDuplicateSubmission() throws IOException {
        duplicateSubmissionCount++;
        try { persist(); } catch (IOException exception) { duplicateSubmissionCount--; throw exception; }
    }
    public synchronized void putAccount(Account a) throws IOException {
        Account old = accounts.put(a.accountId(), a);
        try { persist(); } catch (IOException ex) { if (old == null) accounts.remove(a.accountId()); else accounts.put(a.accountId(), old); throw ex; }
    }
    /** Atomically persist the business state and transaction state in one snapshot replacement. */
    public synchronized void commit(Transaction t, Account a, LedgerEntry entry) throws IOException {
        if (ledger.containsKey(entry.transactionId())) throw new IOException("Ledger entry already exists for transaction");
        Transaction oldT = transactions.put(t.transactionId(), t);
        Account oldA = accounts.put(a.accountId(), a);
        LedgerEntry oldL = ledger.put(entry.transactionId(), entry);
        try { persist(); } catch (IOException ex) {
            if (oldT == null) transactions.remove(t.transactionId()); else transactions.put(t.transactionId(), oldT);
            if (oldA == null) accounts.remove(a.accountId()); else accounts.put(a.accountId(), oldA);
            if (oldL == null) ledger.remove(entry.transactionId()); else ledger.put(entry.transactionId(), oldL);
            throw ex;
        }
    }
    public synchronized Map<String, Long> summary() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Transaction.Status s : Transaction.Status.values()) result.put(s.name(), 0L);
        for (Transaction t : transactions.values()) result.compute(t.status().name(), (k,v) -> v + 1L);
        // DUPLICATE counts replay attempts, not duplicate transaction rows, to keep the ledger immutable.
        result.put(Transaction.Status.DUPLICATE.name(), duplicateSubmissionCount);
        return result;
    }

    private void load() throws IOException {
        if (!Files.exists(file)) return;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\\t", -1);
            try {
                if (p[0].equals("T") && p.length == 15) {
                    Transaction t = new Transaction(d(p[1]),d(p[2]),d(p[3]),d(p[4]),Transaction.Type.valueOf(p[5]),
                        new BigDecimal(p[6]),d(p[7]),Long.parseLong(p[8]),Instant.parse(p[9]),
                        Transaction.Status.valueOf(p[10]),Integer.parseInt(p[11]),nullable(p[12]),nullable(p[13]),Integer.parseInt(p[14]));
                    if (transactions.putIfAbsent(t.transactionId(),t) != null) throw new IOException("Duplicate transaction ID in state snapshot");
                } else if (p[0].equals("A") && p.length == 5) {
                    Account a = new Account(d(p[1]),d(p[2]),new BigDecimal(p[3]),Long.parseLong(p[4])); if (accounts.putIfAbsent(a.accountId(),a) != null) throw new IOException("Duplicate account in state snapshot");
                } else if (p[0].equals("M") && p.length == 2) {
                    long count = Long.parseLong(p[1]);
                    if (count < 0) throw new IOException("Negative duplicate counter in state snapshot");
                    duplicateSubmissionCount = count;
                } else if (p[0].equals("L") && p.length == 7) {
                    LedgerEntry l = new LedgerEntry(d(p[1]),d(p[2]),new BigDecimal(p[3]),d(p[4]),Long.parseLong(p[5]),Instant.parse(p[6]));
                    if (ledger.putIfAbsent(l.transactionId(), l) != null) throw new IOException("Duplicate ledger transaction in snapshot");
                } else throw new IOException("Corrupt state snapshot row");
            } catch (RuntimeException ex) { throw new IOException("Invalid state snapshot; refusing unsafe startup", ex); }
        }
        validateSnapshot();
    }
    private void validateSnapshot() throws IOException {
        Set<String> keys = new HashSet<>();
        Set<String> sequences = new HashSet<>();
        for (Transaction t : transactions.values()) {
            if (!keys.add(t.idempotencyKey())) throw new IOException("Duplicate idempotency key in state snapshot");
            if (!sequences.add(t.accountId() + "\u0000" + t.sequenceNumber())) throw new IOException("Duplicate account sequence in state snapshot");
            LedgerEntry entry = ledger.get(t.transactionId());
            if (t.status() == Transaction.Status.PROCESSED && entry == null) throw new IOException("Processed transaction has no ledger entry");
            if (entry != null && t.status() != Transaction.Status.PROCESSED) throw new IOException("Ledger entry references a non-processed transaction");
        }
        for (LedgerEntry entry : ledger.values()) {
            Transaction t = transactions.get(entry.transactionId());
            if (t == null || !t.accountId().equals(entry.accountId()) || !t.currency().equals(entry.currency()) || t.sequenceNumber() != entry.sequenceNumber()) throw new IOException("Ledger entry does not match its transaction");
        }
        for (Account a : accounts.values()) {
            BigDecimal sum = ledger.values().stream().filter(l -> l.accountId().equals(a.accountId())).map(LedgerEntry::signedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            long maxSequence = ledger.values().stream().filter(l -> l.accountId().equals(a.accountId())).mapToLong(LedgerEntry::sequenceNumber).max().orElse(0L);
            if (sum.compareTo(a.balance()) != 0 || maxSequence != a.lastProcessedSequence()) throw new IOException("Account balance/sequence does not reconcile to ledger");
        }
    }
    private void persist() throws IOException {
        Path parent = file.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        List<String> rows = new ArrayList<>(); rows.add("# transaction-service-state-v1");
        rows.add("M\t" + duplicateSubmissionCount);
        for (Transaction t : transactions.values()) rows.add(String.join("\t", "T",e(t.transactionId()),e(t.requestId()),e(t.idempotencyKey()),e(t.accountId()),t.type().name(),t.amount().toPlainString(),e(t.currency()),Long.toString(t.sequenceNumber()),t.eventTime().toString(),t.status().name(),Integer.toString(t.retryCount()),eNull(t.failureReason()),eNull(t.outcome()),Integer.toString(t.simulatedFailuresRemaining())));
        for (Account a : accounts.values()) rows.add(String.join("\t", "A",e(a.accountId()),e(a.currency()),a.balance().toPlainString(),Long.toString(a.lastProcessedSequence())));
        for (LedgerEntry l : ledger.values()) rows.add(String.join("\t", "L",e(l.transactionId()),e(l.accountId()),l.signedAmount().toPlainString(),e(l.currency()),Long.toString(l.sequenceNumber()),l.processedAt().toString()));
        Files.write(temp, rows, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static String e(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static String eNull(String s) { return s == null ? "~" : e(s); }
    private static String d(String s) { return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8); }
    private static String nullable(String s) { return s.equals("~") ? null : d(s); }
}
