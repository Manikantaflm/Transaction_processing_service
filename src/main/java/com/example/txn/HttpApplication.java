package com.example.txn;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** JDK-only HTTP adapter. The local backend can run without Maven or third-party runtime jars. */
public final class HttpApplication implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 1_000_000;
    private static final int MAX_BATCH_SIZE = 500;

    private final HttpServer server;
    private final TransactionService service;
    private final ScheduledExecutorService worker;
    private final ExecutorService httpExecutor;

    public HttpApplication(int port, TransactionService service) throws IOException {
        this.service = service;
        // Bind to loopback by default: this sample has no authentication layer.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
        server.createContext("/", this::handle);
        httpExecutor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));
        server.setExecutor(httpExecutor);
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "transaction-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        server.start();
        worker.scheduleWithFixedDelay(this::processQueuedWork, 300, 500, TimeUnit.MILLISECONDS);
    }

    private void processQueuedWork() {
        for (Transaction transaction : service.all()) {
            if (transaction.status() != Transaction.Status.RECEIVED
                    && transaction.status() != Transaction.Status.PROCESSING) {
                continue;
            }
            try {
                service.processReceived(transaction.transactionId());
            } catch (Exception exception) {
                // Avoid logging customer payloads or storage paths; include a traceable business ID.
                System.err.println("worker failure transactionId=" + transaction.transactionId()
                        + " type=" + exception.getClass().getSimpleName());
            }
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            if (method.equals("GET") && path.equals("/")) {
                send(exchange, 200, "text/html; charset=utf-8", dashboard());
                return;
            }
            if (method.equals("GET") && path.equals("/actuator/health")) {
                sendJson(exchange, 200, Map.of("status", "UP"));
                return;
            }
            if (method.equals("GET") && path.equals("/api/v1/summary")) {
                sendJson(exchange, 200, service.summary());
                return;
            }
            if (method.equals("GET") && path.equals("/api/v1/transactions")) {
                sendJson(exchange, 200, service.all().stream().map(HttpApplication::transactionMap).toList());
                return;
            }
            if (method.equals("GET") && path.equals("/api/v1/ledger")) {
                sendJson(exchange, 200, service.ledgerEntries().stream().map(HttpApplication::ledgerMap).toList());
                return;
            }
            if (method.equals("POST") && path.equals("/api/v1/transactions")) {
                TransactionRequest request = parseRequest(Json.object(Json.parse(readBody(exchange))));
                sendJson(exchange, 200, transactionMap(service.submit(request)));
                return;
            }
            if (method.equals("POST") && path.equals("/api/v1/transactions/queued")) {
                TransactionRequest request = parseRequest(Json.object(Json.parse(readBody(exchange))));
                sendJson(exchange, 202, transactionMap(service.enqueue(request)));
                return;
            }
            if (method.equals("POST") && path.equals("/api/v1/transactions/batch")) {
                handleBatch(exchange);
                return;
            }
            if (method.equals("POST") && path.startsWith("/api/v1/transactions/") && path.endsWith("/retry")) {
                String transactionId = path.substring("/api/v1/transactions/".length(), path.length() - "/retry".length());
                sendJson(exchange, 200, transactionMap(service.retry(transactionId)));
                return;
            }
            if (method.equals("GET") && path.startsWith("/api/v1/accounts/") && path.endsWith("/balance")) {
                String accountId = path.substring("/api/v1/accounts/".length(), path.length() - "/balance".length());
                sendBalance(exchange, accountId);
                return;
            }
            sendJson(exchange, 404, Map.of("error", "Not found"));
        } catch (ValidationException exception) {
            sendJson(exchange, 400, Map.of("error", safeMessage(exception)));
        } catch (ConflictException exception) {
            sendJson(exchange, 409, Map.of("error", safeMessage(exception)));
        } catch (NoSuchElementException exception) {
            sendJson(exchange, 404, Map.of("error", safeMessage(exception)));
        } catch (IllegalStateException exception) {
            sendJson(exchange, 409, Map.of("error", safeMessage(exception)));
        } catch (Exception exception) {
            // Unexpected errors are logged by type only; response omits stack traces and local paths.
            System.err.println("request failed path=" + path + " type=" + exception.getClass().getSimpleName());
            sendJson(exchange, 500, Map.of("error", "Internal processing error"));
        }
    }

    private void handleBatch(HttpExchange exchange) throws IOException {
        Object parsed = Json.parse(readBody(exchange));
        if (!(parsed instanceof List<?> items)) {
            throw new ValidationException("Batch body must be a JSON array");
        }
        if (items.size() > MAX_BATCH_SIZE) {
            throw new ValidationException("Batch size cannot exceed " + MAX_BATCH_SIZE);
        }

        List<Object> results = new ArrayList<>();
        for (Object item : items) {
            try {
                TransactionRequest request = parseRequest(Json.object(item));
                Transaction transaction = service.submit(request);
                results.add(Map.of("ok", true, "transaction", transactionMap(transaction)));
            } catch (Exception exception) {
                // Isolate bad records, but never leak internal storage exceptions in per-item output.
                results.add(Map.of("ok", false, "error", safeMessage(exception)));
            }
        }
        sendJson(exchange, 207, results);
    }

    private void sendBalance(HttpExchange exchange, String accountId) throws IOException {
        Optional<Account> account = service.balance(accountId);
        if (account.isEmpty()) {
            throw new NoSuchElementException("Account not found");
        }
        Account value = account.get();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accountId", value.accountId());
        response.put("currency", value.currency());
        response.put("balance", value.balance());
        response.put("lastProcessedSequence", value.lastProcessedSequence());
        sendJson(exchange, 200, response);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ValidationException("Request body exceeds 1 MB");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static TransactionRequest parseRequest(Map<String, Object> fields) {
        int simulatedFailures = fields.containsKey("transientFailuresToSimulate")
                ? intInteger(fields.get("transientFailuresToSimulate")) : 0;
        return new TransactionRequest(
                string(fields, "transactionId"),
                string(fields, "requestId"),
                string(fields, "idempotencyKey"),
                string(fields, "accountId"),
                transactionType(fields.get("transactionType")),
                decimal(fields.get("amount")),
                string(fields, "currency"),
                integer(fields.get("sequenceNumber")),
                instant(fields.get("eventTime")),
                simulatedFailures);
    }

    private static String string(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        return value instanceof String text ? text : null;
    }

    private static Transaction.Type transactionType(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return Transaction.Type.valueOf(text);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("transactionType must be CREDIT, DEBIT, or REVERSAL");
        }
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        throw new ValidationException("amount must be a JSON number");
    }

    private static long integer(Object value) {
        if (value instanceof BigDecimal decimal) {
            try {
                return decimal.longValueExact();
            } catch (ArithmeticException exception) {
                throw new ValidationException("Expected integer value");
            }
        }
        throw new ValidationException("Expected integer value");
    }

    private static int intInteger(Object value) {
        long number = integer(value);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new ValidationException("Expected 32-bit integer value");
        }
        return (int) number;
    }

    private static Instant instant(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (Exception exception) {
            throw new ValidationException("eventTime must be an ISO-8601 instant");
        }
    }

    private static Map<String, Object> transactionMap(Transaction transaction) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transactionId", transaction.transactionId());
        result.put("requestId", transaction.requestId());
        result.put("idempotencyKey", transaction.idempotencyKey());
        result.put("accountId", transaction.accountId());
        result.put("transactionType", transaction.type().name());
        result.put("amount", transaction.amount());
        result.put("currency", transaction.currency());
        result.put("sequenceNumber", transaction.sequenceNumber());
        result.put("eventTime", transaction.eventTime().toString());
        result.put("status", transaction.status().name());
        result.put("retryCount", transaction.retryCount());
        result.put("failureReason", transaction.failureReason());
        result.put("outcome", transaction.outcome());
        return result;
    }

    private static Map<String, Object> ledgerMap(LedgerEntry entry) {
        return Map.of(
                "transactionId", entry.transactionId(),
                "accountId", entry.accountId(),
                "signedAmount", entry.signedAmount(),
                "currency", entry.currency(),
                "sequenceNumber", entry.sequenceNumber(),
                "processedAt", entry.processedAt().toString());
    }

    private static String safeMessage(Exception exception) {
        if (exception instanceof ValidationException || exception instanceof ConflictException
                || exception instanceof IllegalStateException || exception instanceof NoSuchElementException) {
            return exception.getMessage() == null ? "Request could not be processed" : exception.getMessage();
        }
        return "Internal processing error";
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", Json.stringify(body));
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String dashboard() {
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Transaction Operations</title>
                <style>body{font:16px system-ui;max-width:1000px;margin:40px auto;padding:0 20px;color:#17202a}h1{margin-bottom:4px}.muted{color:#52616b}button{padding:8px 12px;cursor:pointer}table{border-collapse:collapse;width:100%;margin-top:18px}td,th{border-bottom:1px solid #ddd;padding:10px;text-align:left}.grid{display:flex;gap:12px;flex-wrap:wrap}.tile{background:#f4f6f8;border-radius:8px;padding:14px;min-width:120px}</style></head><body>
                <h1>Transaction Operations</h1><p class="muted">Idempotent background processing service · local assessment dashboard</p><button id="refresh">Refresh</button><div id="summary" class="grid" aria-live="polite"></div>
                <h2>Recent transactions</h2><table><thead><tr><th>Transaction</th><th>Account</th><th>Sequence</th><th>Amount</th><th>Status</th><th>Outcome</th></tr></thead><tbody id="rows"></tbody></table>
                <script>async function refresh(){const [s,t]=await Promise.all([fetch('/api/v1/summary').then(r=>r.json()),fetch('/api/v1/transactions').then(r=>r.json())]);document.querySelector('#summary').innerHTML=Object.entries(s).map(([k,v])=>`<div class="tile"><b>${k}</b><div>${v}</div></div>`).join('');document.querySelector('#rows').innerHTML=t.map(x=>`<tr><td>${esc(x.transactionId)}</td><td>${esc(x.accountId)}</td><td>${x.sequenceNumber}</td><td>${x.amount} ${esc(x.currency)}</td><td>${esc(x.status)}</td><td>${esc(x.outcome||'')}</td></tr>`).join('')}function esc(s){return String(s).replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',"'":'&#39;'}[c]))}document.querySelector('#refresh').addEventListener('click',refresh);refresh().catch(()=>document.querySelector('#summary').textContent='Unable to load summary');</script></body></html>
                """;
    }

    @Override
    public void close() {
        worker.shutdownNow();
        server.stop(1);
        httpExecutor.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Path stateFile = Path.of(System.getenv().getOrDefault("APP_STATE_FILE", "data/transaction-state.tsv"));
        boolean allowFaultInjection = Boolean.parseBoolean(
                System.getenv().getOrDefault("APP_PROCESSING_ALLOW_FAILURE_SIMULATION", "false"));
        StateStore store = new StateStore(stateFile);
        HttpApplication application = new HttpApplication(port, new TransactionService(store, allowFaultInjection));
        Runtime.getRuntime().addShutdownHook(new Thread(application::close));
        application.start();
        System.out.println("Transaction service listening on http://127.0.0.1:" + port
                + "; state=" + stateFile.toAbsolutePath());
    }
}
