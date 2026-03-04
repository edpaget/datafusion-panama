# datafusion-panama

Project Panama (Foreign Function & Memory API) based Java bindings for [Apache DataFusion](https://datafusion.apache.org/).

## Prerequisites

- Java 22 (managed via asdf, see `.tool-versions`)
- Rust stable toolchain
- Gradle 9.x (wrapper included)

## Build

```sh
./gradlew build
```

This compiles the Rust cdylib (`rust/`) and the Java sources. The Rust library is built in release mode and copied to `build/native/`.

## Run

```sh
./gradlew run
```

## Tests

```sh
# Java tests (JUnit 5)
./gradlew test

# Rust tests
cd rust && cargo test
```

## Project structure

- `rust/` — Rust crate exposing C FFI functions (`cdylib`)
- `src/main/java/` — Java code using Panama's `Linker` and `SymbolLookup` to call into Rust
- `build.gradle.kts` — Orchestrates both Java and Rust builds

## Development practices

- **Test-Driven Development (TDD):** Write tests before implementation. Red-green-refactor: write a failing test that defines the desired behavior, implement the minimum code to make it pass, then refactor.
- **Assert expected values, not just existence:** Tests should verify concrete expected results (e.g. `assertEquals(42L, vec.get(0))`) rather than only checking that objects are non-null. Prefer assertions on actual data, row counts, field names, and column values. Reserve `assertNotNull` for cases where the value is genuinely opaque (e.g. native pointers with no observable behavior to test).
- **Loose coupling via interfaces:** Modules should depend on interfaces (Java interfaces or abstract types), not on concrete implementations. This applies across the Java layer boundaries — the idiomatic API layer, the raw Panama bindings, and the Arrow data consumption layer should be connected through contracts, not direct references to implementation classes.

## Architectural principles

See `IMPLEMENTATION.md` for full design rationale and API sketches. The rules below govern how new code should be written.

### FFI boundary (Rust)

- Every fallible FFI function returns `*mut DFResult` and wraps its body in `ffi_result!`. This macro calls `catch_unwind` so panics never cross the FFI boundary.
- Null pointer arguments are validated with `assert!` inside the `ffi_result!` block. The macro catches the panic and converts it to an error result.
- Every `*_free` function accepts null gracefully (check `is_null()` before `Box::from_raw`).
- One Rust module per FFI resource type (`result.rs`, `runtime.rs`, `session.rs`, `dataframe.rs`, etc.). Each module owns its FFI functions and unit tests.
- Wrapper structs (`DFRuntime`, `DFSession`, `DFDataFrame`) hold the underlying Rust types, are boxed via `Box::into_raw`, and cast to `*mut c_void` for the `DFResult` return. Use `pub(crate)` for fields that sibling modules need.

### Memory ownership

- **Iron rule:** memory allocated on one side is freed on that side. Rust allocates native objects; Rust frees them via `*_free` functions. Java allocates arenas for parameters; Java manages those arenas.
- Transient parameters passed to Rust (e.g. SQL strings) are allocated in `Arena.ofConfined()` scoped to the call.
- Long-lived native pointers (runtime, session, dataframe) are held by Java wrapper objects that implement `AutoCloseable` and call the corresponding `*_free` on `close()`.
- `close()` must be idempotent — set the pointer to `MemorySegment.NULL` after freeing. Accessing a closed object via `nativePointer()` throws `IllegalStateException`.

### Java layer conventions

- **Public interface + package-private impl:** Each native resource is exposed as a public interface (`DataFusionRuntime`, `DataFusionSession`) with a static factory method on the interface. The `*Impl` class is package-private and final.
- **MethodHandle ownership:** Each impl class declares its own `static final MethodHandle` fields for the FFI functions it calls. `NativeLibrary` provides only shared infrastructure: library loading, `downcallHandle()`, and `unwrapOrThrow()`.
- **Error handling split:** Rust-originated errors become `DataFusionException` (checked). FFI invocation failures (`Throwable` from `MethodHandle.invokeExact`) become `AssertionError` — these indicate a binding bug, not a user error.
- **No external dependencies for core:** The Java side uses only Panama APIs and the JDK. Arrow Java interop is an optional future layer.
- **Javadoc compliance:** All public API types and members must have complete Javadoc, including `@param`, `@return`, and `@throws` tags. The build enables `-Werror` on the Javadoc task, so any missing or malformed Javadoc fails the build. The `check` task and pre-commit hook both run Javadoc generation.

## Commit conventions

Use [Conventional Commits](https://www.conventionalcommits.org/). Format:

```
<type>(<scope>): <description>
```

Types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `build`, `ci`.
Scope is optional but encouraged (e.g. `rust`, `java`, `gradle`).
