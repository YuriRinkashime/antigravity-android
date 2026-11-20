#!/usr/bin/env bash
# build.sh - compile liblse_compat.so targeting baseline ARMv8.0-A
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CC="${CC:-clang}"
BUILD_SHA="$(git -C "$DIR" rev-parse --short HEAD 2>/dev/null || echo "unknown")"
CFLAGS="${CFLAGS:--march=armv8-a -mno-outline-atomics -O2 -Wall -fPIC -shared -DLSE_COMPAT_BUILD=\"$BUILD_SHA\"}"

"$CC" $CFLAGS "$DIR/liblse_compat.c" -o "$DIR/liblse_compat.so" -ldl

OBJDUMP_BIN="${OBJDUMP:-$(command -v aarch64-linux-gnu-objdump || command -v llvm-objdump || echo objdump)}"
DISASM="$("$OBJDUMP_BIN" -d "$DIR/liblse_compat.so")" || {
    echo "ERROR: Failed to disassemble liblse_compat.so with $OBJDUMP_BIN" >&2
    exit 1
}
if [ -z "$DISASM" ]; then
    echo "ERROR: Disassembly output was empty for liblse_compat.so" >&2
    exit 1
fi
if printf '%s\n' "$DISASM" | grep -qE '\b(cas|swp|ldadd|stadd|ldclr|stclr|ldeor|steor|ldset|stset|ldsmax|ldsmin|ldumax|ldumin)'; then
    echo "ERROR: LSE instructions detected in liblse_compat.so" >&2
    exit 1
fi

cp -f "$DIR/liblse_compat.so" "$DIR/../../modules/core/glibc/liblse_compat.so"
echo "Built: $DIR/liblse_compat.so (build=$BUILD_SHA) and synced to modules/core/glibc/liblse_compat.so"
