#!/usr/bin/env bash
#
# Mono on-device toolchain provisioning.
#
# Android 10+ (targetSdk 29+) forbids executing binaries from app-writable
# storage, so Mono packages the compiler as native libraries: the executables
# are renamed lib*.so and placed in app/src/main/jniLibs/arm64-v8a (extracted
# by the installer into nativeLibraryDir at install time, where exec is
# allowed). Headers, crt objects and the libc++ shared library are shipped as
# assets (app/src/main/assets/toolchain/) and are extracted + checksum-verified
# by the app on first launch.
#
# Where do Android-hosted (aarch64) clang binaries come from? The Android NDK
# only ships x86_64-hosted compilers, so this script pulls clang/lld and the
# bionic sysroot from the Termux package repository (packages.termux.dev),
# which distributes officially built aarch64-linux-android clang packages
# linked against bionic. Everything runs unprivileged; no root needed.
#
# The script is verification-first: every jniLibs file is renamed/patched to
# the extractable lib*.so form and the whole layout (naming, ELF architecture,
# dependency closure, libc++ presence, sysroot contents) is verified with a
# printed summary. Any problem fails the script — CI must never ship an APK
# with a silently broken toolchain.
#
# Usage:
#   ./toolchain/setup-toolchain.sh            # arm64-v8a (the only ABI)
#
# Environment overrides:
#   TERMUX_MIRROR   apt mirror root (default https://packages.termux.dev/apt/termux-main)
#   TERMUX_DIST     distribution suite       (default stable)
#   SKIP_SYSROOT=1  only provision jniLibs binaries, not the header sysroot
#   QEMU_ROOT       optional Android system tree (must contain
#                   system/bin/linker64); when qemu-aarch64-static is also
#                   installed, `clang --version` is smoke-tested under qemu
#
# Outputs:
#   app/src/main/jniLibs/arm64-v8a/lib*.so          executables + shared libs
#   app/src/main/assets/toolchain/sysroot.zip       headers, crt, libc++
#   app/src/main/assets/toolchain/manifest.json     sha256 manifest for the zip
#   app/src/main/assets/toolchain/info.json         toolchain metadata
#   toolchain/work/sysroot-only.zip                 same zip for in-app download
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${REPO_ROOT}/toolchain/work"
STAGE="${WORK}/stage"                       # scratch
ROOT_USR="${WORK}/usr"                      # merged Termux usr/ tree
SYSROOT="${WORK}/sysroot"                   # assembled NDK-style sysroot
JNILIBS="${REPO_ROOT}/app/src/main/jniLibs/arm64-v8a"
ASSETS="${REPO_ROOT}/app/src/main/assets/toolchain"

TERMUX_MIRROR="${TERMUX_MIRROR:-https://packages.termux.dev/apt/termux-main}"
TERMUX_DIST="${TERMUX_DIST:-stable}"
ARCH="aarch64"
TARGET_API="26"

# Bootstrap packages; runtime dependencies are resolved recursively from the
# Packages index, so this list only needs the top-level tools.
SEED_PACKAGES=(clang lld ndk-sysroot ndk-multilib libc++)

log() { printf '\033[1;37m[toolchain]\033[0m %s\n' "$*"; }

# Report unexpected exits (set -e firing on an unguarded command) with the
# line number, so a failure is never silent — an exit-code-1 with no message
# is undebuggable in CI logs. die() raises a flag so its own exit path is
# reported exactly once.
MONO_DIED=0
die() {
  MONO_DIED=1
  printf '\033[1;37m[toolchain]\033[0m \033[1mFAILED:\033[0m %s\n' "$*" >&2
  exit 1
}
trap '
  rc=$?
  if [ "$rc" -ne 0 ] && [ "$MONO_DIED" -eq 0 ]; then
    printf "\033[1;37m[toolchain]\033[0m \033[1mFAILED:\033[0m unexpected exit (status %s) at %s:%s\n" \
      "$rc" "${0##*/}" "$LINENO" >&2
  fi
' EXIT

require_tools() {
  local missing=()
  for t in curl ar tar readelf sha256sum awk find cp mkdir rm zip python3; do
    command -v "$t" >/dev/null 2>&1 || missing+=("$t")
  done
  command -v zcat >/dev/null 2>&1 || missing+=(zcat)
  if [ "${#missing[@]}" -gt 0 ]; then
    die "missing required tools: ${missing[*]} (on Debian/Ubuntu: sudo apt-get install -y curl binutils xz-utils zstd zip patchelf python3)"
  fi
}

