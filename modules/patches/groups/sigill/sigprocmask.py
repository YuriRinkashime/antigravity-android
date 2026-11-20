"""
sigprocmask.py - Sanitizes rt_sigprocmask calls to prevent masking SIGILL (4).

1. Routes Go runtime.rtsigprocmask via a trampoline that reads the 64-bit kernel_sigset_t,
clears bit 3 (1 << (SIGILL - 1)), writes it to a safe 16-byte aligned stack slot, and executes the syscall.
2. Sanitizes C posix_spawn sys_rt_sigprocmask from blocking SIGILL (mov x8, #-1 -> mov x8, #-9).
"""
import sys
import os
from typing import Tuple, Dict, Any

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PATCHES_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
sys.path.insert(0, os.path.join(PATCHES_ROOT, "engine"))

import byte_replacer

# Target 1: Go runtime.rtsigprocmask
TARGET_GO = bytes.fromhex("e00b80b9e10b40f9e20f40f9e32380b9e81080d2010000d4")
REPL_HEADER = bytes.fromhex("7e8dab141f2003d51f2003d51f2003d51f2003d51f2003d5")
CAVE_OFFSET = 0x818bb68
# Clean trampoline with explicit w3=8, ldrsw x0, ldr x2 to avoid stack width / garbage bugs
TRAMPOLINE = bytes.fromhex("e00b80b9e10b40f9e20f40f903018052810100b41f04007140010054290040f929f97c92ff4300d1e90300f9e1030091e81080d2010000d4ff43009103000014e81080d2010000d476725417")

# Target 2: posix_spawn / fork direct sys_rt_sigprocmask (mov x8, #-1 -> mov x8, #-9)
TARGET_POSIX_SPAWN = bytes.fromhex("08008092e1030491e2230491e0031faa03018052e88300f9e8108052010000d4")
REPL_POSIX_SPAWN = bytes.fromhex("08018092e1030491e2230491e0031faa03018052e88300f9e8108052010000d4")

def apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]:
    go_raw = byte_replacer.find_offsets(data, TARGET_GO)
    go_pat = byte_replacer.find_offsets(data, REPL_HEADER)
    ps_raw = byte_replacer.find_offsets(data, TARGET_POSIX_SPAWN)
    ps_pat = byte_replacer.find_offsets(data, REPL_POSIX_SPAWN)

    info = {
        "name": "sigprocmask",
        "expected": 2,
        "go_raw": len(go_raw),
        "go_patched": len(go_pat),
        "posix_spawn_raw": len(ps_raw),
        "posix_spawn_patched": len(ps_pat),
        "go_raw_offsets": [hex(x) for x in go_raw],
        "go_patched_offsets": [hex(x) for x in go_pat],
        "posix_spawn_raw_offsets": [hex(x) for x in ps_raw],
        "posix_spawn_patched_offsets": [hex(x) for x in ps_pat],
        "cave_offset": hex(CAVE_OFFSET)
    }

    has_go = (len(go_raw) + len(go_pat)) > 0
    has_ps = (len(ps_raw) + len(ps_pat)) > 0

    if not has_go or not has_ps:
        if allow_missing:
            info["status"] = "skipped_missing"
            return True, info
        info["status"] = "missing_signature"
        return False, info

    if verify:
        ok = (len(go_raw) == 0 and len(go_pat) >= 1 and len(ps_raw) == 0 and len(ps_pat) >= 1)
        if ok and len(data) >= CAVE_OFFSET + len(TRAMPOLINE):
            ok = (data[CAVE_OFFSET:CAVE_OFFSET + len(TRAMPOLINE)] == TRAMPOLINE)
        info["status"] = "verified" if ok else "unverified"
        return ok, info

    if len(go_raw) == 0 and len(ps_raw) == 0:
        info["status"] = "already_patched"
        return True, info

    if not dry_run:
        for off in go_raw:
            data[off:off + len(REPL_HEADER)] = REPL_HEADER
        data[CAVE_OFFSET:CAVE_OFFSET + len(TRAMPOLINE)] = TRAMPOLINE
        for off in ps_raw:
            data[off:off + len(REPL_POSIX_SPAWN)] = REPL_POSIX_SPAWN
        info["status"] = "patched"
    else:
        info["status"] = "dry_run"

    return True, info
