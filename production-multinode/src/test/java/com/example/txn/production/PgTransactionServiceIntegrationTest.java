package com.example.txn.production;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** These tests use a real PostgreSQL instance because H2 cannot reproduce PostgreSQL locking/constraint behavior. */
@Testcontainers(disabledWithoutDocker = true)
class PgTransactionServiceIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transactions")
            .withUsername("transaction_test")
            .withPassword("transaction_test_password");

    private PgTransactionService service;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE accounts CASCADE");
            statement.execute("TRUNCATE TABLE processing_audit RESTART IDENTITY CASCADE");
            statement.execute("TRUNCATE TABLE outbox_events CASCADE");
        }
        service = new PgTransactionService(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), 3, 12, true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (service != null) service.close();
    }

    @Test
    void duplicateReplayCreatesOnlyOneFinancialPosting() throws Exception {
        service.createAccount("ACC-1", "USD");
        Map<String, Object> request = request("TXN-1", "KEY-1", "ACC-1", "CREDIT", "100.00", 1);
        service.submit(request);
        Map<String, Object> replay = service.submit(new LinkedHashMap<>(request));
        assertEquals(Boolean.TRUE, replay.get("duplicate"));
        assertTrue(service.processOne());
        assertEquals("100.0000", service.account("ACC-1").get("balance"));
        assertEquals(2, service.ledger("TXN-1").size());
    }

    @Test
    void sameIdempotencyKeyWithDifferentTransactionIsConflict() throws Exception {
        service.createAccount("ACC-1", "USD");
        service.submit(request("TXN-1", "KEY-SHARED", "ACC-1", "CREDIT", "100.00", 1));
        Map<String, Object> conflicting = request("TXN-2", "KEY-SHARED", "ACC-1", "CREDIT", "100.00", 2);
        assertThrows(PgTransactionService.Conflict.class, () -> service.submit(conflicting));
    }

    @Test
    void outOfOrderEventsWaitForMissingSequenceAndThenDrain() throws Exception {
        service.createAccount("ACC-1", "USD");
        service.submit(request("TXN-2", "KEY-2", "ACC-1", "DEBIT", "20.00", 2));
        assertTrue(service.processOne()); // sequence 2 is durably marked PENDING
        assertEquals("PENDING", transaction("TXN-2").get("status"));
        service.submit(request("TXN-1", "KEY-1", "ACC-1", "CREDIT", "100.00", 1));
        assertTrue(service.processOne());
        assertTrue(service.processOne());
        assertEquals("80.0000", service.account("ACC-1").get("balance"));
        assertEquals("PROCESSED", transaction("TXN-2").get("status"));
    }

    @Test
    void journalIsBalancedAndImmutableAndReversalIsCompensating() throws Exception {
        service.createAccount("ACC-1", "USD");
        // Reversal arrives first; sequence buffering lets the referenced original arrive later.
        Map<String, Object> reversal = request("TXN-REV", "KEY-REV", "ACC-1", "REVERSAL", "75.00", 2);
        reversal.put("originalTransactionId", "TXN-CREDIT");
        service.submit(reversal);
        assertTrue(service.processOne());
        assertEquals("PENDING", transaction("TXN-REV").get("status"));
        service.submit(request("TXN-CREDIT", "KEY-CREDIT", "ACC-1", "CREDIT", "75.00", 1));
        assertTrue(service.processOne());
        assertTrue(service.processOne());
        assertEquals("0.0000", service.account("ACC-1").get("balance"));
        assertEquals(2, service.ledger("TXN-CREDIT").size());
        assertEquals(2, service.ledger("TXN-REV").size());
        assertThrows(Exception.class, () -> {
            try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM ledger_entries WHERE transaction_id='TXN-CREDIT'");
            }
        });
    }

    @Test
    void transientFailureRetriesWithoutDuplicateEffect() throws Exception {
        service.createAccount("ACC-1", "USD");
        Map<String, Object> request = request("TXN-RETRY", "KEY-RETRY", "ACC-1", "CREDIT", "12.00", 1);
        request.put("transientFailuresToSimulate", 1);
        service.submit(request);
        assertTrue(service.processOne());
        assertEquals("RETRY_PENDING", transaction("TXN-RETRY").get("status"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline && !"PROCESSED".equals(transaction("TXN-RETRY").get("status"))) {
            service.processOne();
            Thread.sleep(100);
        }
        assertEquals("PROCESSED", transaction("TXN-RETRY").get("status"));
        assertEquals("12.0000", service.account("ACC-1").get("balance"));
        assertEquals(2, service.ledger("TXN-RETRY").size());
    }

    @Test
    void simultaneousSubmissionsAcrossThreadsProduceOneAcceptedTransaction() throws Exception {
        service.createAccount("ACC-1", "USD");
        Map<String, Object> request = request("TXN-CONCURRENT", "KEY-CONCURRENT", "ACC-1", "CREDIT", "9.00", 1);
        try (PgTransactionService secondInstance = new PgTransactionService(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), 3, 12, true)) {
            ExecutorService pool = Executors.newFixedThreadPool(10);
            try {
                List<Future<Map<String, Object>>> results = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    PgTransactionService target = i % 2 == 0 ? service : secondInstance;
                    results.add(pool.submit(() -> target.submit(request)));
                }
                int duplicates = 0;
                for (Future<Map<String, Object>> result : results) if (Boolean.TRUE.equals(result.get().get("duplicate"))) duplicates++;
                assertEquals(9, duplicates);
                assertTrue(service.processOne());
                assertEquals("9.0000", service.account("ACC-1").get("balance"));
                assertEquals(2, service.ledger("TXN-CONCURRENT").size());
            } finally {
                pool.shutdownNow();
            }
        }
    }


    @Test
    void multipleWorkerInstancesSerializeAdjacentAccountSequences() throws Exception {
        service.createAccount("ACC-1", "USD");
        service.submit(request("TXN-1", "KEY-1", "ACC-1", "CREDIT", "100.00", 1));
        service.submit(request("TXN-2", "KEY-2", "ACC-1", "DEBIT", "25.00", 2));
        try (PgTransactionService secondInstance = new PgTransactionService(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), 3, 12, true)) {
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = workers.submit(() -> drainFor(secondInstance, "TXN-1", "TXN-2"));
                Future<?> second = workers.submit(() -> drainFor(service, "TXN-1", "TXN-2"));
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            } finally {
                workers.shutdownNow();
            }
        }
        assertEquals("75.0000", service.account("ACC-1").get("balance"));
        assertEquals("PROCESSED", transaction("TXN-1").get("status"));
        assertEquals("PROCESSED", transaction("TXN-2").get("status"));
        assertEquals(4, service.ledger("TXN-1").size() + service.ledger("TXN-2").size());
    }

    @Test
    void currencyMinorUnitPrecisionIsValidatedBeforeAcceptance() throws Exception {
        service.createAccount("ACC-JPY", "JPY");
        Map<String, Object> invalid = request("TXN-JPY", "KEY-JPY", "ACC-JPY", "CREDIT", "10.50", 1);
        invalid.put("currency", "JPY");
        assertThrows(PgTransactionService.BadRequest.class, () -> service.submit(invalid));
    }

    private static void drainFor(PgTransactionService worker, String firstId, String secondId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            try {
                worker.processOne();
                List<Map<String,Object>> rows = worker.transactions(100);
                boolean firstDone = rows.stream().anyMatch(r -> firstId.equals(r.get("transactionId")) && "PROCESSED".equals(r.get("status")));
                boolean secondDone = rows.stream().anyMatch(r -> secondId.equals(r.get("transactionId")) && "PROCESSED".equals(r.get("status")));
                if (firstDone && secondDone) return;
                Thread.sleep(50);
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        throw new AssertionError("Workers did not drain contiguous sequences before timeout");
    }

    private Map<String, Object> transaction(String id) throws Exception {
        return service.transactions(500).stream().filter(row -> id.equals(row.get("transactionId"))).findFirst().orElseThrow();
    }

    private static Map<String, Object> request(String transactionId, String key, String accountId,
                                               String type, String amount, long sequence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transactionId", transactionId);
        result.put("requestId", "REQ-" + UUID.randomUUID());
        result.put("idempotencyKey", key);
        result.put("accountId", accountId);
        result.put("transactionType", type);
        result.put("amount", new BigDecimal(amount));
        result.put("currency", "USD");
        result.put("sequenceNumber", sequence);
        result.put("eventTime", Instant.parse("2026-10-10T00:00:00Z").toString());
        return result;
    }
}
