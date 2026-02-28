# Implementation Design: datafusion-panama

Panama (Foreign Function & Memory API) based Java bindings for Apache DataFusion.

## Architecture

The hybrid architecture: a **custom Rust C-shim** handles control flow (session lifecycle, query dispatch, UDF registration, configuration) while the **Arrow C Data Interface** handles data exchange (zero-copy result transfer between Rust and Java).

This mirrors datafusion-python's architecture (PyO3 for control, Arrow C Data Interface for zero-copy via `__arrow_c_stream__`), but with Panama's lower call overhead (~5ns vs PyO3).

```
┌─────────────────────────────────────────────────┐
│  Java API Layer (hand-written, idiomatic)        │
│  DataFusionRuntime / DataFusionSession / ...     │
├─────────────────────────────────────────────────┤
│  Raw Panama Bindings (hand-written MethodHandles) │
│  MethodHandles for each extern "C" function      │
├──────────────────────┬──────────────────────────┤
│  Control Plane       │  Data Plane              │
│  Panama downcalls    │  Arrow C Data Interface  │
│  (session, config,   │  (ArrowArrayStream for   │
│   UDF registration,  │   zero-copy result       │
│   query dispatch)    │   transfer)              │
├──────────────────────┴──────────────────────────┤
│  Rust cdylib (extern "C" surface)               │
│  Tokio runtime, DataFusion SessionContext        │
└─────────────────────────────────────────────────┘
```

## Java API Surface

The public API exposes three core types:

**DataFusionRuntime** — wraps the Tokio runtime. Created once, lives for the duration of the process. Owns the native pointer in `Arena.global()`.

**DataFusionSession** — wraps a DataFusion `SessionContext`. Implements `AutoCloseable`. This is the primary entry point for users: run SQL, register tables, register UDFs.

**DataFrame** — wraps a DataFusion `DataFrame`. Represents a logical plan that hasn't been executed yet. Implements `AutoCloseable`.

Sketch of the API shape:

```java
try (var runtime = DataFusionRuntime.create()) {
    try (var session = runtime.newSession()) {
        session.registerCsv("my_table", Path.of("data.csv"));

        try (var df = session.sql("SELECT * FROM my_table WHERE x > 10")) {
            try (var reader = df.collect()) {  // RecordBatchReader
                var schema = reader.getSchema();
                while (reader.next()) {
                    RecordBatch batch = reader.getCurrentBatch();
                    // MemorySegment-backed column accessors
                    Int64Column col = batch.getColumn(0, Int64Column.class);
                    for (int i = 0; i < batch.getRowCount(); i++) {
                        if (!col.isNull(i)) {
                            long value = col.get(i);
                        }
                    }
                }
            }
        }
    }
}
```

### Arrow Data Consumption: Layered Approach

The data plane uses the Arrow C Data Interface for zero-copy result transfer. On the Java side, consumption is split into two layers:

**Layer 1 (core, no Arrow Java dependency):** A pure Panama consumer of `ArrowArrayStream`. This layer implements the C Data Interface protocol entirely via Panama downcalls — reading the struct layout, calling `get_schema`/`get_next`/`release` function pointers, and exposing the raw Arrow buffers as `MemorySegment`-backed read-only accessors. These thin wrappers understand Arrow's columnar layout (validity bitmaps, offsets, data buffers) but are minimal — enough to read results without any external dependency.

**Layer 2 (optional, deferred):** An Arrow Java bridge module that translates the `MemorySegment`-backed buffers into Arrow Java `VectorSchemaRoot` / `FieldVector` objects for users who need interop with the broader Arrow Java ecosystem. The open question for this layer is whether it can be zero-copy (wrapping the same underlying memory) or requires a copy due to Arrow Java's `BufferAllocator` ownership model. This is deferred — it doesn't need to be answered until the core path works.

This layering keeps phase 1 dependency-free on the Java side (only Panama APIs and JUnit) while leaving a clean seam for Arrow Java integration later.

## Rust FFI Layer

The Rust crate (`rust/`) is a `cdylib` that exposes `extern "C"` functions. Each function that can fail returns a `*mut DFResult` (see Error Handling below). Each function body is wrapped in `catch_unwind` to prevent panics from crossing the FFI boundary.

Rough grouping of FFI functions:

| Category | Functions |
|----------|-----------|
| Runtime  | `runtime_new`, `runtime_free` |
| Session  | `session_new`, `session_free`, `session_sql`, `session_register_csv`, `session_register_parquet` |
| DataFrame | `dataframe_collect`, `dataframe_free` |
| Result   | `result_is_ok`, `result_unwrap`, `result_error_message`, `result_free` |

### Error Handling: Opaque Result Handle

Every FFI function that can fail returns a `*mut DFResult` — an opaque handle that the caller inspects via helper functions:

- `result_is_ok(result) -> bool` — check success/failure
- `result_unwrap(result) -> *mut c_void` — extract the success value (caller must not call on error)
- `result_error_message(result) -> *const c_char` — get the error message (caller must not call on success)
- `result_free(result)` — free the result handle (must always be called)

On the Rust side, every FFI function body is wrapped in `catch_unwind` and the result (success value or error) is packaged into a `DFResult`. A macro handles the boilerplate:

```rust
#[no_mangle]
pub extern "C" fn session_sql(
    session: *mut SessionContext,
    sql: *const c_char,
) -> *mut DFResult
```

