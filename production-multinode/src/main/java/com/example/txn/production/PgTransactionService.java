package com.example.txn.production;

import com.example.txn.Json;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL-backed service. All cross-instance correctness is enforced in the database. */
public final class PgTransactionService implements AutoCloseable {
    private final PooledConnections connections;
    private final int maxRetries;
    private final boolean allowFailureSimulation;

    public PgTransactionService(String jdbcUrl, String dbUser, String dbPassword, int maxRetries, int poolSize,
                                boolean allowFailureSimulation) {
        this.connections = new PooledConnections(Objects.requireNonNull(jdbcUrl), Objects.requireNonNull(dbUser), Objects.requireNonNull(dbPassword), poolSize);
        if (maxRetries < 1 || maxRetries > 20) {
            throw new IllegalArgumentException("maxRetries must be 1..20");
        }
        this.maxRetries = maxRetries;
        this.allowFailureSimulation = allowFailureSimulation;
    }

    private Connection connect() throws SQLException {
        return connections.getConnection();
    }
    @Override public void close() {
        connections.close();
    }

    public Map<String, Object> createAccount(String accountId, String currency) throws SQLException {
        if (accountId == null || !accountId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}")) throw new BadRequest("Invalid accountId");
        if (currency == null || !currency.matches("[A-Z]{3}")) throw new BadRequest("currency must be a 3-letter uppercase code");
        try {
            currencyScale(currency);
        }
        catch (IllegalArgumentException e) {
            throw new BadRequest("currency must be a recognized ISO 4217 code");
        }
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO accounts(account_id,currency) VALUES (?,?) ON CONFLICT (account_id) DO NOTHING")) {
                ps.setString(1, accountId);
                ps.setString(2, currency);
                int inserted = ps.executeUpdate();
                if (inserted == 0) {
                    try (PreparedStatement check = c.prepareStatement("SELECT currency FROM accounts WHERE account_id=? FOR UPDATE")) {
                        check.setString(1, accountId);
                        try (ResultSet rs = check.executeQuery()) {
                            if (!rs.next()) throw new SQLException("Account disappeared during provisioning");
                            if (!currency.equals(rs.getString(1).trim())) throw new Conflict("Account already exists with another currency");
                        }
                    }
                }
                audit(c, null, "ACCOUNT_PROVISIONED", "Account provisioned");
                c.commit();
                return Map.of("accountId", accountId, "currency", currency, "created", inserted == 1);
            }
            catch (Exception e) {
                rollback(c);
                throw translate(e);
            }
        }
    }

    public Map<String, Object> submit(Map<String, Object> body) throws SQLException {
        Request r = Request.from(body);
        String hash = hash(r.canonical());
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                // Check both unique identities under transaction; unique constraints settle races.
                Map<String,Object> prior = findExisting(c, "transaction_id", r.transactionId);
                if (prior == null) prior = findExisting(c, "idempotency_key", r.idempotencyKey);
                if (prior != null) {
                    // Identity and payload are separate checks: a key cannot be reassigned to another transaction,
                    // while a retry may legitimately carry a new requestId or idempotency key for the same txn ID.
                    if (!r.transactionId.equals(prior.get("transactionId")) || !hash.equals(prior.get("requestHash")))
                    throw new Conflict("Transaction/idempotency identity was reused with a conflicting payload");
                    audit(c, r.transactionId, "DUPLICATE_REQUEST", "Existing transaction returned; no second ledger effect");
                    c.commit();
                    Map<String,Object> duplicate = new LinkedHashMap<>(prior);
                    duplicate.remove("requestHash");
                    duplicate.put("duplicate", true);
                    return duplicate;
                }
                // Accounts must be provisioned through the controlled account workflow before ingestion.
                try (PreparedStatement account = c.prepareStatement("SELECT currency FROM accounts WHERE account_id=?")) {
                    account.setString(1, r.accountId);
                    try (ResultSet rs = account.executeQuery()) {
                        if (!rs.next()) throw new NotFound("Account is not provisioned");
                    }
                }
                if (r.simulatedFailures > 0 && !allowFailureSimulation)
                throw new BadRequest("Failure simulation is disabled");
                if ("REVERSAL".equals(r.type) && r.originalTransactionId == null)
                throw new BadRequest("originalTransactionId is required for REVERSAL");
                String sql = "INSERT INTO transactions(transaction_id,request_id,idempotency_key,request_hash,account_id,transaction_type,original_transaction_id,amount,currency,sequence_number,event_time,status,simulated_failures_remaining) VALUES (?,?,?,?,?,?,?,?,?,?,?,'RECEIVED',?)";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1,r.transactionId);
                    ps.setString(2,r.requestId);
                    ps.setString(3,r.idempotencyKey);
                    ps.setString(4,hash);
                    ps.setString(5,r.accountId);
                    ps.setString(6,r.type);
                    ps.setString(7,r.originalTransactionId);
                    ps.setBigDecimal(8,r.amount);
                    ps.setString(9,r.currency);
                    ps.setLong(10,r.sequence);
                    ps.setTimestamp(11,Timestamp.from(r.eventTime));
                    ps.setInt(12,r.simulatedFailures);
                    ps.executeUpdate();
                }
                audit(c, r.transactionId, "TRANSACTION_RECEIVED", "Accepted for asynchronous processing");
                c.commit();
                return Map.of("transactionId",r.transactionId,"status","RECEIVED","duplicate",false,"sequenceNumber",r.sequence);
            }
            catch (Exception e) {
                rollback(c);
                // Multiple nodes may race on a unique insert. Re-read the winning row and
                // return the same idempotent result rather than incorrectly returning a conflict.
                if (e instanceof SQLException se && "23505".equals(se.getSQLState())) {
                    Map<String,Object> winner = findExisting(c, "transaction_id", r.transactionId);
                    if (winner == null) winner = findExisting(c, "idempotency_key", r.idempotencyKey);
                    if (winner != null) {
                        if (!r.transactionId.equals(winner.get("transactionId")) || !hash.equals(winner.get("requestHash")))
                        throw new Conflict("Transaction/idempotency identity was reused with a conflicting payload");
                        audit(c, r.transactionId, "DUPLICATE_REQUEST", "Concurrent replay resolved to existing transaction");
                        c.commit();
                        Map<String,Object> duplicate = new LinkedHashMap<>(winner);
                        duplicate.remove("requestHash");
                        duplicate.put("duplicate", true);
                        return duplicate;
                    }
                }
                throw translate(e);
            }
        }
    }
    /** Claims one eligible job using SKIP LOCKED; different instances cannot claim the same row. */

    public boolean processOne() throws SQLException {
        String transactionId = null;
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                String claim = "SELECT t.transaction_id FROM transactions t JOIN accounts a ON a.account_id=t.account_id " +
                "WHERE t.next_attempt_at <= now() AND (t.status IN ('RECEIVED','RETRY_PENDING') OR (t.status='PENDING' AND t.sequence_number=a.last_consumed_sequence+1)) " +
                "ORDER BY t.created_at,t.transaction_id LIMIT 1 FOR UPDATE OF t SKIP LOCKED";
                try (PreparedStatement ps = c.prepareStatement(claim); ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        c.commit();
                        return false;
                    }
                    transactionId = rs.getString(1);
                }
                processLocked(c, transactionId);
                c.commit();
                return true;
            }
            catch (SQLException e) {
                rollback(c);
                if (isRetryable(e) && transactionId != null) recordRetry(transactionId, e.getSQLState());
                else if (transactionId != null && !isRetryable(e)) recordFailure(transactionId, "Processing error; inspect service logs (SQLState " + safeState(e.getSQLState()) + ")");
                if (!isRetryable(e)) throw e;
                return true;
            }
            catch (RuntimeException e) {
                rollback(c);
                throw e;
            }
        }
    }

    private void processLocked(Connection c, String id) throws SQLException {
        String sql = "SELECT transaction_id,account_id,transaction_type,original_transaction_id,amount,currency,sequence_number,retry_count,simulated_failures_remaining FROM transactions WHERE transaction_id=? FOR UPDATE";
        String accountId, type, currency, originalTransactionId;
        BigDecimal amount;
        long sequence;
        int retries, simulatedFailures;
        try (PreparedStatement ps=c.prepareStatement(sql)) {
            ps.setString(1,id);
            try(ResultSet rs=ps.executeQuery()) {
                if(!rs.next()) return;
                accountId=rs.getString("account_id");
                type=rs.getString("transaction_type");
                originalTransactionId=rs.getString("original_transaction_id");
                amount=rs.getBigDecimal("amount");
                currency=rs.getString("currency").trim();
                sequence=rs.getLong("sequence_number");
                retries=rs.getInt("retry_count");
                simulatedFailures=rs.getInt("simulated_failures_remaining");
            }
        }
        // Controlled fault injection is disabled by default and exists only to prove retry safety in test environments.
        if (simulatedFailures > 0) {
            int nextRetry = retries + 1;
            String nextStatus = nextRetry >= maxRetries ? "DEAD_LETTER" : "RETRY_PENDING";
            try (PreparedStatement ps = c.prepareStatement("UPDATE transactions SET simulated_failures_remaining=simulated_failures_remaining-1,retry_count=?,status=?,failure_reason='Simulated transient failure (test only)',next_attempt_at=now()+make_interval(secs => (LEAST(power(2,LEAST(?,8))::int,300)+floor(random()*4)::int)),updated_at=now() WHERE transaction_id=?")) {
                ps.setInt(1, nextRetry);
                ps.setString(2, nextStatus);
                ps.setInt(3, nextRetry);
                ps.setString(4, id);
                ps.executeUpdate();
            }
            audit(c, id, "SIMULATED_TRANSIENT_FAILURE", "Fault injection enabled; no ledger effect was applied");
            return;
        }
        try (PreparedStatement ps=c.prepareStatement("UPDATE transactions SET status='PROCESSING',failure_reason=NULL,updated_at=now() WHERE transaction_id=?")) {
            ps.setString(1,id);
            ps.executeUpdate();
        }
        String accountCurrency;
        BigDecimal balance;
        long lastSequence;
        // Lock account row before checking sequence or changing balance. This serializes updates for one account only.
        try (PreparedStatement ps=c.prepareStatement("SELECT currency,balance,last_consumed_sequence FROM accounts WHERE account_id=? FOR UPDATE")) {
            ps.setString(1,accountId);
            try(ResultSet rs=ps.executeQuery()) {
                if(!rs.next()) throw new SQLException("Account not provisioned");
                accountCurrency=rs.getString(1).trim();
                balance=rs.getBigDecimal(2);
                lastSequence=rs.getLong(3);
            }
        }
        long expected=lastSequence+1;
        if(sequence>expected) {
            setStatus(c,id,"PENDING","Waiting for sequence "+expected);
            audit(c,id,"TRANSACTION_PENDING","Waiting for account sequence "+expected);
            return;
        }
        if(sequence<expected) {
            setStatus(c,id,"FAILED","Stale sequence; expected "+expected);
            audit(c,id,"TRANSACTION_REJECTED","Stale sequence; expected "+expected);
            return;
        }
        // Once a sequence is present and evaluated, a permanent business rejection consumes that sequence.
        // This prevents one rejected debit from blocking every later event for the account indefinitely.
        if (!accountCurrency.equals(currency)) {
            rejectAndAdvance(c,id,accountId,sequence,"Currency mismatch for account");
            audit(c,id,"TRANSACTION_REJECTED","Currency mismatch");
            return;
        }
        BigDecimal signed;
        if ("REVERSAL".equals(type)) {
            // A reversal must reference an already posted CREDIT/DEBIT for the same account and currency.
            // This scoped implementation supports one full reversal per original transaction, never partial reversals.
            // The account row is already locked and the sequence cursor guarantees the prior event was consumed.
            // The original's PROCESSED status is terminal, so a second row lock here would only add deadlock risk.
            try (PreparedStatement ps = c.prepareStatement("SELECT transaction_type,amount,currency,account_id,status FROM transactions WHERE transaction_id=?")) {
                ps.setString(1, originalTransactionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !"PROCESSED".equals(rs.getString("status")) || "REVERSAL".equals(rs.getString("transaction_type"))
                    || !accountId.equals(rs.getString("account_id")) || !currency.equals(rs.getString("currency").trim())
                    || amount.compareTo(rs.getBigDecimal("amount")) != 0) {
                        rejectAndAdvance(c, id, accountId, sequence, "Reversal must match a processed original transaction, account, currency, and amount");
                        audit(c, id, "TRANSACTION_REJECTED", "Invalid reversal reference or amount");
                        return;
                    }
                    signed = "CREDIT".equals(rs.getString("transaction_type")) ? amount.negate() : amount;
                }
            }
        }
        else {
            signed = "DEBIT".equals(type) ? amount.negate() : amount;
        }
        BigDecimal updated=balance.add(signed);
        if(updated.signum()<0) {
            rejectAndAdvance(c,id,accountId,sequence,"Insufficient funds");
            audit(c,id,"TRANSACTION_REJECTED","Insufficient funds");
            return;
        }
        // A double-entry journal is immutable: customer and clearing lines balance exactly and commit atomically
        // with the materialized account balance, transaction status, and outbox event.
        BigDecimal zero = BigDecimal.ZERO.setScale(4);
        BigDecimal customerDebit = signed.signum() < 0 ? amount : zero;
        BigDecimal customerCredit = signed.signum() > 0 ? amount : zero;
        BigDecimal clearingDebit = signed.signum() > 0 ? amount : zero;
        BigDecimal clearingCredit = signed.signum() < 0 ? amount : zero;
        try(PreparedStatement ps=c.prepareStatement("INSERT INTO ledger_entries(transaction_id,line_number,account_id,account_role,debit_amount,credit_amount,currency) VALUES (?,?,?,?,?,?,?),(?,?,?,?,?,?,?)")) {
            ps.setString(1,id);
            ps.setInt(2,1);
            ps.setString(3,accountId);
            ps.setString(4,"CUSTOMER");
            ps.setBigDecimal(5,customerDebit);
            ps.setBigDecimal(6,customerCredit);
            ps.setString(7,currency);
            ps.setString(8,id);
            ps.setInt(9,2);
            ps.setString(10,"CLEARING:"+currency);
            ps.setString(11,"CLEARING");
            ps.setBigDecimal(12,clearingDebit);
            ps.setBigDecimal(13,clearingCredit);
            ps.setString(14,currency);
            ps.executeUpdate();
        }
        try(PreparedStatement ps=c.prepareStatement("UPDATE accounts SET balance=?,last_consumed_sequence=?,version=version+1,updated_at=now() WHERE account_id=?")) {
            ps.setBigDecimal(1,updated);
            ps.setLong(2,sequence);
            ps.setString(3,accountId);
            ps.executeUpdate();
        }
        try(PreparedStatement ps=c.prepareStatement("UPDATE transactions SET status='PROCESSED',failure_reason=NULL,updated_at=now() WHERE transaction_id=?")) {
            ps.setString(1,id);
            ps.executeUpdate();
        }
        String event = Json.stringify(Map.of("transactionId",id,"accountId",accountId,"transactionType",type,"amount",signed.toPlainString(),"currency",currency,"sequenceNumber",sequence));
        try(PreparedStatement ps=c.prepareStatement("INSERT INTO outbox_events(event_id,aggregate_id,event_type,payload) VALUES (?,?,'TransactionProcessed',?::jsonb)")) {
            ps.setObject(1,UUID.randomUUID());
            ps.setString(2,id);
            ps.setString(3,event);
            ps.executeUpdate();
        }
        audit(c,id,"TRANSACTION_PROCESSED","Ledger and balance committed atomically");
    }

    private Map<String,Object> findExisting(Connection c, String column, String value) throws SQLException {
        // Column is selected only from the two hard-coded names above (never from user input).
        String sql="SELECT transaction_id,request_hash,status,account_id,sequence_number FROM transactions WHERE "+column+"=?";
        try(PreparedStatement ps=c.prepareStatement(sql)){
            ps.setString(1,value);
            try(ResultSet rs=ps.executeQuery()){
                if(!rs.next())return null;
                Map<String,Object> m=new LinkedHashMap<>();
                m.put("transactionId",rs.getString(1));
                m.put("requestHash",rs.getString(2));
                m.put("status",rs.getString(3));
                m.put("accountId",rs.getString(4));
                m.put("sequenceNumber",rs.getLong(5));
                return m;
            }
        }
    }

    private void recordRetry(String id, String state) throws SQLException {
        String sql = "UPDATE transactions SET retry_count=retry_count+1," +
                "status=CASE WHEN retry_count+1 >= ? THEN 'DEAD_LETTER' ELSE 'RETRY_PENDING' END," +
                "failure_reason=CASE WHEN retry_count+1 >= ? THEN 'Retry limit exhausted' ELSE 'Transient database failure ('||?||')' END," +
                "next_attempt_at=CASE WHEN retry_count+1 >= ? THEN now() ELSE now()+make_interval(secs => " +
                "(LEAST(power(2,LEAST(retry_count+1,8))::int,300)+floor(random()*4)::int)) END,updated_at=now() " +
                "WHERE transaction_id=? AND status IN ('RECEIVED','RETRY_PENDING','PENDING') RETURNING status,retry_count";
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, maxRetries);
                ps.setInt(2, maxRetries);
                ps.setString(3, safeState(state));
                ps.setInt(4, maxRetries);
                ps.setString(5, id);
                String status = null;
                int attempt = 0;
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        status = rs.getString(1);
                        attempt = rs.getInt(2);
                    }
                }
                if (status != null) {
                    audit(c, id, "TRANSACTION_RETRY_SCHEDULED", "status=" + status + "; attempt=" + attempt);
                }
            }
            c.commit();
        } catch (SQLException e) {
            throw e;
        }
    }
    private void recordFailure(String id, String reason) throws SQLException {
        // Unexpected non-retryable infrastructure failures need operator review and must not masquerade as business rejection.
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE transactions SET status='DEAD_LETTER',failure_reason=?,updated_at=now() " +
                    "WHERE transaction_id=? AND status IN ('RECEIVED','RETRY_PENDING','PENDING') RETURNING status")) {
                ps.setString(1, reason);
                ps.setString(2, id);
                boolean updated;
                try (ResultSet rs = ps.executeQuery()) {
                    updated = rs.next();
                }
                if (updated) audit(c, id, "TRANSACTION_DEAD_LETTERED", "Non-retryable processing failure");
            }
            c.commit();
        }
    }
    private static void rejectAndAdvance(Connection c, String id, String accountId, long sequence, String reason) throws SQLException {
        setStatus(c, id, "FAILED", reason);
        try (PreparedStatement ps = c.prepareStatement("UPDATE accounts SET last_consumed_sequence=?,version=version+1,updated_at=now() WHERE account_id=?")) {
            ps.setLong(1, sequence);
            ps.setString(2, accountId);
            ps.executeUpdate();
        }
    }

    private static void setStatus(Connection c,String id,String status,String reason)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement("UPDATE transactions SET status=?,failure_reason=?,updated_at=now() WHERE transaction_id=?")){
            ps.setString(1,status);
            ps.setString(2,reason);
            ps.setString(3,id);
            ps.executeUpdate();
        }
    }

    private static void audit(Connection c,String id,String type,String detail)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement("INSERT INTO processing_audit(transaction_id,event_type,detail) VALUES (?,?,?)")){
            ps.setString(1,id);
            ps.setString(2,type);
            ps.setString(3,detail);
            ps.executeUpdate();
        }
    }
    /** Explicitly requeues dead-lettered work. This never changes a posted transaction or ledger entry. */

    public Map<String,Object> redrive(String transactionId) throws SQLException {
        if (transactionId == null || !transactionId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}"))
        throw new BadRequest("Invalid transactionId");
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
            "UPDATE transactions SET status='RETRY_PENDING',retry_count=0,failure_reason=NULL,next_attempt_at=now(),updated_at=now() " +
            "WHERE transaction_id=? AND status='DEAD_LETTER' RETURNING transaction_id,status")) {
                ps.setString(1, transactionId);
                String returnedId;
                String returnedStatus;
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new Conflict("Only DEAD_LETTER transactions can be redriven; verify the current status");
                    returnedId = rs.getString(1);
                    returnedStatus = rs.getString(2);
                }
                audit(c, transactionId, "TRANSACTION_REDRIVEN", "Operator initiated redrive");
                c.commit();
                return Map.of("transactionId", returnedId, "status", returnedStatus);
            }
            catch (Exception e) {
                rollback(c);
                throw translate(e);
            }
        }
    }

    public Map<String,Object> account(String accountId) throws SQLException {
        if (accountId == null || !accountId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}"))
        throw new BadRequest("Invalid accountId");
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
        "SELECT account_id,currency,balance,last_consumed_sequence,version,created_at,updated_at FROM accounts WHERE account_id=?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new NotFound("Account not found");
                Map<String,Object> result = new LinkedHashMap<>();
                result.put("accountId", rs.getString(1));
                result.put("currency", rs.getString(2).trim());
                result.put("balance", rs.getBigDecimal(3).toPlainString());
                result.put("lastConsumedSequence", rs.getLong(4));
                result.put("version", rs.getLong(5));
                result.put("createdAt", rs.getTimestamp(6).toInstant().toString());
                result.put("updatedAt", rs.getTimestamp(7).toInstant().toString());
                return result;
            }
        }
    }
    /** Ledger query returns journal lines, not a mutable balance-derived representation. */

    public List<Map<String,Object>> ledger(String transactionId) throws SQLException {
        if (transactionId == null || !transactionId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}"))
        throw new BadRequest("Invalid transactionId");
        List<Map<String,Object>> result = new ArrayList<>();
        try (Connection c = connect()) {
            try (PreparedStatement exists = c.prepareStatement("SELECT 1 FROM transactions WHERE transaction_id=?")) {
                exists.setString(1, transactionId);
                try (ResultSet rs = exists.executeQuery()) {
                    if (!rs.next()) throw new NotFound("Transaction not found");
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
            "SELECT line_number,account_id,account_role,debit_amount,credit_amount,currency,posted_at " +
            "FROM ledger_entries WHERE transaction_id=? ORDER BY line_number")) {
                ps.setString(1, transactionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        row.put("lineNumber", rs.getInt(1));
                        row.put("accountId", rs.getString(2));
                        row.put("accountRole", rs.getString(3));
                        row.put("debit", rs.getBigDecimal(4).toPlainString());
                        row.put("credit", rs.getBigDecimal(5).toPlainString());
                        row.put("currency", rs.getString(6).trim());
                        row.put("postedAt", rs.getTimestamp(7).toInstant().toString());
                        result.add(row);
                    }
                }
            }
        }
        return result;
    }

    public Map<String,Object> summary() throws SQLException {
        Map<String,Object> result=new LinkedHashMap<>();
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement("SELECT status,count(*) FROM transactions GROUP BY status");ResultSet rs=ps.executeQuery()) {
            while(rs.next()) result.put(rs.getString(1),rs.getLong(2));
        }
        for(String s:List.of("RECEIVED","PROCESSING","PROCESSED","PENDING","FAILED","RETRY_PENDING","DEAD_LETTER")) result.putIfAbsent(s,0L);
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement("SELECT count(*) FROM processing_audit WHERE event_type='DUPLICATE_REQUEST'");ResultSet rs=ps.executeQuery()){
            rs.next();
            result.put("DUPLICATE",rs.getLong(1));
        }
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement("SELECT count(DISTINCT transaction_id) FROM ledger_entries");ResultSet rs=ps.executeQuery()){
            rs.next();
            result.put("ledgerTransactions",rs.getLong(1));
        }
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement("SELECT count(*) FROM ledger_entries");ResultSet rs=ps.executeQuery()){
            rs.next();
            result.put("ledgerLines",rs.getLong(1));
        }
        return result;
    }

    public List<Map<String,Object>> transactions(int limit) throws SQLException {
        if(limit<1||limit>500)throw new BadRequest("limit must be between 1 and 500");
        List<Map<String,Object>> out=new ArrayList<>();
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement("SELECT transaction_id,account_id,transaction_type,original_transaction_id,amount,currency,sequence_number,status,retry_count,failure_reason,created_at FROM transactions ORDER BY created_at DESC LIMIT ?")){
            ps.setInt(1,limit);
            try(ResultSet rs=ps.executeQuery()){
                while(rs.next()){
                    Map<String,Object> m=new LinkedHashMap<>();
                    m.put("transactionId",rs.getString(1));
                    m.put("accountId",rs.getString(2));
                    m.put("type",rs.getString(3));
                    m.put("originalTransactionId",rs.getString(4));
                    m.put("amount",rs.getBigDecimal(5).toPlainString());
                    m.put("currency",rs.getString(6).trim());
                    m.put("sequenceNumber",rs.getLong(7));
                    m.put("status",rs.getString(8));
                    m.put("retryCount",rs.getInt(9));
                    m.put("failureReason",rs.getString(10));
                    m.put("createdAt",rs.getTimestamp(11).toInstant().toString());
                    out.add(m);
                }
            }
        }
        return out;
    }

    private static boolean isRetryable(SQLException e){
        String s=e.getSQLState();
        return e instanceof SQLTransientException || (s!=null&&(s.startsWith("08")||s.startsWith("40")||s.startsWith("53")||s.startsWith("57")));
    }

    private static String safeState(String s){
        return s==null?"unknown":s.replaceAll("[^A-Za-z0-9]","");
    }

    private static void rollback(Connection c){
        try{
            c.rollback();
        }
        catch(SQLException ignored){
            /* Preserve original failure. */
        }
    }

    private static RuntimeException translate(Exception e) {
        if (e instanceof RuntimeException runtime) return runtime;
        if (e instanceof SQLException sql) {
            if ("23505".equals(sql.getSQLState())) return new Conflict("Transaction ID, idempotency key, reversal, or account sequence already exists");
            // Keep database failures distinguishable so the HTTP adapter can return a retry-safe 503.
            return new PersistenceFailure("Persistence operation failed", sql);
        }
        return new IllegalStateException("Operation failed", e);
    }

    private static int currencyScale(String currencyCode) {
        Currency currency;
        try {
            currency = java.util.Currency.getInstance(currencyCode);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown ISO 4217 currency", e);
        }
        int scale = currency.getDefaultFractionDigits();
        if (scale < 0 || scale > 4) throw new IllegalArgumentException("Unsupported currency precision");
        return scale;
    }

    private static String hash(String text){
        try{
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch(NoSuchAlgorithmException e){
            throw new IllegalStateException(e);
        }
    }

    private record Request(String transactionId,String requestId,String idempotencyKey,String accountId,String type,String originalTransactionId,BigDecimal amount,String currency,long sequence,Instant eventTime,int simulatedFailures) {
        static Request from(Map<String,Object> m){
            String tid=str(m,"transactionId"),rid=str(m,"requestId"),key=str(m,"idempotencyKey"),aid=str(m,"accountId"),type=str(m,"transactionType"),currency=str(m,"currency");
            if(!tid.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}"))throw new BadRequest("Invalid transactionId");
            if(!rid.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"))throw new BadRequest("Invalid requestId");
            if(!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"))throw new BadRequest("Invalid idempotencyKey");
            if(!aid.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}"))throw new BadRequest("Invalid accountId");
            if(!List.of("CREDIT","DEBIT","REVERSAL").contains(type))throw new BadRequest("Unsupported transactionType");
            if(!currency.matches("[A-Z]{3}"))throw new BadRequest("currency must be a 3-letter uppercase code");
            int allowedScale;
            try {
                allowedScale=currencyScale(currency);
            }
            catch(IllegalArgumentException e) {
                throw new BadRequest("currency must be a recognized ISO 4217 code");
            }
            BigDecimal amount;
            try{
                amount=new BigDecimal(String.valueOf(m.get("amount")));
            }
            catch(Exception e){
                throw new BadRequest("amount must be numeric");
            }
            if(amount.scale()>4 || amount.precision()>19 || amount.precision()-amount.scale()>15 || amount.signum()<=0 || amount.stripTrailingZeros().scale()>allowedScale)throw new BadRequest("amount must be positive, fit NUMERIC(19,4), and respect the currency minor-unit precision");
            long seq;
            try{
                seq=new BigDecimal(String.valueOf(m.get("sequenceNumber"))).longValueExact();
            }
            catch(Exception e){
                throw new BadRequest("sequenceNumber must be an integer");
            }
            if(seq<=0)throw new BadRequest("sequenceNumber must be positive");
            Instant time;
            try{
                time=Instant.parse(str(m,"eventTime"));
            }
            catch(Exception e){
                throw new BadRequest("eventTime must be ISO-8601 UTC, e.g. 2026-01-01T00:00:00Z");
            }
            String original = m.get("originalTransactionId") == null ? null : str(m,"originalTransactionId");
            if ("REVERSAL".equals(type) && (original == null || !original.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}"))) throw new BadRequest("originalTransactionId is required for REVERSAL");
            if (!"REVERSAL".equals(type) && original != null) throw new BadRequest("originalTransactionId is allowed only for REVERSAL");
            int failures=0;
            if(m.containsKey("transientFailuresToSimulate")) {
                try {
                    failures=new BigDecimal(String.valueOf(m.get("transientFailuresToSimulate"))).intValueExact();
                }
                catch(Exception e) {
                    throw new BadRequest("transientFailuresToSimulate must be an integer");
                }
                if(failures<0||failures>10)throw new BadRequest("transientFailuresToSimulate must be 0..10");
            }
            return new Request(tid,rid,key,aid,type,original,amount,currency,seq,time,failures);
        }
        // Request IDs and idempotency keys identify delivery attempts, not the financial payload.
        String canonical(){
            return String.join("|",transactionId,accountId,type,Objects.toString(originalTransactionId,""),amount.stripTrailingZeros().toPlainString(),currency,Long.toString(sequence),eventTime.toString());
        }
        static String str(Map<String,Object> m,String k){
            Object o=m.get(k);
            if(!(o instanceof String s)||s.isBlank())throw new BadRequest(k+" is required and must be a string");
            return s;
        }
    }

    public static class BadRequest extends RuntimeException {
        private static final long serialVersionUID=1L;
        public BadRequest(String m){
            super(m);
        }
    }

    public static class Conflict extends RuntimeException {
        private static final long serialVersionUID=1L;
        public Conflict(String m){
            super(m);
        }
    }

    public static class NotFound extends RuntimeException {
        private static final long serialVersionUID=1L;
        public NotFound(String m){
            super(m);
        }
    }

    public static class PersistenceFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public PersistenceFailure(String message, SQLException cause) {
            super(message, cause);
        }
        public String sqlState() {
            return getCause() instanceof SQLException sql ? sql.getSQLState() : null;
        }
    }
}