fetch_packages_index() {
  mkdir -p "${WORK}"
  local idx="${WORK}/Packages.gz"
  log "Fetching package index from ${TERMUX_MIRROR} (${TERMUX_DIST}) ..."
  curl -fsSL --retry 3 -o "${idx}" \
    "${TERMUX_MIRROR}/dists/${TERMUX_DIST}/main/binary-${ARCH}/Packages.gz" \
    || die "could not download Packages.gz (check TERMUX_MIRROR override)"
  zcat "${idx}" > "${WORK}/Packages"
  log "Index: $(grep -c '^Package:' "${WORK}/Packages") packages."
}

# Prints "filename" for a package name using the downloaded index.
pkg_filename() {
  awk -v pkg="$1" '
    BEGIN { RS = "" }
    {
      name = ""
      file = ""
      n = split($0, lines, "\n")
      for (i = 1; i <= n; i++) {
        if (lines[i] ~ /^Package: /)  { name = substr(lines[i], 10) }
        if (lines[i] ~ /^Filename: /) { file = substr(lines[i], 11) }
      }
      if (name == pkg && file != "") { print file; exit }
    }
  ' "${WORK}/Packages"
}

pkg_depends() {
  awk -v pkg="$1" '
    BEGIN { RS = "" }
    {
      name = ""; dep = ""
      n = split($0, lines, "\n")
      for (i = 1; i <= n; i++) {
        if (lines[i] ~ /^Package: /)  { name = substr(lines[i], 10) }
        if (lines[i] ~ /^Depends: /)  { dep = substr(lines[i], 10) }
      }
      if (name == pkg) { print dep; exit }
    }
  ' "${WORK}/Packages"
}

# Resolve seed packages + their (first-alternative) dependency closure.
resolve_closure() {
  local -a queue=("${SEED_PACKAGES[@]}")
  local -A seen=()
  local -a ordered=()
  while [ "${#queue[@]}" -gt 0 ]; do
    local pkg="${queue[0]}"
    queue=("${queue[@]:1}")
    [ -n "${seen[$pkg]:-}" ] && continue
    if [ -z "$(pkg_filename "$pkg")" ]; then
      log "  note: package '${pkg}' not in index; skipping"
      seen[$pkg]=1
      continue
    fi
    seen[$pkg]=1
    ordered+=("$pkg")
    local deps
    deps="$(pkg_depends "$pkg")"
    if [ -n "$deps" ]; then
      # Drop version constraints, take the first of each alternative.
      local bare
      bare="$(echo "$deps" | tr ',' '\n' | sed 's/(.*//' | sed 's/|.*//' | xargs)"
      local d
      for d in $bare; do
        [ -n "$d" ] && queue+=("$d")
      done
    fi
  done
  RESOLVED_PACKAGES=("${ordered[@]}")
}

download_and_extract() {
  mkdir -p "${ROOT_USR}"
  local pkgs=("$@")
  local pkg file deb
  for pkg in "${pkgs[@]}"; do
    file="$(pkg_filename "$pkg")"
    [ -z "$file" ] && continue
    deb="${WORK}/$(basename "${file}")"
    if [ ! -f "${deb}" ]; then
      log "Downloading ${pkg} ($(basename "${file}")) ..."
      curl -fsSL --retry 3 -o "${deb}" "${TERMUX_MIRROR}/${file}" \
        || die "failed to download ${pkg}"
    fi
    # Extract data.tar.* from the .deb into a per-package dir, then merge usr/.
    local ex="${STAGE}/${pkg}"
    rm -rf "${ex}"
    mkdir -p "${ex}"
    ( cd "${ex}" && ar x "${deb}" )
    local data
    data="$(cd "${ex}" && ls data.tar.* 2>/dev/null | head -n1 || true)"
    [ -z "$data" ] && die "no data.tar.* inside ${deb}"
    case "${data}" in
      *.xz) tar -xJf "${ex}/${data}" -C "${ex}" ;;
      *.zst) tar --zstd -xf "${ex}/${data}" -C "${ex}" ;;
      *.gz) tar -xzf "${ex}/${data}" -C "${ex}" ;;
      *.bz2) tar -xjf "${ex}/${data}" -C "${ex}" ;;
      *) tar -xf "${ex}/${data}" -C "${ex}" ;;
    esac
    if [ -d "${ex}/data/data/com.termux/files/usr" ]; then
      cp -R -n "${ex}/data/data/com.termux/files/usr/." "${ROOT_USR}/" 2>/dev/null || true
    fi
  done
}

