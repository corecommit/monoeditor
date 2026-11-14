# Mono

A minimalistic on-device C++ editor and compiler for Android. One shade
system, zero hues: near-black layers, soft gray text, white reserved for the
active line and the caret.

## Features

- **Custom-rendered editor** on a Compose Canvas (no TextField anywhere):
  animated caret glide with soft blink, inertial fling scrolling, drag
  selection handles, pinch-to-zoom font size, line-number gutter that dims
  away from the caret, auto-indent, bracket pairing/matching, undo/redo,
  find/replace with regex, and efficient incremental rendering for files of
  10k+ lines.
- **Monochrome syntax highlighting** for C++ — keywords, types, preprocessor,
  strings, numbers, comments and calls distinguished only by brightness,
  weight and italics.
- **Symbol row** above the keyboard (`{ } ( ) [ ] ; : < > * & -> # " '`),
  swipe up for the extras palette (tab, arrows, operators).
- **User modules**: drop `.cpp`/`.h` files into the shared `modules/` folder
  (Modules drawer: create, rename, delete, import from the Files app) and
  use them from any project with `#include <module.h>` or
  `#include <module.cpp>` — Mono adds `modules/` to the compiler include
  path. Each module gets its own symbol index; autocomplete only offers the
  symbols of the modules (and STL headers) the current file actually
  includes, grouped by module with type and signature. Background re-index
  on change, include-guard reminders, and a clear "module not found" error.
- **Files app integration**: everything lives under `Mono/projects/<name>/`
  and `Mono/modules/` and is exposed through a DocumentsProvider (SAF root
  "Mono"), so you can browse, edit, rename and delete files from the system
  Files app or any other editor — changes show up in Mono live. Optional
  setting to work directly against a shared `Documents/Mono` tree instead.
- **On-device compiling** with a bundled Clang/LLVM toolchain: incremental
  builds (content-hash object cache), precompiled-header cache, builds on a
  background dispatcher, thin progress line, diagnostics as faint underlines
  (tap to read, tap in Issues to jump), sliding output panel, run console
  with stdin, streamed stdout/stderr, stop button and timeout.
- **Settings**: C++ standard (11–23), optimization level, extra flags,
  prefix header, run timeout, autosave interval, theme, storage mode.
- **Focus mode** hides every UI element except the code.

## Building

Requirements: JDK 17, Android SDK with platform 34. The Gradle wrapper is
included (Gradle 8.7, AGP 8.4.2, Kotlin 2.0.0).

