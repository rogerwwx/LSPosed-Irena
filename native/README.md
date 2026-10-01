# LSPosed Native Library (`native`)

## Purpose and Design Philosophy

This library provides low-level hooking and modification capabilities for the Android OS.
It is not a standalone application but a collection of components designed to be integrated into a larger loading mechanism, such as the Zygisk module in `../zygisk`.

## Architectural Breakdown

The library is organized into distinct modules, each with a clear responsibility.

### `core` - The Abstract Engine

This module defines the central abstractions and manages the runtime state. It's the conceptual heart of the library.

-   **`Context`**: An abstract base class that defines the injection lifecycle. It contains pure virtual methods like `LoadDex` and `SetupEntryClass`. The consumer of this library (e.g., the `LspdModule` in `../zygisk`) must inherit from `Context` and provide the concrete implementations for these steps.
-   **`ConfigBridge`**: A simple, native-side singleton that acts as a cache for configuration data (specifically, the obfuscation map) that is fetched and provided by the consumer.
-   **`native_api`**: Implements the native module support system. It works by hooking the system's `do_dlopen` function. When it detects a registered module library being loaded, it calls that library's `native_init` entry point, providing it with a set of [API](include/core/native_api.h)s for creating its own native hooks. The library names normally arrive over JNI from the injected framework, and the hook engine behind the API is Dobby, but neither is required: `SetHookBackend` lets a runtime supply its own primitives, which is how the HyperOS Rust Runtime path (see `../zygisk/src/main/jni/src/hyos_runtime.cpp`) drives this same code from a process that has no JVM at all.

### `elf` - Symbol Resolution

This module is responsible for runtime symbol lookups in shared libraries, a critical function for native hooking.

-   **`ElfImage`**: A parser for ELF files mapped into the current process's memory. It can resolve symbols in stripped binaries by locating, decompressing (using `xz-embedded`), and parsing the `.gnu_debugdata` section. It applies a cascading lookup strategy: GNU hash -> ELF hash -> linear scan of the symbol table.
-   **`ElfSymbolCache`**: A thread-safe, lazy-initialized cache for `ElfImage` instances, providing a safe way to access common libraries like `libart.so` and the `linker`.

### `jni` - The Business Logic Interface

This is the most significant module and represents the library's primary service layer. It contains a set of JNI bridges that expose the core features to the injected Java framework code. The functionality here is the main product of the native library.

-   **`jni_bridge.h`**: Provides a set of helper macros (`LSPD_NATIVE_METHOD`, `REGISTER_LSPD_NATIVE_METHODS`, etc.) to standardize and simplify the tedious process of writing JNI boilerplate.
-   **`HookBridge`**: The engine for ART method hooking. It maintains a thread-safe map of all active hooks. It also includes some stability controls, such as using atomic operations to set the backup method trampoline and throwing a Java exception instead of causing a native crash if a user tries to invoke the original method of a failed hook.
-   **`ResourcesHook`**: Provides the functionality to intercept and rewrite Android's binary XML resources on the fly. It relies on non-public structures from `libandroidfw.so` and uses the `elf` module to find the necessary function symbols at runtime.
-   **`DexParserBridge`**: Exposes a native DEX parser to the Java layer using a visitor pattern. This allows for analysis of an app's bytecode without the overhead of instantiating the entire DEX structure as Java objects.
-   **`NativeApiBridge`**: The JNI counterpart to the `core/native_api`. It exposes a method for the Java framework to register the filenames of third-party native modules.

### `common` & `framework`

-   **`common`**: A collection of basic utilities, including a `fmt`-based logging system, global constants, and helper functions.
-   **`framework`**: Contains minimal C++ structure definitions that mirror those inside Android's internal `libandroidfw.so`. These are necessary to correctly interpret resource data pointers.

## 3. Build System

The library is configured with CMake to be built as a **static library (`libnative.a`)**. All external dependencies are also linked statically for maximum portability.

## Per-app ART inline hook compatibility

Settings > Restore inline hooks opens the package selection page. It is opt-in,
applies to all Android users of a selected package on the next process start,
and does not change module scope. Shared processes necessarily share the setting.
Irena's virtual `system` target (system_server) is excluded; the real `android`
package can match `system:ui`.

The restorer is adapted from [Vector PR #921](https://github.com/JingMatrix/Vector/pull/921)
at `e3b15cf0`. Before LSPlant initialization it snapshots pre-existing executable
modifications. After framework bootstrap it restores the executable libart
segments containing tracked LSPlant hooks and reapplies that baseline, without
destroying LSPlant/Dobby metadata. ART maintenance hooks will no longer run;
this compatibility mode can break modules or crash apps.

Run `python tools/check_inline_hooks.py` with JDK 21+ and Linux g++.
On Windows run `--java-only`, then run `--native-only` in WSL. These checks cover
package/process policy, resources and native file-backed restoration. Android
builds and on-device hook/JIT/GC stability still require separate verification.
