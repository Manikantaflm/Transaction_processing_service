package com.example.txn.production;

import com.example.txn.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Small HTTP adapter for the PostgreSQL-backed multi-instance production profile. */
public final class ProductionApplication {

    private static final Logger LOG = Logger.getLogger(ProductionApplication.class.getName());

    private static final int MAX_BODY_BYTES = 1_048_576;
    private ProductionApplication() {
    }

    public static void main(String[] args) throws Exception {
        String jdbcUrl = required("DB_URL");
        String dbUser = required("DB_USER");
        String dbPassword = required("DB_PASSWORD");
        String apiToken = required("API_BEARER_TOKEN");
        String opsToken = required("OPS_BEARER_TOKEN");
        if (apiToken.length() < 32 || opsToken.length() < 32 || java.security.MessageDigest.isEqual(apiToken.getBytes(StandardCharsets.UTF_8), opsToken.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("API_BEARER_TOKEN and OPS_BEARER_TOKEN must be different values of at least 32 characters");
        }
        int port = integerEnv("PORT", 8080, 1, 65535);
        int poolSize = integerEnv("DB_POOL_SIZE", 12, 2, 100);
        int maxRetries = integerEnv("MAX_RETRIES", 5, 1, 20);
        migrateDatabase(jdbcUrl, dbUser, dbPassword);
        boolean allowFailureSimulation = Boolean.parseBoolean(System.getenv().getOrDefault("ENABLE_TEST_FAULT_INJECTION", "false"));
        if (allowFailureSimulation && "production".equalsIgnoreCase(System.getenv().getOrDefault("APP_ENV", "production"))) {
            throw new IllegalStateException("Fault injection cannot be enabled when APP_ENV=production");
        }
        PgTransactionService service = new PgTransactionService(jdbcUrl, dbUser, dbPassword, maxRetries, poolSize, allowFailureSimulation);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 128);
        int httpThreads = integerEnv("HTTP_THREADS", 16, 4, 256);
        // A bounded queue provides back-pressure instead of allowing an unbounded request backlog under load.
        var httpExecutor = new java.util.concurrent.ThreadPoolExecutor(httpThreads, httpThreads, 0L, TimeUnit.MILLISECONDS,
        new java.util.concurrent.ArrayBlockingQueue<>(256), runnable -> new Thread(runnable, "http-worker"),
        new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        server.setExecutor(httpExecutor);
        server.createContext("/", ProductionApplication::handleLanding);
        server.createContext("/health/live", ProductionApplication::handleLive);
        server.createContext("/health/ready", x -> handleReady(x, service, opsToken));
        server.createContext("/v1/", x -> handleApi(x, service, apiToken, opsToken));
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r,"transaction-worker"); t.setDaemon(true); return t; });
        worker.scheduleWithFixedDelay(() -> {
            try { boolean didWork = service.processOne(); if (!didWork) Thread.sleep(250); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception e) {
                if (e instanceof SQLException sql) {
                    LOG.log(Level.SEVERE, "Background processing attempt failed; SQLState={0}", sql.getSQLState());
                } else {
                    LOG.log(Level.SEVERE, "Background processing attempt failed; exceptionType={0}", e.getClass().getSimpleName());
                }
                try { Thread.sleep(1000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }, 0, 10, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(5);
            worker.shutdownNow();
            httpExecutor.shutdown();
            try { httpExecutor.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try { service.close(); }
            catch (Exception e) { LOG.log(Level.WARNING,"Connection pool close failed; exceptionType={0}",e.getClass().getSimpleName()); }
        }, "shutdown"));
        server.start();
        LOG.info(() -> "PostgreSQL transaction service listening on port " + port + "; bearer authentication is enabled");
    }

    private static void migrateDatabase(String url, String user, String password) {
        // Flyway uses PostgreSQL advisory locks so simultaneous replica startups serialize migrations.
        try {
            Class<?> flywayClass = Class.forName("org.flywaydb.core.Flyway");
            Object configuration = flywayClass.getMethod("configure").invoke(null);
            Class<?> configurationClass = configuration.getClass();
            configurationClass.getMethod("dataSource", String.class, String.class, String.class).invoke(configuration, url, user, password);
            configurationClass.getMethod("locations", String[].class).invoke(configuration, (Object) new String[]{"classpath:db/migration"});
            Object flyway = configurationClass.getMethod("load").invoke(configuration);
            flyway.getClass().getMethod("migrate").invoke(flyway);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Database migrations failed or Flyway dependencies are missing; refusing startup", e);
        }
    }

    static Set<String> allowedOrigins() {
        String configured = System.getenv().getOrDefault("UI_ALLOWED_ORIGIN",
                "http://localhost:5173,http://localhost:5174,http://127.0.0.1:5173,http://127.0.0.1:5174");
        Set<String> allowed = new LinkedHashSet<>();
        for (String raw : configured.split(",")) {
            String value = raw == null ? "" : raw.trim();
            if (!value.isEmpty()) {
                allowed.add(value);
            }
        }
        if (allowed.isEmpty()) {
            allowed.add("http://localhost:5173");
            allowed.add("http://localhost:5174");
        }
        return Set.copyOf(allowed);
    }

    private static void handleLive(HttpExchange x) throws IOException {
        if (!"GET".equals(x.getRequestMethod()) || !"/health/live".equals(x.getRequestURI().getPath())) {
            respond(x, 404, Map.of("error", "Not found"));
            return;
        }
        respond(x, 200, Map.of("status", "UP"));
    }

    private static void handleLanding(HttpExchange x) throws IOException {
        if (!"GET".equals(x.getRequestMethod()) || !"/".equals(x.getRequestURI().getPath())) {
            respond(x, 404, Map.of("error", "Not found"));
            return;
        }
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Transaction Processing Service</title>" +
        "<style>body{font:16px system-ui;max-width:850px;margin:4rem auto;padding:0 1rem;color:#182230}code{background:#f1f5f9;padding:.2rem .35rem;border-radius:4px}.card{border:1px solid #d0d7de;border-radius:12px;padding:1rem;margin:1rem 0}</style></head><body>" +
        "<h1>Transaction Processing Service</h1><p>PostgreSQL-backed, idempotent background transaction processor.</p>" +
        "<div class=\"card\"><h2>Health</h2><p><a href=\"/health/live\">Liveness endpoint</a> · <a href=\"/health/ready\">Readiness endpoint</a></p></div>" +
        "<div class=\"card\"><h2>Authenticated API</h2><p>Use the API bearer token to submit transactions. Use the separate operations bearer token for account management, summaries, ledger reads, and redrive.</p>" +
        "<p>See the repository README and <code>docs/API.md</code> for examples.</p></div><p>Do not use this assessment service to move real funds.</p></body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        x.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'");
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(200, bytes.length);
        try (var out = x.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void handleReady(HttpExchange x, PgTransactionService service, String opsToken) throws IOException {
        if (!"GET".equals(x.getRequestMethod()) || !"/health/ready".equals(x.getRequestURI().getPath())) {
            respond(x, 404, Map.of("error", "Not found"));
            return;
        }
        // Readiness exposes operational details, so require the operations credential.
        if (!authorized(x, opsToken)) {
            respond(x, 401, Map.of("error", "Unauthorized"));
            return;
        }
        try {
            respond(x,200,Map.of("status","UP","database","reachable","summary",service.summary()));
        }
        catch(Exception e) {
            if (e instanceof SQLException sql) {
                LOG.log(Level.WARNING, "Readiness database check failed; SQLState={0}", sql.getSQLState());
            } else {
                LOG.log(Level.WARNING, "Readiness check failed; exceptionType={0}", e.getClass().getSimpleName());
            }
            respond(x,503,Map.of("status","DOWN"));
        }
    }

    private static void handleApi(HttpExchange x, PgTransactionService service, String apiToken, String opsToken) throws IOException {
        // Explicit allow-list for the optional Vite UI; override for deployment. Never use wildcard CORS.
        String origin = x.getRequestHeaders().getFirst("Origin");
        Set<String> allowedOrigins = allowedOrigins();
        if (origin != null && allowedOrigins.contains(origin)) {
            x.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            x.getResponseHeaders().set("Vary", "Origin");
            x.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
            x.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        }
        if ("OPTIONS".equals(x.getRequestMethod())) {
            if (origin == null || !allowedOrigins.contains(origin)) {
                respond(x, 403, Map.of("error", "Origin is not allowed"));
            } else {
                x.getResponseHeaders().set("Cache-Control", "no-store");
                x.sendResponseHeaders(204, -1);
                x.close();
            }
            return;
        }
        String path=x.getRequestURI().getPath();
        String method=x.getRequestMethod();
        // Split operational/read-only account data and redrive privileges from transaction submission.
        boolean operationsRoute = ("GET".equals(method) && ("/v1/summary".equals(path) || "/v1/transactions".equals(path)
        || path.matches("/v1/accounts/[A-Za-z0-9._:-]+") || path.matches("/v1/transactions/[A-Za-z0-9._:-]+/ledger")))
        || ("POST".equals(method) && ("/v1/accounts".equals(path) || path.matches("/v1/transactions/[A-Za-z0-9._:-]+/redrive")));
        if (!authorized(x, operationsRoute ? opsToken : apiToken)) {
            respond(x,401,Map.of("error","Unauthorized"));
            return;
        }
        try {
            if ("POST".equals(method) && "/v1/accounts".equals(path)) {
                Map<String,Object> body=readBody(x);
                respond(x,201,service.createAccount(string(body,"accountId"),string(body,"currency")));
                return;
            }
            if ("GET".equals(method) && path.matches("/v1/accounts/[A-Za-z0-9._:-]+")) {
                String accountId = path.substring("/v1/accounts/".length());
                respond(x,200,service.account(accountId));
                return;
            }
            if ("POST".equals(method) && "/v1/transactions/batch".equals(path)) {
                Object parsed = readBodyValue(x);
                if (!(parsed instanceof List<?> items)) throw new PgTransactionService.BadRequest("Batch body must be a JSON array");
                if (items.size() > 500) throw new PgTransactionService.BadRequest("Batch cannot exceed 500 records");
                List<Map<String,Object>> outcomes = new ArrayList<>(items.size());
                for (int i = 0; i < items.size(); i++) {
                    Map<String,Object> outcome = new LinkedHashMap<>();
                    outcome.put("index", i);
                    try {
                        Map<String,Object> item = Json.object(items.get(i));
                        Object transactionId = item.get("transactionId");
                        if (transactionId instanceof String value) outcome.put("transactionId", value);
                        Map<String,Object> result = service.submit(item);
                        outcome.put("outcome", Boolean.TRUE.equals(result.get("duplicate")) ? "DUPLICATE" : "ACCEPTED");
                        outcome.put("result", result);
                    }
                    catch (PgTransactionService.BadRequest | PgTransactionService.Conflict | PgTransactionService.NotFound e) {
                        outcome.put("outcome", "REJECTED");
                        outcome.put("error", e.getMessage());
                    }
                    catch (PgTransactionService.PersistenceFailure | SQLException e) {
                        outcome.put("outcome", "RETRYABLE_ERROR");
                        outcome.put("error", "Persistence failure; retry the item with the same idempotency key");
                    }
                    catch (RuntimeException e) {
                        outcome.put("outcome", "REJECTED");
                        outcome.put("error", "Invalid transaction record");
                    }
                    outcomes.add(outcome);
                }
                respond(x,207,Map.of("count",outcomes.size(),"outcomes",outcomes));
                return;
            }
            if ("POST".equals(method) && "/v1/transactions".equals(path)) {
                respond(x,202,service.submit(readBody(x)));
                return;
            }
            if ("GET".equals(method) && "/v1/summary".equals(path)) {
                respond(x,200,service.summary());
                return;
            }
            if ("GET".equals(method) && path.matches("/v1/transactions/[A-Za-z0-9._:-]+/ledger")) {
                String id = path.substring("/v1/transactions/".length(), path.length()-"/ledger".length());
                respond(x,200,Map.of("transactionId",id,"entries",service.ledger(id)));
                return;
            }
            if ("POST".equals(method) && path.matches("/v1/transactions/[A-Za-z0-9._:-]+/redrive")) {
                String id = path.substring("/v1/transactions/".length(), path.length()-"/redrive".length());
                respond(x,202,service.redrive(id));
                return;
            }
            if ("GET".equals(method) && "/v1/transactions".equals(path)) {
                String raw=x.getRequestURI().getRawQuery();
                int limit=100;
                if(raw!=null) for(String pair:raw.split("&")){
                    String[] kv=pair.split("=",2);
                    if(kv.length==2&&kv[0].equals("limit"))try{
                        limit=Integer.parseInt(kv[1]);
                    }
                    catch(NumberFormatException e){
                        throw new PgTransactionService.BadRequest("limit must be an integer");
                    }
                }
                respond(x,200,Map.of("transactions",service.transactions(limit)));
                return;
            }
            respond(x,404,Map.of("error","Not found"));
        }
        catch(PgTransactionService.BadRequest e) {
            respond(x,400,Map.of("error",e.getMessage()));
        }
        catch(PgTransactionService.Conflict e) {
            respond(x,409,Map.of("error",e.getMessage()));
        }
        catch(PgTransactionService.NotFound e) {
            respond(x,404,Map.of("error",e.getMessage()));
        }
        catch(IllegalStateException e) {
            LOG.log(Level.WARNING,"Request could not be completed",e);
            respond(x,500,Map.of("error","Request could not be completed"));
        }
        catch(SQLException e) {
            LOG.log(Level.SEVERE,"Database operation failed; details excluded from client response",e);
            respond(x,503,Map.of("error","Temporary persistence failure; retry with the same idempotency key"));
        }
        catch(Exception e) {
            LOG.log(Level.WARNING,"Malformed or failed request",e);
            respond(x,400,Map.of("error","Invalid request"));
        }
    }

    private static Map<String,Object> readBody(HttpExchange x) throws IOException {
        try {
            return Json.object(readBodyValue(x));
        }
        catch (PgTransactionService.BadRequest e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw new PgTransactionService.BadRequest("JSON body must be an object");
        }
    }

    private static Object readBodyValue(HttpExchange x) throws IOException {
        String length=x.getRequestHeaders().getFirst("Content-Length");
        if(length!=null)try{
            if(Long.parseLong(length)>MAX_BODY_BYTES)throw new PgTransactionService.BadRequest("Request body too large");
        }
        catch(NumberFormatException e){
            throw new PgTransactionService.BadRequest("Invalid Content-Length");
        }
        byte[] bytes=x.getRequestBody().readNBytes(MAX_BODY_BYTES+1);
        if(bytes.length>MAX_BODY_BYTES)throw new PgTransactionService.BadRequest("Request body too large");
        String contentType=x.getRequestHeaders().getFirst("Content-Type");
        if(contentType==null||!contentType.toLowerCase(Locale.ROOT).startsWith("application/json"))throw new PgTransactionService.BadRequest("Content-Type must be application/json");
        try{
            return Json.parse(new String(bytes,StandardCharsets.UTF_8));
        }
        catch(RuntimeException e){
            throw new PgTransactionService.BadRequest("Malformed JSON body");
        }
    }

    private static String string(Map<String,Object> body,String key){
        Object v=body.get(key);
        if(!(v instanceof String s)||s.isBlank())throw new PgTransactionService.BadRequest(key+" is required");
        return s;
    }

    private static boolean authorized(HttpExchange x,String token){
        String header=x.getRequestHeaders().getFirst("Authorization");
        return header!=null&&header.startsWith("Bearer ")&&constantTimeEquals(header.substring(7),token);
    }

    private static boolean constantTimeEquals(String a,String b){
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange x,int status,Object body)throws IOException{
        byte[] bytes=Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control","no-store");
        x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        x.sendResponseHeaders(status,bytes.length);
        try(var out=x.getResponseBody()){
            out.write(bytes);
        }
    }

    private static String required(String key){
        String value=System.getenv(key);
        if(value==null||value.isBlank())throw new IllegalStateException("Required environment variable is missing: "+key);
        return value;
    }

    private static int integerEnv(String key,int fallback,int min,int max){
        String raw=System.getenv(key);
        if(raw==null||raw.isBlank())return fallback;
        try{
            int value=Integer.parseInt(raw);
            if(value<min||value>max)throw new IllegalArgumentException(key+" must be between "+min+" and "+max);
            return value;
        }
        catch(NumberFormatException e){
            throw new IllegalArgumentException(key+" must be an integer",e);
        }
    }
}
