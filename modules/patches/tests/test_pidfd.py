#!/usr/bin/env python3
"""
test_pidfd.py - Unit test for pidfd_open seccomp mitigation patch.
"""
import unittest
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODULES_DIR = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
sys.path.insert(0, os.path.join(MODULES_DIR, "groups", "syscalls"))

import pidfd

class TestPidfd(unittest.TestCase):
    def test_signatures(self):
        self.assertEqual(len(pidfd.TARGET), 16)
        self.assertEqual(len(pidfd.REPLACEMENT), 16)
        self.assertEqual(pidfd.EXPECTED_COUNT, 1)

    def test_apply_and_verify(self):
        raw = bytearray(b"\x00" * 32 + pidfd.TARGET + b"\x00" * 32)
        
        # Dry run
        ok, info = pidfd.apply(raw, dry_run=True)
        self.assertTrue(ok)
        self.assertEqual(info["status"], "dry_run")
        self.assertIn(pidfd.TARGET, raw)

        # Live patch
        ok, info = pidfd.apply(raw, dry_run=False)
        self.assertTrue(ok)
        self.assertEqual(info["status"], "patched")
        self.assertNotIn(pidfd.TARGET, raw)
        self.assertIn(pidfd.REPLACEMENT, raw)

        # Idempotent re-apply
        ok, info = pidfd.apply(raw, dry_run=False)
        self.assertTrue(ok)
        self.assertEqual(info["status"], "already_patched")

        # Verify
        ok, info = pidfd.apply(raw, verify=True)
        self.assertTrue(ok)
        self.assertEqual(info["status"], "verified")

if __name__ == "__main__":
    unittest.main()
