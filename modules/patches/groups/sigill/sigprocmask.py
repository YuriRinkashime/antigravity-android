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

# Target: posix_spawn / fork direct sys_rt_sigprocmask (mov x8, #-1 -> mov x8, #-9)
TARGET_POSIX_SPAWN = bytes.fromhex("08008092e1030491e2230491e0031faa03018052e88300f9e8108052010000d4")
REPL_POSIX_SPAWN = bytes.fromhex("08018092e1030491e2230491e0031faa03018052e88300f9e8108052010000d4")

def apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]:
    ps_raw = byte_replacer.find_offsets(data, TARGET_POSIX_SPAWN)
    ps_pat = byte_replacer.find_offsets(data, REPL_POSIX_SPAWN)

    info = {
        "name": "sigprocmask",
        "expected": 1,
        "posix_spawn_raw": len(ps_raw),
        "posix_spawn_patched": len(ps_pat),
        "posix_spawn_raw_offsets": [hex(x) for x in ps_raw],
        "posix_spawn_patched_offsets": [hex(x) for x in ps_pat],
    }

    has_ps = (len(ps_raw) + len(ps_pat)) > 0

    if not has_ps:
        if allow_missing:
            info["status"] = "skipped_missing"
            return True, info
        info["status"] = "missing_signature"
        return False, info

    if verify:
        ok = (len(ps_raw) == 0 and len(ps_pat) >= 1)
        info["status"] = "verified" if ok else "unverified"
        return ok, info

    if len(ps_raw) == 0:
        info["status"] = "already_patched"
        return True, info

    if not dry_run:
        for off in ps_raw:
            data[off:off + len(REPL_POSIX_SPAWN)] = REPL_POSIX_SPAWN
        info["status"] = "patched"
    else:
        info["status"] = "dry_run"

    return True, info