first_real_file() {
  # $1 = glob pattern under $2 = dir; resolves alternatives symlinks.
  local pattern="$1" dir="$2" f
  for f in $(cd "${dir}" 2>/dev/null && ls -1 ${pattern} 2>/dev/null || true); do
    if [ -f "${dir}/${f}" ]; then
      echo "${dir}/${f}"
      return 0
    fi
  done
  return 1
}

provision_binaries() {
  # jniLibs is fully generated — start from a clean slate so renamed or
  # dropped dependencies from earlier runs cannot linger.
  rm -rf "${JNILIBS}"
  mkdir -p "${JNILIBS}"

  local clang ld
  clang="$(first_real_file 'clang-[0-9]*' "${ROOT_USR}/bin")" \
    || die "clang binary not found in extracted packages"
  # Termux's lld package ships only unversioned names (ld.lld -> lld), so the
  # versioned probe may legitimately find nothing. The trailing `|| true`
  # keeps set -e from terminating the script silently on that miss — the
  # unversioned fallback below then resolves ld.lld, and the final check
  # reports a genuine absence with a clear message.
  ld="$(first_real_file 'ld.lld-[0-9]*' "${ROOT_USR}/bin" || true)"
  if [ -z "$ld" ]; then
    # ld.lld is sometimes an unversioned symlink/file.
    if [ -f "${ROOT_USR}/bin/ld.lld" ]; then
      ld="$(readlink -f "${ROOT_USR}/bin/ld.lld")"
    fi
  fi
  [ -n "$ld" ] && [ -f "$ld" ] || die "ld.lld binary not found in extracted packages"

  log "clang:  ${clang#${ROOT_USR}/}"
  log "ld.lld: ${ld#${ROOT_USR}/}"

  cp "${clang}" "${JNILIBS}/libclang.so"
  cp "${ld}" "${JNILIBS}/libld_lld.so"

  # libc++_shared.so must ship in jniLibs no matter what the compiler
  # binaries themselves depend on: compiled programs link against it and,
  # on targetSdk 29+, shared libraries can only be mapped executable from
  # nativeLibraryDir. The sysroot copy alone is not enough at run time.
  local cxxsrc=""
  local cand
  for cand in "${ROOT_USR}/lib/libc++_shared.so" "${ROOT_USR}/lib64/libc++_shared.so"; do
    if [ -e "${cand}" ]; then cxxsrc="${cand}"; break; fi
  done
  if [ -z "${cxxsrc}" ]; then
    die "libc++_shared.so not found in the extracted packages (did the 'libc++' package install?)"
  fi
  cp -f "${cxxsrc}" "${JNILIBS}/libc++_shared.so"

  # Walk the NEEDED closure of both binaries and copy every dependency into
  # jniLibs. LD_LIBRARY_PATH=nativeLibraryDir makes the dynamic linker find
  # them at runtime. Names that do not match lib*.so are fixed up afterwards
  # by sanitize_jnilibs.
  local -a queue=("${JNILIBS}/libclang.so" "${JNILIBS}/libld_lld.so" "${JNILIBS}/libc++_shared.so")
  # Two distinct sets: `scanned` guards the readelf walk (each library's
  # NEEDED entries are inspected exactly once) and `bundled` guards the
  # search/copy (each soname is resolved once even when several libraries
  # need it). Conflating them truncates the walk at depth one — a dependency
  # marked at queue time would be skipped at dequeue and its own NEEDED
  # entries (e.g. libLLVM.so -> libffi.so, libxml2.so.16) never followed.
  local -A scanned=()
  local -A bundled=([libclang.so]=1 [libld_lld.so]=1 [libc++_shared.so]=1)
  while [ "${#queue[@]}" -gt 0 ]; do
    local so="${queue[0]}"
    queue=("${queue[@]:1}")
    local base
    base="$(basename "${so}")"
    [ -n "${scanned[${base}]:-}" ] && continue
    scanned[$base]=1
    local needed
    needed="$(readelf -d "${so}" 2>/dev/null | awk '/NEEDED/ { gsub(/[][]/,"",$NF); print $NF }' || true)"
    local n
    for n in $needed; do
      case "$n" in
        libc.so|libc.so.*|libm.so|libm.so.*|libdl.so|libdl.so.*|librt.so*|ld-android.so|linker64) continue ;;
      esac
      if [ -n "${bundled[$n]:-}" ]; then continue; fi
      bundled[$n]=1
      local found=""
      local cand2
      for cand2 in "${ROOT_USR}/lib/${n}" "${ROOT_USR}/lib64/${n}"; do
        if [ -e "$cand2" ]; then found="$cand2"; break; fi
      done
      if [ -z "$found" ]; then
        # try a versionless match (libz.so.1 -> libz.so*)
        local hit
        hit="$(find "${ROOT_USR}/lib" -maxdepth 1 -name "${n%%.*}*" -print -quit 2>/dev/null || true)"
        [ -n "$hit" ] && found="$hit"
      fi
      if [ -n "$found" ]; then
        cp -f "${found}" "${JNILIBS}/${n}"
        queue+=("${JNILIBS}/${n}")
      else
        log "  warning: NEEDED library '${n}' not found in packages (may be provided by the system)"
      fi
    done
  done

  log "jniLibs provisioned: $(ls -1 "${JNILIBS}" | wc -l) files."
}

