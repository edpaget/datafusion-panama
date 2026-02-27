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

## Commit conventions

Use [Conventional Commits](https://www.conventionalcommits.org/). Format:

```
<type>(<scope>): <description>
```

Types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `build`, `ci`.
Scope is optional but encouraged (e.g. `rust`, `java`, `gradle`).
