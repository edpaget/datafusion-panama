# Follow-up Items

Cleanup and improvement items identified during the Phase 1 review.

## Rust

- [ ] **Reduce `ffi_result!` boilerplate.** Every call site needs an explicit `let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr); result` two-liner because the macro can't infer the type from just `Ok(ptr)`. Add a type alias (e.g. `type FfiResult = Result<*mut c_void, Box<dyn std::error::Error>>`) or adjust the macro to coerce the return type.

- [ ] **Add clarifying comment in `session_new`.** The function takes `*mut DFRuntime`, validates it's non-null, dereferences it, then discards the reference. `SessionContext::new()` is synchronous and doesn't need the runtime. Add a comment explaining the runtime is validated but not needed for sync construction.

- [ ] **Document `dataframe_collect` re-execution behavior.** `collect()` clones the `DataFrame` (required because `DataFrame::collect()` consumes `self`). Calling `collect()` twice on the same `DFDataFrame` re-executes the query from scratch. This is correct but worth documenting in the Rust doc comment.

- [ ] **Remove `add()` smoke-test function from `lib.rs`.** It was useful for initial FFI validation but serves no purpose in the real API. Remove it along with the corresponding Java test and MethodHandle.

## Java

- [ ] **Remove dead `pointer == null` checks.** `nativePointer()` in `DataFusionRuntimeImpl`, `DataFusionSessionImpl`, and `DataFusionDataFrameImpl` checks `pointer == null || pointer.equals(MemorySegment.NULL)`. The field is initialized in the constructor and only ever set to `MemorySegment.NULL` on close, never to `null`. The `null` check is dead code.

- [ ] **`getSchema()` allocates more than necessary.** `RecordBatchReaderImpl.getSchema()` calls `getVectorSchemaRoot()` which returns the full `VectorSchemaRoot`, heavier than just a schema. Arrow Java's `ArrowReader` doesn't expose a lightweight schema accessor, so this is the pragmatic choice for now but could be revisited if a lighter path becomes available.

## DataFrame API

### Java

- [ ] **`nativePointer()` leaks on the public interface.** `DataFusionDataFrame.nativePointer()` is public but only needed internally (for `join`, `union`, etc. where one DataFrame references another's pointer). Either cast to `DataFusionDataFrameImpl` internally in those methods and remove `nativePointer()` from the interface, or add a package-private helper.

- [x] **Intermediate DataFrames in chains leak native memory.** In `df.filter("id > 1").sort("id DESC").limit(0, 2)`, the intermediate DataFrames from `filter()` and `sort()` are never closed — their native memory is never freed. Either document this clearly so users know to close intermediates, or explore a design where chained operations can track/close predecessors.

- [ ] **`count()` pointer-as-value trick is undocumented on Java side.** `DataFusionDataFrameImpl.count()` uses `valuePtr.address()` to extract the row count. This works because Rust stuffs the `usize` into the pointer field of `DFResult`, but the Java side has no comment explaining this convention.

### Rust

- [ ] **Unused `runtime` parameter in most DataFrame FFI functions.** Functions like `dataframe_filter`, `dataframe_select`, `dataframe_sort`, `dataframe_distinct`, `dataframe_aggregate`, etc. accept `runtime: *mut DFRuntime` but bind it to `_rt` (never used). Only `dataframe_collect` and `dataframe_count` need the runtime for `block_on`. Either remove the unused parameter from sync-only functions or add a comment explaining it's kept for API consistency/future-proofing.

## Concurrency Testing (Phase 3 prep)

- [ ] **Tier 1: `@RepeatedTest` + CountDownLatch concurrency tests** — In progress (see current plan).

- [ ] **Tier 2: jcstress for synchronization primitives.** Add a separate `jcstress` source set and Gradle task to stress-test the `AtomicReference`/CAS patterns guarding native pointers (millions of iterations). Proves exactly one thread observes a non-null pointer in `close()`. Requires `org.openjdk.jcstress:jcstress-core` dependency and a custom `JavaExec` task.

- [ ] **Tier 3: Lincheck model-checking.** Add JetBrains Lincheck (`@ModelCheckingTest`) for systematic interleaving exploration of wrapper classes. Works inside JUnit 5, uses bytecode manipulation to inject scheduling points. Cannot instrument native code, so only covers the Java synchronization layer.

- [ ] **Tier 4: Rust ThreadSanitizer.** Run `RUSTFLAGS="-Z sanitizer=thread" cargo +nightly test` to detect Rust-side data races that Java testing cannot reach. Complementary to Java-side concurrency tests.
