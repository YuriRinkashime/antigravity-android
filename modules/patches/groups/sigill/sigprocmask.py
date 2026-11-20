"""
sigprocmask.py - Sanitizes Go runtime.rtsigprocmask calls to prevent masking SIGILL (4).

Routes rt_sigprocmask via a trampoline that reads the 64-bit kernel_sigset_t, clears bit 3
(1 << (SIGILL - 1)), writes it to a safe 16-byte aligned stack slot, and executes the syscall.
"""
import sys
import os
from typing import Tuple, Dict, Any

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PATCHES_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
sys.path.insert(0, os.path.join(PATCHES_ROOT, "engine"))

import byte_replacer

TARGET = bytes.fromhex("e00b80b9e10b40f9e20f40f9e32380b9e81080d2010000d4")
REPL_HEADER = bytes.fromhex("7e8dab141f2003d51f2003d51f2003d51f2003d51f2003d5")
CAVE_OFFSET = 0x818bb68
TRAMPOLINE = bytes.fromhex("e00b40b9e10b40f9e28f41a9810100b41f04007140010054290040f929f97c92ff4300d1e90300f9e1030091e81080d2010000d4ff43009103000014e81080d2010000d477725417")
EXPECTED_COUNT = 1

def apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]:
    raw_offsets = byte_replacer.find_offsets(data, TARGET)
    pat_offsets = byte_replacer.find_offsets(data, REPL_HEADER)

    total = len(raw_offsets) + len(pat_offsets)
    info = {
        "name": "sigprocmask",
        "expected": EXPECTED_COUNT,
        "raw_count": len(raw_offsets),
        "patched_count": len(pat_offsets),
        "raw_offsets": [hex(x) for x in raw_offsets],
        "patched_offsets": [hex(x) for x in pat_offsets],
        "cave_offset": hex(CAVE_OFFSET)
    }

    if total < 1:
        if allow_missing:
            info["status"] = "skipped_missing"
            return True, info
        info["status"] = "missing_signature"
        return False, info

    if verify:
        ok = (len(raw_offsets) == 0 and len(pat_offsets) >= 1)
        if ok and len(data) >= CAVE_OFFSET + len(TRAMPOLINE):
            ok = (data[CAVE_OFFSET:CAVE_OFFSET + len(TRAMPOLINE)] == TRAMPOLINE)
        info["status"] = "verified" if ok else "unverified"
        return ok, info

    if len(raw_offsets) == 0:
        info["status"] = "already_patched"
        return True, info

    if not dry_run:
        for off in raw_offsets:
            data[off:off + len(REPL_HEADER)] = REPL_HEADER
        data[CAVE_OFFSET:CAVE_OFFSET + len(TRAMPOLINE)] = TRAMPOLINE
        info["status"] = "patched"
    else:
        info["status"] = "dry_run"

    return True, info
