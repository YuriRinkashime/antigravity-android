"""
resolv_redirect.py - Relative resolver path patch: /etc/resolv.conf -> etc//resolv.conf (length 16 in-place),
and resilient public DNS fallback patch: defaultNS [127.0.0.1:53, [::1]:53] -> [8.8.8.8:53].
"""
import struct
from typing import Tuple, Dict, Any, List, Union

TARGET = b"/etc/resolv.conf"
LEGACY_TARGET = b"/sdcard/rslv.cnf"
REPLACEMENT = b"etc//resolv.conf"
ADJACENT_REJECT = b"/.\\"
EXPECTED_COUNT = 1

DNS_FALLBACK_TARGET = b"127.0.0.1:53"
DNS_FALLBACK_REPL = b"8.8.8.8:53\x00\x00"
DNS_FALLBACK_STR_OFFSET = 0x4c75955
DNS_ARRAY_LEN_OFFSET = 0xa39b808
DNS_SLICE_LEN_OFFSET = 0xa39af18

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

    dns_fallback_clean = (
        len(data) > DNS_ARRAY_LEN_OFFSET + 8 and
        data[DNS_FALLBACK_STR_OFFSET:DNS_FALLBACK_STR_OFFSET + len(DNS_FALLBACK_TARGET)] == DNS_FALLBACK_TARGET
    )
    dns_fallback_patched = (
        len(data) > DNS_ARRAY_LEN_OFFSET + 8 and
        data[DNS_FALLBACK_STR_OFFSET:DNS_FALLBACK_STR_OFFSET + 10] == b"8.8.8.8:53"
    )

    info = {
        "name": "resolv_redirect",
        "expected": EXPECTED_COUNT,
        "clean_count": len(clean),
        "rejected_count": len(rejected),
        "replacement_count": len(repl),
        "clean_offsets": [hex(x) for x in clean],
        "replacement_offsets": [hex(x) for x in repl],
        "dns_fallback_clean": dns_fallback_clean,
        "dns_fallback_patched": dns_fallback_patched
    }

    if total < 1:
        if allow_missing:
            info["status"] = "skipped_missing"
            return True, info
        info["status"] = "missing_signature"
        return False, info

    if verify:
        path_ok = (len(clean) == 0 and len(repl) >= 1)
        dns_ok = dns_fallback_patched or (allow_missing and not dns_fallback_clean)
        ok = path_ok and dns_ok
        info["status"] = "verified" if ok else "unverified"
        return ok, info

    if len(clean) == 0 and dns_fallback_patched:
        info["status"] = "already_patched"
        return True, info

    if not dry_run:
        for pos in clean:
            data[pos:pos + len(REPLACEMENT)] = REPLACEMENT
        if dns_fallback_clean:
            data[DNS_FALLBACK_STR_OFFSET:DNS_FALLBACK_STR_OFFSET + len(DNS_FALLBACK_REPL)] = DNS_FALLBACK_REPL
            struct.pack_into("<Q", data, DNS_ARRAY_LEN_OFFSET, 10)
            struct.pack_into("<Q", data, DNS_SLICE_LEN_OFFSET, 1)
        info["status"] = "patched"
    else:
        info["status"] = "dry_run"

    return True, info
