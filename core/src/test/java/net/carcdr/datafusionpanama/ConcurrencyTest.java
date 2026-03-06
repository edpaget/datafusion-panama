package net.carcdr.datafusionpanama;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Tier 1 concurrency tests for native wrapper types.
 *
 * <p>These tests exercise the check-then-act race windows in {@code close()} and concurrent usage
 * of native resources. They use {@link CountDownLatch} gates to maximise thread contention and
 * {@link RepeatedTest} to increase the probability of hitting race windows.
 *
 * <p>Tests marked {@code @Disabled} trigger native memory corruption (double-free or
 * use-after-free) that kills the JVM with SIGSEGV/SIGABRT. They will be enabled after Phase 3 adds
 * synchronisation to the Java wrappers.
 */
class ConcurrencyTest {

    private static final int THREAD_COUNT = 8;
    private static final int REPETITIONS = 50;

    // ── DataFusionRuntime ───────────────────────────────────────────────

    @Disabled("Double-free crashes JVM with SIGABRT; enable after Phase 3 adds synchronisation")
    @RepeatedTest(REPETITIONS)
    void concurrentCloseOnRuntime() throws Exception {
        DataFusionRuntime runtime = DataFusionRuntime.create();
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                exec.submit(
                        () -> {
                            try {
                                gate.await();
                                runtime.close();
                            } catch (Throwable t) {
                                errors.add(t);
                            }
                        });
            }
            gate.countDown();
        } finally {
            exec.shutdown();
            assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS), "threads did not finish");
            runtime.close();
        }
        assertTrue(errors.isEmpty(), "unexpected errors during concurrent close: " + errors);
    }

    @Disabled("Use-after-free crashes JVM with SIGSEGV; enable after Phase 3 adds synchronisation")
    @RepeatedTest(REPETITIONS)
    void useRuntimeWhileClosing() throws Exception {
        DataFusionRuntime runtime = DataFusionRuntime.create();
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                boolean closer = (i == 0);
                exec.submit(
                        () -> {
                            try {
                                gate.await();
                                if (closer) {
                                    runtime.close();
                                } else {
                                    DataFusionSession session = runtime.newSession();
                                    session.close();
                                }
                            } catch (IllegalStateException | AssertionError e) {
                                // Expected: resource closed while in use
                            } catch (DataFusionException e) {
                                // Expected: Rust-level error from stale pointer
                            } catch (Throwable t) {
                                errors.add(t);
                            }
                        });
            }
            gate.countDown();
        } finally {
            exec.shutdown();
            assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS), "threads did not finish");
            runtime.close();
        }
        assertTrue(errors.isEmpty(), "unexpected errors during use-while-closing: " + errors);
    }

    // ── DataFusionSession ───────────────────────────────────────────────

    @Disabled("Double-free crashes JVM with SIGABRT; enable after Phase 3 adds synchronisation")
    @RepeatedTest(REPETITIONS)
    void concurrentCloseOnSession() throws Exception {
        DataFusionRuntime runtime = DataFusionRuntime.create();
        DataFusionSession session = runtime.newSession();
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                exec.submit(
                        () -> {
                            try {
                                gate.await();
                                session.close();
                            } catch (Throwable t) {
                                errors.add(t);
                            }
                        });
            }
            gate.countDown();
        } finally {
            exec.shutdown();
            assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS), "threads did not finish");
            session.close();
            runtime.close();
        }
        assertTrue(errors.isEmpty(), "unexpected errors during concurrent close: " + errors);
    }

    @Disabled("Use-after-free crashes JVM with SIGSEGV; enable after Phase 3 adds synchronisation")
    @RepeatedTest(REPETITIONS)
    void useSessionWhileClosing() throws Exception {
        DataFusionRuntime runtime = DataFusionRuntime.create();
        DataFusionSession session = runtime.newSession();
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                boolean closer = (i == 0);
                exec.submit(
                        () -> {
                            try {
                                gate.await();
                                if (closer) {
                                    session.close();
                                } else {
                                    DataFusionDataFrame df = session.sql("SELECT 1");
                                    df.close();
                                }
                            } catch (IllegalStateException | AssertionError e) {
                                // Expected: resource closed while in use
                            } catch (DataFusionException e) {
                                // Expected: Rust-level error from stale pointer
                            } catch (Throwable t) {
                                errors.add(t);
                            }
                        });
            }
            gate.countDown();
        } finally {
            exec.shutdown();
            assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS), "threads did not finish");
            session.close();
            runtime.close();
        }
        assertTrue(errors.isEmpty(), "unexpected errors during use-while-closing: " + errors);
    }

    @RepeatedTest(REPETITIONS)
    void concurrentQueriesOnSharedSession() throws Exception {
        DataFusionRuntime runtime = DataFusionRuntime.create();
        DataFusionSession session = runtime.newSession();
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                int idx = i;
                exec.submit(
                        () -> {
                            try {
                                gate.await();
                                try (DataFusionDataFrame df =
                                        session.sql("SELECT " + idx + " AS val")) {
                                    df.count();
                                }
                            } catch (Throwable t) {
                                errors.add(t);
                            }
                        });
            }
            gate.countDown();
        } finally {
            exec.shutdown();
            assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS), "threads did not finish");
            session.close();
            runtime.close();
        }
        assertTrue(errors.isEmpty(), "unexpected errors during concurrent queries: " + errors);
    }

    // ── DataFusionDataFrame ─────────────────────────────────────────────

    @RepeatedTest(REPETITIONS)
    void concurrentCloseOnDataFrame() throws Exception {
        // DataFrame.close() delegates to Cleanable.clean(), which guarantees at-most-once
        // execution of the cleaning action. Concurrent close should be safe.
        DataFusionRuntime runtime = DataFusionRuntime.create();
        DataFusionSession session = runtime.newSession();
        DataFusionDataFrame df = session.sql("SELECT 1 AS a");
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                exec.submit(
                        () -> {
                            try {
                                gate.await();
                                df.close();
                            } catch (Throwable t) {
                                errors.add(t);
                            }
                        });
            }
            gate.countDown();
        } finally {
            exec.shutdown();
            assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS), "threads did not finish");
            df.close();
            session.close();
            runtime.close();
        }
        assertTrue(errors.isEmpty(), "unexpected errors during concurrent close: " + errors);
    }

    @Disabled("Use-after-free crashes JVM with SIGSEGV; enable after Phase 3 adds synchronisation")
    @RepeatedTest(REPETITIONS)
    void useDataFrameWhileClosing() throws Exception {
        DataFusionRuntime runtime = DataFusionRuntime.create();
        DataFusionSession session = runtime.newSession();
        DataFusionDataFrame df = session.sql("SELECT 1 AS a");
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                boolean closer = (i == 0);
                exec.submit(
                        () -> {
                            try {
                                gate.await();
                                if (closer) {
                                    df.close();
                                } else {
                                    df.count();
                                }
                            } catch (IllegalStateException | AssertionError e) {
                                // Expected: resource closed while in use
                            } catch (DataFusionException e) {
                                // Expected: Rust-level error from stale pointer
                            } catch (Throwable t) {
                                errors.add(t);
                            }
                        });
            }
            gate.countDown();
        } finally {
            exec.shutdown();
            assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS), "threads did not finish");
            df.close();
            session.close();
            runtime.close();
        }
        assertTrue(errors.isEmpty(), "unexpected errors during use-while-closing: " + errors);
    }
}