# Android's installer only extracts APK lib/<abi>/ entries whose names match
# lib*.so into nativeLibraryDir — everything else silently stays inside the
# APK. Termux sonames are almost always unversioned and already conform, but
# when a versioned one shows up (libz.so.1) the file is renamed to a
# conforming name and every NEEDED entry that references it is patched with
# patchelf; otherwise on-device exec would fail with "library not found".
conform_name() {
  # $1: original soname/file name; prints a lib*.so-conforming name.
  local n="$1"
  case "$n" in
    lib*.so) printf '%s\n' "$n"; return 0 ;;
  esac
  local stem="$n" ver=""
  case "$n" in
    *.so.*)
      stem="${n%%.so*}"
      ver="${n#*.so}"; ver="${ver#.}"
      ;;
    *.so)
      stem="${n%.so}"
      ;;
  esac
  case "$stem" in
    lib*) ;;
    *) stem="lib${stem}" ;;
  esac
  if [ -n "$ver" ]; then
    printf '%s-%s.so\n' "$stem" "$ver"
  else
    printf '%s.so\n' "$stem"
  fi
}

sanitize_jnilibs() {
  local -a offenders=()
  local f base
  for f in "${JNILIBS}"/*; do
    [ -f "$f" ] || continue
    base="$(basename "$f")"
    case "$base" in
      lib*.so) continue ;;
    esac
    offenders+=("$base")
  done
  if [ "${#offenders[@]}" -eq 0 ]; then return 0; fi

  command -v patchelf >/dev/null 2>&1 \
    || die "jniLibs needs [${offenders[*]}] renamed to the lib*.so form but patchelf is not installed (Debian/Ubuntu: sudo apt-get install -y patchelf)"

  local from to elf
  for from in "${offenders[@]}"; do
    to="$(conform_name "$from")"
    if [ -e "${JNILIBS}/${to}" ]; then
      die "conforming-name collision: ${from} -> ${to}, but ${to} already exists"
    fi
    log "renaming ${from} -> ${to} (patching NEEDED references)"
    mv -f "${JNILIBS}/${from}" "${JNILIBS}/${to}"
    for elf in "${JNILIBS}"/*; do
      [ -f "$elf" ] || continue
      if readelf -d "$elf" 2>/dev/null | grep -Fq "Shared library: [${from}]"; then
        patchelf --replace-needed "$from" "$to" "$elf" \
          || die "patchelf --replace-needed failed on ${elf}"
      fi
    done
    patchelf --set-soname "$to" "${JNILIBS}/${to}" 2>/dev/null || true
  done
}

# Verification of the generated jniLibs. Every check is fatal: a tree that
# would not work on-device must fail the build here, not in the user's hands.
# Prints a summary at the end; clang itself is smoke-tested under qemu only
# when qemu-aarch64-static + QEMU_ROOT are available (bionic's linker64 does
# not exist on a plain Linux host, so readelf checks are the authoritative
# verification everywhere else).
verify_jnilibs() {
  local -a files=()
  local f
  for f in "${JNILIBS}"/*; do
    [ -f "$f" ] && files+=("$f")
  done
  if [ "${#files[@]}" -eq 0 ]; then
    die "jniLibs is empty — nothing to package"
  fi

  local -A known=()
  local failures=0 total_bytes=0

  # 1. Installer extraction: every file name must match lib*.so.
  for f in "${files[@]}"; do
    local base
    base="$(basename "$f")"
    if [[ "$base" == lib*.so ]]; then
      known["$base"]=1
    else
      log "  FAIL  name:   ${base} does not match lib*.so (would not be extracted by the installer)"
      failures=$((failures + 1))
    fi
  done

  # 2. Architecture: every file must be ELF64 / AArch64.
  for f in "${files[@]}"; do
    local hdr
    hdr="$(readelf -h "$f" 2>/dev/null || true)"
    if ! grep -q 'Class:.*ELF64' <<<"$hdr" || ! grep -q 'Machine:.*AArch64' <<<"$hdr"; then
      log "  FAIL  format: $(basename "$f") is not an ELF64/AArch64 object"
      failures=$((failures + 1))
    fi
  done

  # 3. Dependencies: every NEEDED entry must resolve to a system library or
  #    to another bundled file (post-rename).
  for f in "${files[@]}"; do
    local needed n
    needed="$(readelf -d "$f" 2>/dev/null | awk '/NEEDED/ { gsub(/[][]/,"",$NF); print $NF }' || true)"
    for n in $needed; do
      case "$n" in
        libc.so|libc.so.*|libm.so|libm.so.*|libdl.so|libdl.so.*|librt.so*|ld-android.so|linker64) continue ;;
      esac
      if [ -z "${known[$n]:-}" ]; then
        log "  FAIL  dep:    $(basename "$f") needs ${n}, which is neither bundled nor a system library"
        failures=$((failures + 1))
      fi
    done
  done

  # 4. The shared libc++ runtime must be bundled: compiled programs run
  #    against it from nativeLibraryDir.
  if [ -z "${known[libc++_shared.so]:-}" ]; then
    log "  FAIL  libc++: libc++_shared.so is not in jniLibs"
    failures=$((failures + 1))
  fi

  if [ "$failures" -ne 0 ]; then
    die "jniLibs verification failed (${failures} problem(s) found)"
  fi

  for f in "${files[@]}"; do
    total_bytes=$(( total_bytes + $(wc -c < "$f") ))
  done

  log "-------- jniLibs verification summary --------"
  log "  files:        ${#files[@]}"
  log "  total size:   $(( total_bytes / 1024 / 1024 )) MB ($(( total_bytes / 1024 )) KiB)"
  log "  naming:       all files match lib*.so (extractable by the installer)"
  log "  format:       all files are ELF64 / AArch64"
  log "  libc++:       libc++_shared.so bundled"
  log "  dependencies: all NEEDED entries resolve (system or bundled)"

  # Optional smoke test — informational only, never fatal.
  if command -v qemu-aarch64-static >/dev/null 2>&1 && [ -n "${QEMU_ROOT:-}" ] \
     && [ -x "${QEMU_ROOT}/system/bin/linker64" ]; then
    log "  qemu:         running clang --version (best effort)..."
    if LD_LIBRARY_PATH="${JNILIBS}" qemu-aarch64-static -L "${QEMU_ROOT}" \
        "${JNILIBS}/libclang.so" --version >/dev/null 2>&1; then
      log "  qemu:         clang --version ran successfully"
    else
      log "  qemu:         clang --version could not run (informational only)"
    fi
  else
    log "  qemu:         skipped (no qemu-aarch64-static/QEMU_ROOT; readelf checks above are authoritative)"
  fi
  log "------------------------------------------------"
}

assemble_sysroot() {
  rm -rf "${SYSROOT}"
  mkdir -p "${SYSROOT}/usr/include" \
           "${SYSROOT}/usr/lib/aarch64-linux-android/${TARGET_API}" \
           "${SYSROOT}/resource"

  # Headers: bionic (ndk-sysroot) + libc++ (libc++ package) + clang builtins.
  if [ -d "${ROOT_USR}/include" ]; then
    cp -R "${ROOT_USR}/include/." "${SYSROOT}/usr/include/"
  fi
  local clangres=""
  local d
  # Termux layout: usr/lib/clang/<major>/include (depth 3 from lib/, so a
  # shallow find cannot see it). Probe the known shape first, then fall back
  # to a find deep enough to cover both the major-only and full-version
  # layouts. BuildEngine passes -resource-dir=<sysroot>/resource, and without
  # these headers every on-device compile dies on <stddef.h> & friends.
  for d in "${ROOT_USR}/lib/clang/"*/include; do
    if [ -d "$d" ]; then clangres="$d"; break; fi
  done
  if [ -z "$clangres" ]; then
    clangres="$(find "${ROOT_USR}/lib" -maxdepth 4 -type d -path '*clang*' -name include -print -quit 2>/dev/null || true)"
  fi
  if [ -n "$clangres" ] && [ -d "$clangres" ]; then
    mkdir -p "${SYSROOT}/resource/include"
    cp -R "${clangres}/." "${SYSROOT}/resource/include/"
    log "clang resource headers: ${clangres#${ROOT_USR}/}"
  else
    log "warning: clang builtin headers (resource dir) not found"
  fi

  # crt objects: from ndk-multilib. Probe known layouts (Termux keeps them
  # under usr/aarch64-linux-android/lib, NDK-style sysroots under
  # usr/lib/aarch64-linux-android) before falling back to a global find.
  local crt_src=""
  for d in "${ROOT_USR}/lib/aarch64-linux-android" "${ROOT_USR}/aarch64-linux-android/lib" "${ROOT_USR}/lib"; do
    if [ -f "${d}/crtbegin_dynamic.o" ]; then crt_src="$d"; break; fi
  done
  if [ -z "$crt_src" ]; then
    crt_src="$(dirname "$(find "${ROOT_USR}" -name crtbegin_dynamic.o -print -quit 2>/dev/null || true)")"
  fi
  [ -n "$crt_src" ] && [ -f "${crt_src}/crtbegin_dynamic.o" ] \
    || die "crtbegin_dynamic.o not found (ndk-multilib layout changed?)"
  local o
  for o in crtbegin_dynamic.o crtbegin_static.o crtend_android.o; do
    if [ -f "${crt_src}/$o" ]; then
      cp -f "${crt_src}/$o" "${SYSROOT}/usr/lib/aarch64-linux-android/${TARGET_API}/"
      cp -f "${crt_src}/$o" "${SYSROOT}/usr/lib/aarch64-linux-android/"
    fi
  done

  # libc++_shared.so for linking against. Without it, every -lc++_shared
  # link fails — this is a hard requirement, not a warning.
  local cxx=""
  local c
  for c in "${ROOT_USR}/lib/libc++_shared.so" "${JNILIBS}/libc++_shared.so"; do
    if [ -e "$c" ]; then cxx="$c"; break; fi
  done
  if [ -z "$cxx" ]; then
    die "libc++_shared.so not found — cannot assemble a linkable sysroot"
  fi
  cp -f "$cxx" "${SYSROOT}/usr/lib/aarch64-linux-android/${TARGET_API}/"
  cp -f "$cxx" "${SYSROOT}/usr/lib/aarch64-linux-android/"
  log "sysroot assembled at ${SYSROOT}"
}

