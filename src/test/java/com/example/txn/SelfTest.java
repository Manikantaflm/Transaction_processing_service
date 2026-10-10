package com.example.txn;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Dependency-free executable unit/integration test suite; run with ./verify-local.sh. */
public final class SelfTest {
    private int passed;
    public static void main(String[] args) throws Exception { new SelfTest().run(); }
    private void run() throws Exception {
        test("credit then debit updates exact balance", this::creditDebit);
        test("duplicate replay does not change balance or original status", this::duplicateReplay);
        test("same idempotency key with changed payload conflicts", this::payloadConflict);
        test("out of order sequence buffers and drains", this::outOfOrder);
        test("negative amount is rejected", this::negativeAmount);
        test("insufficient funds leaves balance unchanged", this::insufficientFunds);
        test("transient retry succeeds exactly once", this::retrySuccess);
        test("retry exhaustion has no financial effect", this::retryExhaustion);
        test("state survives process restart", this::durableRestart);
        test("JSON parser rejects duplicate keys", this::duplicateJsonKey);
        test("concurrent duplicate submissions have one effect", this::concurrentDuplicate);
        test("pending event with wrong currency cannot mutate account", this::pendingCurrencyMismatch);
        test("queued transaction is recoverable by worker", this::queuedWorker);
        test("excess amount precision is rejected", this::excessPrecision);
        test("invalid currency is rejected", this::invalidCurrency);
        test("corrupt snapshot is rejected at startup", this::corruptSnapshot);
        test("out of order concurrent submissions drain contiguously", this::concurrentOutOfOrder);
        test("account sequence collision is rejected", this::sequenceCollision);
        System.out.println("PASS: " + passed + " tests; 0 failures");
    }
    private void test(String name, ThrowingRunnable r) throws Exception { try { r.run(); passed++; System.out.println("PASS " + name); } catch(Throwable e) { System.err.println("FAIL " + name + ": " + e); throw e; } }
    private StateStore store() throws Exception { return new StateStore(Files.createTempDirectory("txn-test-").resolve("state.tsv")); }
    private TransactionService service(boolean faults) throws Exception { return new TransactionService(store(),faults); }
    private TransactionRequest req(String id,String key,long seq,Transaction.Type type,String amount,int failures) { return new TransactionRequest(id,"REQ-"+id,key,"ACC-1",type,new BigDecimal(amount),"USD",seq,Instant.parse("2026-01-01T00:00:00Z"),failures); }
    private void creditDebit() throws Exception { var s=service(false);s.submit(req("T1","K1",1,Transaction.Type.CREDIT,"100.00",0));s.submit(req("T2","K2",2,Transaction.Type.DEBIT,"35.25",0));eq("64.75",s.balance("ACC-1").orElseThrow().balance().toPlainString());eq(2L,s.balance("ACC-1").orElseThrow().lastProcessedSequence()); }
    private void duplicateReplay() throws Exception { var s=service(false);var a=s.submit(req("T1","K1",1,Transaction.Type.CREDIT,"10",0));var b=s.submit(req("T1","K1",1,Transaction.Type.CREDIT,"10",0));eq("PROCESSED",a.status().name());eq("DUPLICATE",b.outcome());eq("10",s.balance("ACC-1").orElseThrow().balance().stripTrailingZeros().toPlainString());eq(1,s.all().size());eq(1,s.ledgerEntries().size());eq(1L,s.summary().get("DUPLICATE")); }
    private void payloadConflict() throws Exception { var s=service(false);s.submit(req("T1","K1",1,Transaction.Type.CREDIT,"10",0));expect(ConflictException.class,()->s.submit(req("T1","K1",1,Transaction.Type.CREDIT,"11",0))); }
    private void outOfOrder() throws Exception { var s=service(false);eq("PENDING",s.submit(req("T2","K2",2,Transaction.Type.CREDIT,"20",0)).status().name());eq("PROCESSED",s.submit(req("T1","K1",1,Transaction.Type.CREDIT,"10",0)).status().name());eq("PROCESSED",s.all().stream().filter(t->t.transactionId().equals("T2")).findFirst().orElseThrow().status().name());eq("30",s.balance("ACC-1").orElseThrow().balance().stripTrailingZeros().toPlainString()); }
    private void negativeAmount() throws Exception { var s=service(false);expect(ValidationException.class,()->s.submit(req("T1","K1",1,Transaction.Type.CREDIT,"-1",0)));eq(0,s.all().size()); }
    private void insufficientFunds() throws Exception { var s=service(false);var t=s.submit(req("T1","K1",1,Transaction.Type.DEBIT,"10",0));eq("FAILED",t.status().name());eq(null,s.balance("ACC-1").orElse(null)); }
    private void retrySuccess() throws Exception { var s=service(true);var t=s.submit(req("T1","K1",1,Transaction.Type.CREDIT,"10",2));eq("PROCESSED",t.status().name());eq(2,t.retryCount());eq("10",s.balance("ACC-1").orElseThrow().balance().stripTrailingZeros().toPlainString()); }
    private void retryExhaustion() throws Exception { var s=service(true);var t=s.submit(req("T1","K1",1,Transaction.Type.CREDIT,"10",3));eq("FAILED",t.status().name());eq(3,t.retryCount());eq(null,s.balance("ACC-1").orElse(null));var retried=s.retry("T1");eq("PROCESSED",retried.status().name());eq("10",s.balance("ACC-1").orElseThrow().balance().stripTrailingZeros().toPlainString());eq(1,s.ledgerEntries().size()); }
    private void durableRestart() throws Exception { Path f=Files.createTempDirectory("txn-persist-").resolve("state.tsv");var s=new TransactionService(new StateStore(f),false);s.submit(req("T1","K1",1,Transaction.Type.CREDIT,"12.34",0));var s2=new TransactionService(new StateStore(f),false);eq("12.34",s2.balance("ACC-1").orElseThrow().balance().toPlainString());eq(1,s2.all().size());eq(1,s2.ledgerEntries().size()); }
    private void duplicateJsonKey() { expect(ValidationException.class,()->Json.parse("{\"x\":1,\"x\":2}")); }
    private void concurrentDuplicate() throws Exception { var s=service(false);var pool=Executors.newFixedThreadPool(8);try{List<Callable<Transaction>> calls=new ArrayList<>();for(int i=0;i<20;i++)calls.add(()->s.submit(req("T1","K1",1,Transaction.Type.CREDIT,"10",0)));for(Future<Transaction> f:pool.invokeAll(calls))f.get();eq("10",s.balance("ACC-1").orElseThrow().balance().stripTrailingZeros().toPlainString());eq(1,s.all().size());eq(1,s.ledgerEntries().size());eq(19L,s.summary().get("DUPLICATE"));}finally{pool.shutdownNow();} }
    private void pendingCurrencyMismatch() throws Exception {
        var s=service(false);
        eq("PENDING",s.submit(new TransactionRequest("T2","REQ-T2","K2","ACC-1",Transaction.Type.CREDIT,new BigDecimal("20"),"EUR",2,Instant.parse("2026-01-01T00:00:00Z"),0)).status().name());
        s.submit(req("T1","K1",1,Transaction.Type.CREDIT,"10",0));
        Transaction t=s.all().stream().filter(x->x.transactionId().equals("T2")).findFirst().orElseThrow();
        eq("FAILED",t.status().name()); eq("10",s.balance("ACC-1").orElseThrow().balance().stripTrailingZeros().toPlainString());
    }
    private void queuedWorker() throws Exception {
        var s=service(false); eq("RECEIVED",s.enqueue(req("T1","K1",1,Transaction.Type.CREDIT,"10",0)).status().name());
        eq("PROCESSED",s.processReceived("T1").status().name()); eq("10",s.balance("ACC-1").orElseThrow().balance().stripTrailingZeros().toPlainString());
    }
    private void excessPrecision() throws Exception { var s=service(false);expect(ValidationException.class,()->s.submit(req("T1","K1",1,Transaction.Type.CREDIT,"1.001",0)));eq(0,s.all().size()); }
    private void invalidCurrency() throws Exception { var s=service(false);TransactionRequest r=new TransactionRequest("T1","REQ-T1","K1","ACC-1",Transaction.Type.CREDIT,new BigDecimal("1"),"usd",1,Instant.parse("2026-01-01T00:00:00Z"),0);expect(ValidationException.class,()->s.submit(r)); }
    private void corruptSnapshot() throws Exception { Path f=Files.createTempFile("txn-corrupt-",".tsv");Files.writeString(f,"not-a-valid-row\n");expect(java.io.IOException.class,()->new StateStore(f)); }
    private void concurrentOutOfOrder() throws Exception {
        var s=service(false);var pool=Executors.newFixedThreadPool(8);
        try {
            List<Callable<Transaction>> calls=new ArrayList<>();
            for(int i=20;i>=1;i--){final int seq=i;calls.add(()->s.submit(req("T"+seq,"K"+seq,seq,Transaction.Type.CREDIT,"1.00",0)));}
            for(Future<Transaction> f:pool.invokeAll(calls))f.get();
            eq(20,s.ledgerEntries().size());eq("20",s.balance("ACC-1").orElseThrow().balance().stripTrailingZeros().toPlainString());
            if(s.all().stream().anyMatch(t->t.status()!=Transaction.Status.PROCESSED))throw new AssertionError("Every contiguous sequence should be drained");
        } finally {pool.shutdownNow();}
    }
    private void sequenceCollision() throws Exception { var s=service(false);s.submit(req("T1","K1",1,Transaction.Type.CREDIT,"1",0));expect(ConflictException.class,()->s.submit(req("T2","K2",1,Transaction.Type.CREDIT,"2",0)));eq(1,s.ledgerEntries().size()); }
    private static void eq(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("expected="+expected+", actual="+actual);}
    private static void expect(Class<? extends Throwable> type,ThrowingRunnable r){try{r.run();throw new AssertionError("Expected "+type.getSimpleName());}catch(Throwable e){if(!type.isInstance(e))throw new AssertionError("Expected "+type.getSimpleName()+" but got "+e,e);}}
    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
