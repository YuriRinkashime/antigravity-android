"""
resolv_redirect.py - Absolute resolver path patch: /etc/resolv.conf -> /sdcard/rslv.cnf (length 16 in-place).
"""
from typing import Tuple, Dict, Any, List, Union

TARGET = b"/etc/resolv.conf"
LEGACY_TARGET = b"etc//resolv.conf"
REPLACEMENT = b"/sdcard/rslv.cnf"
ADJACENT_REJECT = b"/.\\"
EXPECTED_COUNT = 1

def scan(data: Union[bytes, bytearray, memoryview]) -> Tuple[List[int], List[int], List[int]]:
    clean = []
    rejected = []
    for tgt in (TARGET, LEGACY_TARGET):
        pos = 0
        while True:
            pos = data.find(tgt, pos)
            if pos < 0:
                break
            prev_b = data[pos - 1] if pos > 0 else None
            next_b = data[pos + len(tgt)] if pos + len(tgt) < len(data) else None
            adjacent = (prev_b is not None and bytes([prev_b]) in ADJACENT_REJECT) or (
                next_b is not None and bytes([next_b]) in ADJACENT_REJECT)
            (rejected if adjacent else clean).append(pos)
            pos += len(tgt)

    repl = []
    pos = 0
    while True:
        pos = data.find(REPLACEMENT, pos)
        if pos < 0:
            break
        repl.append(pos)
        pos += len(REPLACEMENT)
    return clean, rejected, repl

def apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]:
    clean, rejected, repl = scan(data)
    total = len(clean) + len(repl)

    info = {
        "name": "resolv_redirect",
        "expected": EXPECTED_COUNT,
        "clean_count": len(clean),
        "rejected_count": len(rejected),
        "replacement_count": len(repl),
        "clean_offsets": [hex(x) for x in clean],
        "replacement_offsets": [hex(x) for x in repl]
    }

    if total < 1:
        if allow_missing:
            info["status"] = "skipped_missing"
            return True, info
        info["status"] = "missing_signature"
        return False, info

    if verify:
        ok = (len(clean) == 0 and len(repl) >= 1)
        info["status"] = "verified" if ok else "unverified"
        return ok, info

    if len(clean) == 0:
        info["status"] = "already_patched"
        return True, info

    if not dry_run:
        for pos in clean:
            data[pos:pos + len(REPLACEMENT)] = REPLACEMENT
        info["status"] = "patched"
    else:
        info["status"] = "dry_run"

    return True, info
