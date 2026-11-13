#!/usr/bin/env python3

# Copyright (c) 2026 The Browser Express Authors. All rights reserved.
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this file,
# You can obtain one at https://mozilla.org/MPL/2.0/.

import argparse
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import browser_express_crash_release_gate as release_gate
import browser_express_upload_crash_symbols as symbol_upload


BUILD_FLAGS = """
declare_args() {
  browser_express_crash_upload_url = "https://api.browser.express/crash/android/upload"
  browser_express_crash_product = "BrowserExpress_Android"
}

if (is_official_build) {
  assert(browser_express_crash_upload_url != "",
         "Set browser_express_crash_upload_url for official builds.")
}

assert(browser_express_crash_upload_url != "https://cr.brave.com",
       "Do not use Brave's crash endpoint for this fork.")
assert(browser_express_crash_upload_url != "https://clients2.google.com/cr/report",
       "Do not use Google's crash endpoint for this fork.")
"""


def write_file(path, contents):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(contents, encoding="utf-8")


def create_minimal_repo(root):
    for relative_path in release_gate.REQUIRED_SOURCE_FILES:
        write_file(root / relative_path, "Browser Express crash release gate test\n")
    write_file(root / "components/crash_reporting/buildflags.gni", BUILD_FLAGS)

    for relative_path in release_gate.REQUIRED_PATCH_OVERRIDES:
        write_file(root / relative_path, "Browser Express patch override\n")

    write_file(
        root / "docs/production_crash_observability_file_tracking.md",
        "* `01-test.patch`\n",
    )
    write_file(
        root / "docs/production_crash_observability_patches/01-test.patch",
        "diff --git a/test b/test\n",
    )


def make_args(root, **overrides):
    defaults = {
        "root": root,
        "out_dir": None,
        "gn_args_file": None,
        "require_official_config": False,
        "require_symbols": False,
        "symbols_archive": [],
        "check_tracking_doc": False,
    }
    defaults.update(overrides)
    return argparse.Namespace(**defaults)


class BrowserExpressCrashReleaseGateTest(unittest.TestCase):
    def test_release_gate_passes_minimal_repo(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            create_minimal_repo(root)

            findings = release_gate.run_checks(make_args(root))

        self.assertEqual([], findings)

    def test_release_gate_parses_multiline_gn_assignment(self):
        assignments = release_gate.parse_gn_assignments(
            'browser_express_crash_upload_url =\n'
            '    "https://api.browser.express/crash/android/upload"\n'
        )

        self.assertEqual(
            "https://api.browser.express/crash/android/upload",
            assignments["browser_express_crash_upload_url"],
        )

    def test_release_gate_rejects_forbidden_args_endpoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            create_minimal_repo(root)
            args_gn = root / "out/Release/args.gn"
            write_file(
                args_gn,
                'is_official_build = true\n'
                'browser_express_crash_upload_url = "https://cr.brave.com"\n',
            )

            findings = release_gate.run_checks(make_args(root, gn_args_file=args_gn))

        self.assertTrue(any("forbidden inherited endpoint" in item.message for item in findings))

    def test_release_gate_accepts_official_args_using_default_endpoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            create_minimal_repo(root)
            args_gn = root / "out/Release/args.gn"
            write_file(args_gn, "is_official_build = true\n")

            findings = release_gate.run_checks(
                make_args(root, gn_args_file=args_gn, require_official_config=True)
            )

        self.assertEqual([], findings)

    def test_release_gate_requires_symbol_archive_when_requested(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            create_minimal_repo(root)

            findings = release_gate.run_checks(make_args(root, require_symbols=True))

        self.assertTrue(any("--require-symbols" in item.message for item in findings))

    def test_release_gate_accepts_nonempty_symbol_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            create_minimal_repo(root)
            archive = root / "symbols.tar"
            archive.write_bytes(b"symbols")

            findings = release_gate.run_checks(
                make_args(root, require_symbols=True, symbols_archive=[archive])
            )

        self.assertEqual([], findings)

    def test_symbol_upload_dry_run_validates_without_network(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / "symbols.tar"
            archive.write_bytes(b"symbols")

            result = symbol_upload.main(
                [
                    "--symbols-archive",
                    str(archive),
                    "--upload-url",
                    "https://symbols.example.com/upload",
                    "--version",
                    "1.2.3.4",
                    "--dry-run",
                ]
            )

        self.assertEqual(0, result)

    def test_symbol_upload_uses_backend_default_url(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / "symbols.tar"
            archive.write_bytes(b"symbols")

            result = symbol_upload.main(
                [
                    "--symbols-archive",
                    str(archive),
                    "--version",
                    "1.2.3.4",
                    "--dry-run",
                ]
            )

        self.assertEqual(0, result)

    def test_symbol_upload_rejects_inherited_endpoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / "symbols.tar"
            archive.write_bytes(b"symbols")

            with self.assertRaises(symbol_upload.UploadConfigError):
                symbol_upload.validate_upload_config(
                    "https://cr.brave.com", archive, allow_http_for_testing=False
                )


if __name__ == "__main__":
    unittest.main()
