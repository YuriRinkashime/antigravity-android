"""
sigill_handler.py - Preserves LD_PRELOAD SIGILL handler for liblse_compat.so by:
1. Skipping Go runtime.rt_sigaction for SIGILL (4)
2. Neutralizing Google Crashpad's raw svc #0 rt_sigaction for SIGILL (4)
"""
import sys
import os
from typing import Tuple, Dict, Any

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PATCHES_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
sys.path.insert(0, os.path.join(PATCHES_ROOT, "engine"))

import byte_replacer

# Target 1: Go runtime.rt_sigaction
# mov x0, [sp, 8]; mov x1, [sp, 16]; mov x2, [sp, 24]; mov x3, [sp, 32]; mov x8, 134; svc #0; str w0, [sp, 40]; ret
TARGET_GO = bytes.fromhex("e00740f9e10b40f9e20f40f9e31340f9c81080d2010000d4e02b00b9c0035fd6")
REPL_GO = bytes.fromhex("e08740a9e28f41a91f1000f1c81080d240000054010000d4ff2b00b9c0035fd6")

# Target 2: Google Crashpad direct sys_rt_sigaction for SIGILL (4)
# mov w8, #134; mov w0, #4; str q0, [x27, 0x160]; svc #0; cmn x0, 0x1000
TARGET_CRASHPAD = bytes.fromhex("c810805280008052605b803d010000d41f0440b1")
# mov w8, #134; mov w0, #0; str q0, [x27, 0x160]; mov x0, xzr; cmn x0, 0x1000
REPL_CRASHPAD = bytes.fromhex("c810805200008052605b803de0031faa1f0440b1")

def apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]:
    go_raw = byte_replacer.find_offsets(data, TARGET_GO)
    go_pat = byte_replacer.find_offsets(data, REPL_GO)
    cp_raw = byte_replacer.find_offsets(data, TARGET_CRASHPAD)
    cp_pat = byte_replacer.find_offsets(data, REPL_CRASHPAD)

    info = {
        "name": "sigill",
        "expected": 2,
        "go_raw": len(go_raw),
        "go_patched": len(go_pat),
        "crashpad_raw": len(cp_raw),
        "crashpad_patched": len(cp_pat),
        "go_raw_offsets": [hex(x) for x in go_raw],
        "go_patched_offsets": [hex(x) for x in go_pat],
        "crashpad_raw_offsets": [hex(x) for x in cp_raw],
        "crashpad_patched_offsets": [hex(x) for x in cp_pat],
    }

    has_go = (len(go_raw) + len(go_pat)) > 0
    has_cp = (len(cp_raw) + len(cp_pat)) > 0

    if not has_go or not has_cp:
        if allow_missing:
            info["status"] = "skipped_missing"
            return True, info
        info["status"] = "missing_signature"
        return False, info

    if verify:
        ok = (len(go_raw) == 0 and len(go_pat) >= 1 and len(cp_raw) == 0 and len(cp_pat) >= 1)
        info["status"] = "verified" if ok else "unverified"
        return ok, info

    if len(go_raw) == 0 and len(cp_raw) == 0:
        info["status"] = "already_patched"
        return True, info

    if not dry_run:
        for off in go_raw:
            data[off:off + len(REPL_GO)] = REPL_GO
        for off in cp_raw:
            data[off:off + len(REPL_CRASHPAD)] = REPL_CRASHPAD
        info["status"] = "patched"
    else:
        info["status"] = "dry_run"

    return True, info