On the Java side, a single helper method consumes the pattern and converts errors to exceptions:

```java
// Internal helper — all FFI calls flow through this
private static MemorySegment unwrapOrThrow(MemorySegment result) throws DataFusionException {
    try {
        if ((boolean) resultIsOkHandle.invokeExact(result)) {
            return (MemorySegment) resultUnwrapHandle.invokeExact(result);
        }
        var errPtr = (MemorySegment) resultErrorMessageHandle.invokeExact(result);
        throw new DataFusionException(errPtr.getString(0));
    } finally {
        resultFreeHandle.invokeExact(result);
    }
}
```

This keeps function signatures clean, centralizes error handling on both sides, and is extensible — adding error codes or categories later means adding new inspector functions without changing any existing signatures.

## Async Bridging

DataFusion's core APIs are async (built on Tokio). The Rust FFI layer creates a single `tokio::runtime::Runtime` and uses `runtime.block_on()` to execute async work synchronously from FFI entry points.

Key constraint: `block_on()` panics if called from within an existing Tokio context. All FFI calls must originate from non-Tokio threads (normal Java threads are fine).

### Sync path (phase 1)

Every FFI function that does async work internally calls `runtime.block_on(async_operation)`. The calling Java thread blocks until the operation completes. This is simple and sufficient for most use cases.

### Async path (future phase)

Async support is added as **separate FFI functions** alongside the sync versions — not by modifying existing signatures. Each async variant takes a callback function pointer and user-data pointer:

```rust
fn session_sql_async(
    session: *mut SessionContext,
    sql: *const c_char,
    callback: extern "C" fn(*mut c_void, *mut DFResult),
    user_data: *mut c_void,
) -> *mut DFResult  // immediate: "submitted" or "invalid args"
```

On the Java side, the wrapper creates a Panama upcall stub that completes a `CompletableFuture`:

```java
CompletableFuture<RecordBatchReader> future = session.sqlAsync("SELECT ...");
future.thenAccept(reader -> { /* process results */ });
```

### Internal Rust structure

To avoid duplicating logic between sync and async FFI functions, the async implementation is the "real" code, and sync wrappers call `block_on()`:

```rust
// Shared async logic
async fn session_sql_impl(ctx: &SessionContext, sql: &str) -> Result<DataFrame> { ... }

// Sync FFI (phase 1): blocks the calling thread
fn session_sql(...) -> *mut DFResult { runtime.block_on(session_sql_impl(...)) }

// Async FFI (added later): spawns on Tokio, invokes callback when done
fn session_sql_async(...) { runtime.spawn(async { callback(session_sql_impl(...).await) }) }
```

This means async is a purely additive change — new FFI functions, no modifications to existing ones.

## Memory Management

Iron rule: memory allocated on one side is freed on that side.

| Object | Allocated by | Freed by | Arena type |
|--------|-------------|----------|------------|
| Tokio Runtime pointer | Rust (`Box::into_raw`) | Rust (`runtime_free`) | `Arena.global()` |
| SessionContext pointer | Rust (`Box::into_raw`) | Rust (`session_free`) | `Arena.ofShared()` |
| DataFrame pointer | Rust (`Box::into_raw`) | Rust (`dataframe_free`) | `Arena.ofShared()` |
| Error message string | Rust (`CString::into_raw`) | Rust (`error_free`) | transient |
| SQL string parameter | Java | Java (arena-scoped) | `Arena.ofConfined()` |
| ArrowArrayStream struct | Java (allocated) | Java (release callback from Rust) | `Arena.ofConfined()` |
| Arrow array buffers | Rust (via ArrowArray) | Rust (release callback) | managed by Arrow protocol |

Java wrapper classes (`DataFusionSession`, `DataFrame`) implement `AutoCloseable` and call the corresponding `*_free()` function on close. This gives deterministic cleanup via try-with-resources.

## Build Pipeline

Current: Gradle calls `cargo build --release`, copies the dylib to `build/native/`.

### Binding Generation: Hand-written + cbindgen verification

Panama `MethodHandle` declarations are written by hand in Java. The FFI surface is small and uniform (most functions follow the `(opaque_ptr, args...) -> *mut DFResult` pattern), so hand-writing is straightforward and gives full control without adding build tool dependencies.

`cbindgen` generates a C header from the Rust source as a **CI verification step** — not part of the main build. This catches drift between the Rust FFI surface and Java declarations. Any mismatch also shows up as runtime link errors in tests, providing a second safety net.

## Implementation Phases

**Phase 1: Core query path.** Runtime and session lifecycle, `session.sql()` returning results via Arrow C Data Interface, CSV/Parquet table registration. Synchronous only. This validates the entire architecture end-to-end.

**Phase 2: DataFrame API.** Expose DataFusion's DataFrame operations (filter, select, aggregate, join) as Java methods on the DataFrame wrapper. Still synchronous.

**Phase 3: Async + streaming.** Add `sqlAsync()` returning `CompletableFuture`, streaming result sets via `ArrowArrayStream` (batch-at-a-time rather than collecting all at once).

## Dependencies

### Rust side
- `datafusion` — the query engine
- `tokio` — async runtime (pulled in by datafusion)
- `arrow` — Arrow memory/types (pulled in by datafusion)

### Java side
- No external dependencies for core functionality (pure Panama APIs)
- Apache Arrow Java — optional bridge module, deferred to a later phase
- JUnit 5 (test only, already present)
