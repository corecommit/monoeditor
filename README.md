<div align="center">

<br>

# `Mono`

### A C++ studio that lives in your pocket.

Write, build and run C++ entirely on your phone — no PC, no Termux, no cloud.<br>
Black, white and every shade between. Nothing else.

<br>

[![Build](https://img.shields.io/github/actions/workflow/status/corecommit/monoeditor/build.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white&label=build&labelColor=1f2328&color=2ea043)](https://github.com/corecommit/monoeditor/actions)
[![Release](https://img.shields.io/github/v/release/corecommit/monoeditor?style=for-the-badge&logo=github&logoColor=white&label=release&labelColor=1f2328&color=0969da&include_prereleases)](https://github.com/corecommit/monoeditor/releases)
[![Downloads](https://img.shields.io/github/downloads/corecommit/monoeditor/total?style=for-the-badge&logo=android&logoColor=white&label=downloads&labelColor=1f2328&color=8250df)](https://github.com/corecommit/monoeditor/releases)
[![Stars](https://img.shields.io/github/stars/corecommit/monoeditor?style=for-the-badge&logo=github&logoColor=white&labelColor=1f2328&color=e3b341)](https://github.com/corecommit/monoeditor/stargazers)

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=black)
![Arch](https://img.shields.io/badge/arch-arm64--v8a-0091BD?style=flat-square&logo=arm&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Canvas-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)
![C++](https://img.shields.io/badge/C%2B%2B-11%20to%2023-00599C?style=flat-square&logo=cplusplus&logoColor=white)
![LLVM](https://img.shields.io/badge/LLVM-clang%20%2B%20lld-262D3A?style=flat-square&logo=llvm&logoColor=white)

<br>

[**Download**](https://github.com/corecommit/monoeditor/releases) &nbsp;|&nbsp;
[**Features**](#features) &nbsp;|&nbsp;
[**Quick start**](#quick-start) &nbsp;|&nbsp;
[**Modules**](#your-own-modules) &nbsp;|&nbsp;
[**Build it**](#build-from-source) &nbsp;|&nbsp;
[**How it works**](#how-it-works)

<br>

</div>

---

## Why Mono?

Most phone "code editors" either can't compile, or hand your code to a server. Mono ships a real **Clang/LLVM toolchain inside the APK**, so pressing run compiles and runs your program on the device itself — offline, fast, and private.

And it looks like nothing else on your phone: one shade system, zero hues. Near-black layers, soft grey text, and pure white reserved for the line you're on and your caret.

<div align="center">

| | |
|:---:|:---|
| **Offline** | Compiles with Clang on-device. No internet needed after install. |
| **Fast** | Incremental builds, object cache and precompiled-header cache. |
| **Yours** | Projects are plain files — edit them from the system Files app too. |
| **Calm** | Minimal UI, monochrome highlighting, optional focus mode. |

</div>

---

## Features

<table>
<tr>
<td width="50%" valign="top">

### Editor built from scratch
No `TextField` anywhere — the whole editor is drawn on a Compose Canvas.

- Smooth caret glide with a soft blink
- Inertial fling scrolling
- Drag selection handles
- Pinch-to-zoom font size
- Line numbers that fade away from the caret
- Auto-indent, bracket pairing and matching
- Undo / redo, find & replace (with regex)
- Stays smooth on files with 10k+ lines

</td>
<td width="50%" valign="top">

### Monochrome highlighting
Keywords, types, preprocessor, strings, numbers, comments and calls are told apart only by **brightness, weight and italics** — never by colour.

### Built for thumbs
- Symbol row above the keyboard: `{ } ( ) [ ] ; : < > * & -> # " '`
- Always-visible **left / up / down / right** arrow keys (hold to repeat)
- `tab` key, plus swipe up the row for operators and `untab`
- Backspace removes a **whole indent** at once

</td>
</tr>
<tr>
<td width="50%" valign="top">

### On-device compiling
- Real Clang + lld, bundled in the app
- Content-hash object cache — unchanged code isn't rebuilt
- Precompiled-header cache (self-heals if it goes stale)
- Diagnostics as faint underlines — tap to read the message, tap in **Issues** to jump to it
- Run console with stdin, live stdout/stderr, stop button and timeout

</td>
<td width="50%" valign="top">

### Smart autocomplete
- Per-module symbol index, built in the background
- Suggestions come only from the headers and modules the file **actually includes**
- Grouped by module, with type and signature
- Curated STL index, snippets and fuzzy matching
- Clear "module not found" errors

</td>
</tr>
</table>

### More

- **Files app integration** — everything lives in `Mono/projects` and `Mono/modules`, exposed as a system Files root. Edit, rename or delete from any app; changes appear in Mono live.
- **Settings** — C++ standard (11 to 23), optimization level, extra flags, prefix header, run timeout, autosave interval, theme and storage mode.
- **Focus mode** — hides every piece of UI except your code.

---

## Quick start

1. **Install** the APK from [Releases](https://github.com/corecommit/monoeditor/releases).
2. Open Mono — the **toolchain** card at the top should say `clang ready`. On first launch it unpacks the headers, so give it a moment.
3. Tap **new project**, give it a name, and you're in `main.cpp`.
4. Tap the **run** button. Build output appears in the sliding panel (`build`, `run`, `issues`).

```cpp
#include <iostream>

int main() {
    std::cout << "Hello from my phone!\n";
}
```

> **Tip:** the three-dot menu on a project opens a small menu with *open*, *rename* and *delete*.

---

## Your own modules

Drop `.cpp` / `.h` files into the shared `modules/` folder (or use the **Modules drawer** to create, rename, delete and import files from the Files app). Every project can then use them:

```cpp
#include <mymath.h>      // works
#include <mymath.cpp>    // also works
```

Mono adds `modules/` to the compiler include path, indexes each module separately, and offers its symbols in autocomplete. Edit a module and every project that uses it is rebuilt automatically.

```
Mono/
├── projects/
│   └── <name>/
│       └── main.cpp        (+ any extra files)
└── modules/                (shared by every project)
```

---

## Build from source

**Requirements:** JDK 17 and the Android SDK (platform 34). The Gradle wrapper is included (Gradle 8.7, AGP 8.4.2, Kotlin 2.0.0).

```bash
./gradlew assembleDebug      # output: app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # release APK (debug-signed unless you add a keystore)
```

A fresh clone builds and runs **without** a compiler — the editor, modules and settings all work, and the toolchain card tells you what's missing. To get a build that can actually compile C++, provision the toolchain first:

```bash
./toolchain/setup-toolchain.sh     # downloads clang + sysroot, then rebuild
```

<details>
<summary><b>Toolchain script details</b></summary>

<br>

- Needs `curl`, `ar` (binutils), `tar`, `readelf`, `zstd`, `xz`, `zip`, `python3` — and `patchelf` when a dependency has a versioned soname.
- Environment overrides: `TERMUX_MIRROR`, `SKIP_SYSROOT=1`.
- Writes:
  - `app/src/main/jniLibs/arm64-v8a/` — compiler and its libraries
  - `app/src/main/assets/toolchain/` — `sysroot.zip`, `manifest.json`, `info.json`
  - `toolchain/work/sysroot-only.zip` — standalone archive for the in-app download (**Settings > Toolchain > URL**)

</details>

### Continuous integration

`.github/workflows/build.yml` runs on pushes to `main`, pull requests, manual dispatch and `v*` tags. It provisions and **verifies** the toolchain (the build fails rather than ship a broken one), builds a debug APK every run and a release APK on tags, signs with `KEYSTORE_BASE64` / `KEY_ALIAS` / `KEY_PASSWORD` if you set them, uploads the artifacts, and attaches the APK to a GitHub Release on tags.

---

## How it works

<details>
<summary><b>Why the compiler is packaged as <code>.so</code> files</b></summary>

<br>

Android 10+ (targetSdk 29+) refuses to execute binaries from app-writable storage. So Mono ships `clang` and `ld.lld` renamed to `libclang.so` and `libld_lld.so` in `jniLibs/arm64-v8a`. The installer extracts them to `nativeLibraryDir`, where execution is allowed. Headers, crt objects and libc++ ship as `assets/toolchain/sysroot.zip` with a sha256 manifest and are unpacked and verified on first launch.

The NDK only provides x86_64-hosted compilers, so the script pulls **aarch64-hosted Clang** (built for Android) and the bionic sysroot from the Termux package repository.

</details>

<details>
<summary><b>How programs are run</b></summary>

<br>

Built programs link against the bundled `libc++_shared.so` and load it from `nativeLibraryDir`. Because targetSdk 29+ also blocks `execve()` on app-data files, Mono launches them through `/system/bin/linker64 <binary>`, falling back to a direct exec and showing a clear console error if both fail.

</details>

<details>
<summary><b>Build pipeline</b></summary>

<br>

1. Check the toolchain, sysroot and `libc++_shared.so`.
2. Build (or reuse) a precompiled header, keyed by its contents and the compile flags.
3. Compile `main.cpp` to an object file, cached by a hash of the source, the flags **and every file on the include path**.
4. Link with lld and run.

If clang ever rejects a cached precompiled header, Mono deletes it and compiles without instead of failing your build.

</details>

### Architecture

| Module | What it does |
| :--- | :--- |
| **`:app`** | Screens — home, editor, modules drawer, output panel, settings — and session glue |
| **`:core-editor`** | Canvas editor: text buffer, undo/redo, gestures, rendering, IME bridge, completion popup, symbol row, find bar |
| **`:core-indexer`** | Pure-Kotlin C++ lexer, symbol parser, per-module index, STL index, fuzzy matcher, snippets |
| **`:core-compiler`** | Toolchain manager, incremental build engine + PCH, diagnostics parser, process runner |
| **`:core-storage`** | Project and module repository, settings, storage watcher, DocumentsProvider |

Dependencies point strictly downward: `app` depends on all modules, `editor` on `indexer`, `compiler` and `storage` on nothing, and `indexer` only on coroutines.

---

## Good to know

- Compiling targets **arm64-v8a** only — the vast majority of phones.
- The symbol parser is a fast heuristic, not a full C++ front-end, so exotic template code may index imperfectly.
- The toolchain script relies on third-party package repositories (Termux). If their layout changes, the script — and CI — fails loudly instead of shipping a broken APK.
- The bundled compiler is an Apache-2.0 / LLVM-licensed build. If you redistribute a toolchain-enabled APK, respect those licenses.

---

<div align="center">

<br>

**Mono** — made by [Equs](https://github.com/corecommit)

<sub>Code in greyscale. Think in colour.</sub>
<br>

[![Made with Kotlin](https://img.shields.io/badge/made%20with-Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Powered by LLVM](https://img.shields.io/badge/powered%20by-LLVM-262D3A?style=flat-square&logo=llvm&logoColor=white)](https://llvm.org)
</div>