```bash
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # release APK (debug-signed by default)
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

The app itself contains no C/C++ sources, so the Android NDK is not required
for a plain build; the CI workflow installs it anyway so forks that add JNI
code work out of the box.

## The on-device toolchain

Android 10+ (targetSdk 29+) forbids executing binaries from app-writable
storage, so Mono packages the compiler as native libraries: `clang` and
`ld.lld` are renamed `libclang.so` / `libld_lld.so`, placed in
`app/src/main/jniLibs/arm64-v8a`, and extracted by the installer into
`nativeLibraryDir`, where exec is allowed (`extractNativeLibs=true` /
`useLegacyPackaging`). Headers, crt objects and libc++ ship as
`assets/toolchain/sysroot.zip` with a sha256 manifest, unpacked and verified
on first launch.

Since the NDK only ships x86_64-hosted compilers, Mono provisions
**aarch64-hosted clang** from the Termux package repository (officially
built Android clang) plus the bionic sysroot:

```bash
./toolchain/setup-toolchain.sh    # provisions jniLibs + assets, then rebuild
```

- Needs `curl`, `ar` (binutils), `tar`, `readelf`, `zstd`, `xz`, `zip`,
  `python3`; `patchelf` additionally when a dependency carries a versioned
  soname (the script renames the file to `lib*.so` and patches every NEEDED
  reference to it).
- Environment overrides: `TERMUX_MIRROR`, `SKIP_SYSROOT=1`.
- Writes:
  - `app/src/main/jniLibs/arm64-v8a/` — compiler + dependency libraries
  - `app/src/main/assets/toolchain/` — sysroot.zip, manifest.json, info.json
  - `toolchain/work/sysroot-only.zip` — standalone archive for the in-app
    download (Settings → Toolchain → URL), useful for APKs built without
    the assets.

A fresh clone builds and runs **without** running the script — the app then
shows a clear toolchain status card (and the editor, modules and settings
work fully); compilation needs a toolchain-enabled build.

Runtime wiring (for reference): `LD_LIBRARY_PATH` and `PATH` point at
`nativeLibraryDir`; the sysroot is passed via `--sysroot`, the resource dir
via `-resource-dir`, and the linker via `--ld-path` (which is why renamed
executables still work). Modules resolve through `-I <modules>` so angle
brackets and quoted includes both find user files.

Compiled programs link against the bundled `libc++_shared.so` and run
against it from `nativeLibraryDir` (the only place targetSdk 29+ allows
executable mappings from). Because targetSdk 29+ also blocks `execve()`
on app-data files, Mono launches built programs through
`/system/bin/linker64 <binary>`, with a direct exec as the fallback and a
clear console error if both fail.

## CI

`.github/workflows/build.yml`:

- triggers: push to `main`, pull requests, `workflow_dispatch`, tags `v*`;
- Ubuntu runner, JDK 17 (Temurin), Android SDK 34 + NDK, Gradle 8.7 with
  caching, wrapper bootstrap guard;
- runs the toolchain provisioning script, which renames/patches every
  jniLibs entry to the extractable `lib*.so` form and verifies the result
  (file naming, ELF64/AArch64, dependency closure, libc++ presence,
  sysroot contents) with a printed summary — the build **fails** if the
  toolchain cannot be provisioned or verified;
- builds the debug APK on every run and a release APK on tags; signs with
  the secrets `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD` when present,
  otherwise a fresh CI-generated keystore;
- uploads APK (and toolchain archive) as artifacts; on tags, creates a
  GitHub Release with the APK attached.

## Architecture

| Module | Responsibility |
| --- | --- |
| `:app` | UI: home, editor screen, modules drawer, output panel, settings, session glue |
| `:core-editor` | Canvas editor: text buffer, undo/redo, gestures, rendering, IME bridge, completion popup, symbol row, find bar |
| `:core-indexer` | Pure-Kotlin C++ lexer, symbol parser, per-module index, curated STL index, fuzzy matcher, snippets |
| `:core-compiler` | Toolchain manager (locate/verify/extract/download), incremental build engine + PCH, diagnostics parser, process runner |
| `:core-storage` | Project/module repository (internal + SAF shared roots), settings, storage watcher, DocumentsProvider |

Dependencies point strictly downward: `app → all`, `editor → indexer`,
`compiler → nothing`, `storage → nothing`, `indexer → coroutines only`.

## Storage layout

```
Mono/
├── projects/<name>/main.cpp      (and any extra files you add)
└── modules/                      (shared, include-path for every project)
```

Internal mode lives in app storage and is exposed to the Files app through
the `Mono` SAF root; shared mode points at a user-picked `Documents/Mono`
tree. Switching does not move or delete anything, and `allowBackup` keeps
data across app updates. Autosave runs every few seconds while editing.

## Notes and limitations

- On-device compilation targets `arm64-v8a` only (the overwhelming majority
  of devices).
- The toolchain script depends on third-party package repositories
  (Termux). If their layout changes, the script fails loudly and the CI
  fails with it rather than shipping an APK with a broken toolchain.
- The symbol parser is a fast heuristic, not a full C++ front-end; template
  metaprogramming corners may index imperfectly.
- The bundled compiler binaries are Apache-2.0/LLVM-licensed builds; if you
  redistribute the toolchain-enabled APK, respect those licenses.
