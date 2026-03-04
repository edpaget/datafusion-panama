# Implementation Design: datafusion-panama

Panama (Foreign Function & Memory API) based Java bindings for Apache DataFusion.

## Architecture

The hybrid architecture: a **custom Rust C-shim** handles control flow (session lifecycle, query dispatch, UDF registration, configuration) while the **Arrow C Data Interface** handles data exchange (zero-copy result transfer between Rust and Java).

This mirrors datafusion-python's architecture (PyO3 for control, Arrow C Data Interface for zero-copy via `__arrow_c_stream__`), but with Panama's lower call overhead (~5ns vs PyO3).

```
┌─────────────────────────────────────────────────┐
│  Java API Layer                                  │
│  core/   — DataFusionRuntime / Session / ...     │
│  ext-*/* — Extension modules (optional JARs)     │
├─────────────────────────────────────────────────┤
│  Raw Panama Bindings (hand-written MethodHandles) │
│  Core symbols always present; extension symbols   │
│  present only when Rust feature is enabled        │
├──────────────────────┬──────────────────────────┤
│  Control Plane       │  Data Plane              │
│  Panama downcalls    │  Arrow C Data Interface  │
├──────────────────────┴──────────────────────────┤
│  Rust cdylib (extern "C" surface)               │
│  Core modules + feature-gated extension modules  │
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

### Arrow Data Consumption

The data plane uses the Arrow C Data Interface for zero-copy result transfer. On the Java side, `RecordBatchReader` uses Arrow Java's `arrow-c-data` module to import `ArrowArrayStream` structs populated by Rust. The flow:

1. Java allocates an `ArrowArrayStream` via `ArrowArrayStream.allocateNew(allocator)`
2. Rust's `dataframe_collect` populates the stream struct via Panama downcall
3. `Data.importArrayStream(allocator, stream)` produces an `ArrowReader` — zero-copy via `ForeignAllocation`
4. Users access typed columns via Arrow Java's `FieldVector` subclasses (`BigIntVector`, `Float8Vector`, `VarCharVector`, `BitVector`, etc.)

Each `RecordBatchReader` owns its own `RootAllocator`, closed when the reader closes. The low-level Panama struct layouts (`ArrowArrayStreamLayout`, `ArrowArrayLayout`, `ArrowSchemaLayout`) remain in the codebase for debugging and potential future lower-level access.

## Rust FFI Layer

The Rust crate (`rust/`) is a `cdylib` that exposes `extern "C"` functions. Each function that can fail returns a `*mut DFResult` (see Error Handling below). Each function body is wrapped in `catch_unwind` to prevent panics from crossing the FFI boundary.

Rough grouping of FFI functions:

| Category | Functions |
|----------|-----------|
| Runtime  | `runtime_new`, `runtime_free` |
| Session  | `session_new`, `session_free`, `session_sql`, `session_register_csv`, `session_register_parquet` |
| DataFrame | `dataframe_collect`, `dataframe_free` |
| Result   | `result_is_ok`, `result_unwrap`, `result_error_message`, `result_free` |
| Extensions | `enabled_features`; per-extension registration functions (feature-gated) |

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

## Extension Architecture

Extensions are purely additive. They add FFI symbols and register providers (catalogs, table factories, optimizer rules) into the existing `SessionContext`. Core types (`DFResult`, `DFRuntime`, `DFSession`, `DFDataFrame`) are never modified by extensions.

### Rust side — Cargo features

Each extension is a Cargo feature flag that gates an optional dependency and a `mod` declaration. Feature-gated modules live in `rust/src/` alongside core modules (or in subdirectories for extensions with multiple sub-features).

Extension FFI functions take `*mut DFRuntime` and `*mut DFSession` as their first arguments and register catalogs/providers/rules into the session's `SessionContext`. They follow the same `-> *mut DFResult` + `ffi_result!` pattern as core.

`Cargo.toml` feature pattern:

```toml
[features]
default = []
ext-foo = ["dep:some-crate"]
ext-bar = ["dep:another-crate"]
all-extensions = ["ext-foo", "ext-bar"]