# Verifies the assembled sysroot contains everything the driver needs.
# Fatal on anything missing — otherwise the failure would only surface
# on-device as confusing clang/linker errors.
verify_sysroot() {
  local failures=0
  local inc="${SYSROOT}/usr/include"
  local libdir="${SYSROOT}/usr/lib/aarch64-linux-android"
  local apidir="${libdir}/${TARGET_API}"

  if [ ! -f "${inc}/stdio.h" ]; then
    log "  FAIL  headers: bionic headers missing (${inc}/stdio.h)"
    failures=$((failures + 1))
  fi
  if [ ! -d "${inc}/c++/v1" ]; then
    log "  FAIL  headers: libc++ headers missing (${inc}/c++/v1)"
    failures=$((failures + 1))
  fi
  if [ ! -f "${libdir}/crtbegin_dynamic.o" ] && [ ! -f "${apidir}/crtbegin_dynamic.o" ]; then
    log "  FAIL  crt:     crtbegin_dynamic.o missing"
    failures=$((failures + 1))
  fi
  if [ ! -f "${libdir}/crtend_android.o" ] && [ ! -f "${apidir}/crtend_android.o" ]; then
    log "  FAIL  crt:     crtend_android.o missing"
    failures=$((failures + 1))
  fi
  if [ ! -f "${libdir}/libc++_shared.so" ] && [ ! -f "${apidir}/libc++_shared.so" ]; then
    log "  FAIL  libc++:  libc++_shared.so missing from sysroot"
    failures=$((failures + 1))
  fi
  if [ ! -f "${SYSROOT}/resource/include/stddef.h" ]; then
    log "  FAIL  builtins: clang resource headers missing (sysroot/resource/include/stddef.h)"
    failures=$((failures + 1))
  fi

  if [ "$failures" -ne 0 ]; then
    die "sysroot verification failed (${failures} problem(s) found)"
  fi

  local n_files
  n_files="$(find "${SYSROOT}" -type f | wc -l)"
  log "-------- sysroot verification summary --------"
  log "  files:        ${n_files}"
  log "  headers:      bionic + libc++ (usr/include/c++/v1) present"
  log "  builtins:     clang resource headers present (resource/include)"
  log "  crt objects:  present in usr/lib/aarch64-linux-android (flat + per-API)"
  log "  libc++:       libc++_shared.so present"
  log "------------------------------------------------"
}

write_manifest() {
  # manifest.json maps every file inside the zip to its sha256.
  python3 - "${SYSROOT}" "${SYSROOT}/../manifest.json" <<'PY'
import hashlib, json, os, sys
root, out = sys.argv[1], sys.argv[2]
entries = []
for dirpath, _, files in os.walk(root):
    for name in files:
        p = os.path.join(dirpath, name)
        rel = os.path.relpath(p, root)
        h = hashlib.sha256()
        with open(p, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 16), b""):
                h.update(chunk)
        entries.append({"path": rel, "sha256": h.hexdigest(), "size": os.path.getsize(p)})
entries.sort(key=lambda e: e["path"])
with open(out, "w") as f:
    json.dump(entries, f, indent=1)
print(f"[toolchain] manifest: {len(entries)} files")
PY
}

package_assets() {
  mkdir -p "${ASSETS}"
  # sysroot.zip contains sysroot/ + manifest.json at its root.
  ( cd "${WORK}" && rm -f sysroot.zip && zip -qr sysroot.zip sysroot manifest.json )
  cp -f "${WORK}/sysroot.zip" "${ASSETS}/sysroot.zip"
  cp -f "${WORK}/manifest.json" "${ASSETS}/manifest.json"

  local clangv
  clangv="$(ls -1 "${ROOT_USR}/bin" | grep -E '^clang-[0-9]' | head -n1 || echo clang)"
  cat > "${ASSETS}/info.json" <<EOF
{
  "target": "aarch64-unknown-linux-android",
  "api": ${TARGET_API},
  "compiler": "termux/${clangv}",
  "binaries": ["libclang.so", "libld_lld.so"],
  "abi": "arm64-v8a"
}
EOF

  # Standalone archive used by the in-app first-launch download (headers only;
  # executables must be baked into the APK through jniLibs).
  ( cd "${WORK}" && rm -f sysroot-only.zip && zip -qr sysroot-only.zip sysroot manifest.json )
  log "assets written to ${ASSETS}"
}

main() {
  require_tools
  fetch_packages_index
  resolve_closure
  log "Resolved ${#RESOLVED_PACKAGES[@]} packages: ${RESOLVED_PACKAGES[*]}"
  download_and_extract "${RESOLVED_PACKAGES[@]}"
  provision_binaries
  sanitize_jnilibs
  # The .deb payloads carry assorted modes (some 0600); everything in
  # nativeLibraryDir must be executable/loadable on device — normalise.
  chmod 0755 "${JNILIBS}"/*
  verify_jnilibs
  if [ "${SKIP_SYSROOT:-0}" != "1" ]; then
    assemble_sysroot
    write_manifest
    package_assets
    verify_sysroot
  fi
  log "Toolchain provisioned and verified. Rebuild the APK to bundle it."
}

main "$@"