[dependencies]
some-crate = { version = "...", optional = true }
another-crate = { version = "...", optional = true }
```

`lib.rs` conditional compilation pattern:

```rust
#[cfg(feature = "ext-foo")]
mod foo;
#[cfg(feature = "ext-foo")]
pub use foo::*;
```

### Java side — Gradle subprojects

Each extension is a separate Gradle subproject (e.g., `ext-foo/`) that depends on `:core`. Extension modules provide their own `MethodHandle` lookups via `NativeLibrary.LOOKUP` and their own Java API types (following the same public-interface + package-private-impl pattern as core).

Extensions use `NativeLibrary.LOOKUP.find(name)` which returns `Optional<MemorySegment>`. If the symbol is absent (Rust built without that feature), the lookup returns empty. Extension modules check this at load time and throw `UnsupportedOperationException` with a message naming the missing Cargo feature.

Users add only the Gradle dependencies they need:

```kotlin
dependencies {
    implementation(project(":core"))
    implementation(project(":ext-foo"))  // optional
}
```

### Feature manifest

A core FFI function `enabled_features() -> *const c_char` returns a comma-separated list of features compiled into the native library. Java's `NativeLibrary` exposes this as `NativeLibrary.enabledFeatures() -> Set<String>`. Extensions can check this at load time for clear diagnostics.

### Naming convention

Cargo features and Gradle subprojects use the `ext-` prefix for third-party DataFusion ecosystem integrations (e.g., `ext-iceberg`, `ext-delta`, `ext-federation`). This distinguishes them from core modules (`core`, `jdbc`).

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

### Cargo feature selection

The Gradle `buildRust` task accepts a `rustFeatures` project property that maps to `cargo build --features`:

```sh
./gradlew build -PrustFeatures=ext-iceberg,ext-delta
```

Default (no property): builds core only, smallest binary. An `all-extensions` feature is provided for convenience:

```sh
./gradlew build -PrustFeatures=all-extensions
```

## Implementation Phases

**Phase 1: Core query path.** Runtime and session lifecycle, `session.sql()` returning results via Arrow C Data Interface, CSV/Parquet table registration. Synchronous only. This validates the entire architecture end-to-end.

**Phase 2: DataFrame API.** Expose DataFusion's DataFrame operations (filter, select, aggregate, join) as Java methods on the DataFrame wrapper. Still synchronous.

**Phase 3: Async + streaming.** Add `sqlAsync()` returning `CompletableFuture`, streaming result sets via `ArrowArrayStream` (batch-at-a-time rather than collecting all at once).

**Phase 4: Minimal JDBC adapter.** A thin JDBC driver that wraps the DataFusion session, enabling any JDBC-compatible tool (DBeaver, DataGrip, BI tools, JDBC-based ORMs) to query DataFusion.

Scope — implement the minimum subset of JDBC interfaces needed for read-only SQL:

| Interface | Key methods | Notes |
|-----------|------------|-------|
| `java.sql.Driver` | `connect`, `acceptsURL` | URL scheme: `jdbc:datafusion:` with path to data dir or `:memory:` |
| `Connection` | `createStatement`, `prepareStatement`, `getMetaData`, `close` | Wraps `DataFusionSession`. Read-only; `setAutoCommit`/`commit`/`rollback` are no-ops. |
| `Statement` | `executeQuery`, `execute`, `getResultSet`, `close` | Delegates to `session.sql()` |
| `PreparedStatement` | `setString`, `setInt`, `setLong`, `setDouble`, `executeQuery` | Parameter substitution via string interpolation initially; parameterized queries later if DataFusion adds support. |
| `ResultSet` | `next`, `getString`, `getInt`, `getLong`, `getDouble`, `getFloat`, `getBoolean`, `wasNull`, `getMetaData`, `close` | Backed by the Arrow `RecordBatchReader` from Phase 1. Iterates row-by-row across batches. |
| `ResultSetMetaData` | `getColumnCount`, `getColumnName`, `getColumnType`, `getColumnTypeName` | Maps Arrow schema fields to `java.sql.Types` |
| `DatabaseMetaData` | `getTables`, `getColumns`, `getSchemas`, `getCatalogs`, `getTypeInfo` | Queries DataFusion's `information_schema`. Enables tool auto-discovery of tables/columns. |

Design decisions:
- **Service-provider registration:** `META-INF/services/java.sql.Driver` so `DriverManager.getConnection()` works automatically.
- **Arrow-to-JDBC type mapping:** `Int8`→`TINYINT`, `Int16`→`SMALLINT`, `Int32`→`INTEGER`, `Int64`→`BIGINT`, `Float32`→`REAL`, `Float64`→`DOUBLE`, `Utf8`→`VARCHAR`, `Boolean`→`BOOLEAN`, `Date32`→`DATE`, `Timestamp`→`TIMESTAMP`. Unmapped types fall back to `getString()`.
- **Row cursor over batches:** `ResultSet` internally holds a `RecordBatchReader` and tracks current batch + row index. `next()` advances within the current batch, fetching the next batch when exhausted.
- **Read-only:** `executeUpdate` throws `SQLFeatureNotSupportedException`. Transaction methods are no-ops. `ResultSet` is `TYPE_FORWARD_ONLY`, `CONCUR_READ_ONLY`.
- **Unsupported methods:** Throw `SQLFeatureNotSupportedException` with a descriptive message. This is standard practice for minimal JDBC drivers.

Connection URL format:
```
jdbc:datafusion:/path/to/data       # file-backed, registers directory as tables
jdbc:datafusion::memory:            # in-memory session
jdbc:datafusion:?key=value          # with configuration properties
```

Sketch of usage:
```java
try (var conn = DriverManager.getConnection("jdbc:datafusion::memory:")) {
    try (var stmt = conn.createStatement()) {
        stmt.execute("CREATE EXTERNAL TABLE t STORED AS CSV LOCATION 'data.csv'");
    }
    try (var stmt = conn.createStatement();
         var rs = stmt.executeQuery("SELECT * FROM t WHERE x > 10")) {
        while (rs.next()) {
            System.out.println(rs.getString("name") + ": " + rs.getInt("x"));
        }
    }
}
```

This phase depends only on Phase 1 (core query path) — it wraps the existing session and result-reading infrastructure behind standard JDBC interfaces. No new Rust FFI functions are needed.

**Phase 5: Extension architecture.** Implement the extension infrastructure: `enabled_features` FFI function, `NativeLibrary.enabledFeatures()` on the Java side, Gradle `rustFeatures` property, and one example extension module as a proving ground for the pattern. This validates the feature-gating, symbol probing, and build coordination before building real extensions.

## Dependencies

### Rust side
- Core: `datafusion`, `tokio`, `arrow` (unchanged)
- Extensions: each declared as `optional = true` in `[dependencies]`, gated by its feature flag

### Java side
- Core: Arrow Java (`arrow-vector`, `arrow-c-data`, `arrow-memory-unsafe`) for zero-copy C Data Interface import
- Extension modules: no external Java dependencies — they only add Panama bindings for the feature-gated FFI symbols. The heavy lifting is in Rust.
- JUnit 5 (test only, already present)
