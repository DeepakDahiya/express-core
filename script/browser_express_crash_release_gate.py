#!/usr/bin/env python3

# Copyright (c) 2026 The Browser Express Authors. All rights reserved.
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this file,
# You can obtain one at https://mozilla.org/MPL/2.0/.

"""Release gate for Browser Express crash reporting configuration."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


FORBIDDEN_ENDPOINTS = (
    "https://cr.brave.com",
    "https://clients2.google.com/cr/report",
    "https://clients4.google.com/cr/report",
)

PRODUCTION_CRASH_UPLOAD_URL = "https://api.browser.express/crash/android/upload"

REQUIRED_SOURCE_FILES = (
    "components/crash_reporting/buildflags.gni",
    "chromium_src/components/crash/core/app/crash_reporter_client.cc",
    "components/minidump_uploader/android/java/templates/"
    "BraveMinidumpUploaderConfig.template",
    "components/minidump_uploader/android/java/src/org/chromium/components/"
    "minidump_uploader/util/BraveHttpURLConnectionFactoryImpl.java",
    "android/java/org/chromium/chrome/browser/crash/"
    "BrowserExpressPendingCrashReportStore.java",
)

REQUIRED_PATCH_OVERRIDES = (
    "patches/components-crash-android-crash-keys.patch",
    "patches/components-crash-core-app-BUILD.gn.patch",
    "patches/components-minidump_uploader-BUILD.gn.patch",
)

FILES_FOR_FORBIDDEN_ENDPOINT_SCAN = (
    "chromium_src/components/crash/core/app/crash_reporter_client.cc",
    "components/minidump_uploader/android/java/templates/"
    "BraveMinidumpUploaderConfig.template",
    "components/minidump_uploader/android/java/src/org/chromium/components/"
    "minidump_uploader/util/BraveHttpURLConnectionFactoryImpl.java",
    "android/java/org/chromium/chrome/browser/crash/"
    "BrowserExpressPendingCrashReportStore.java",
)

SYMBOL_ARCHIVE_PATTERNS = ("*.tar", "*.tar.gz", "*.tgz", "*.zip")


class Finding:
    def __init__(self, level: str, message: str) -> None:
        self.level = level
        self.message = message

    def __str__(self) -> str:
        return f"{self.level}: {self.message}"


def parse_gn_assignments(text: str) -> dict[str, str]:
    assignments: dict[str, str] = {}
    lines = text.splitlines()
    index = 0
    while index < len(lines):
        raw_line = lines[index]
        index += 1
        line = raw_line.split("#", 1)[0].strip()
        if not line or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if value == "" and index < len(lines):
            value = lines[index].split("#", 1)[0].strip()
            index += 1
        if value.startswith('"') and value.endswith('"'):
            value = value[1:-1]
        assignments[key] = value
    return assignments


def find_symbol_archives(out_dir: Path) -> list[Path]:
    dist_dir = out_dir / "dist"
    if not dist_dir.is_dir():
        return []

    archives: list[Path] = []
    for pattern in SYMBOL_ARCHIVE_PATTERNS:
        archives.extend(path for path in dist_dir.glob(pattern) if path.is_file())
    return sorted(set(archives))


def _read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _has_nonempty_file(path: Path) -> bool:
    return path.is_file() and path.stat().st_size > 0


def check_required_files(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for relative_path in REQUIRED_SOURCE_FILES + REQUIRED_PATCH_OVERRIDES:
        path = root / relative_path
        if not path.is_file():
            findings.append(Finding("ERROR", f"missing required file: {relative_path}"))
    return findings


def check_buildflags(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    path = root / "components/crash_reporting/buildflags.gni"
    if not path.is_file():
        return [Finding("ERROR", "missing components/crash_reporting/buildflags.gni")]

    text = _read_text(path)
    required_snippets = (
        "browser_express_crash_upload_url",
        "browser_express_crash_product",
        "Set browser_express_crash_upload_url for official builds.",
        "Do not use Brave's crash endpoint for this fork.",
        "Do not use Google's crash endpoint for this fork.",
    )
    for snippet in required_snippets:
        if snippet not in text:
            findings.append(Finding("ERROR", f"buildflags missing guard: {snippet}"))

    assignments = parse_gn_assignments(text)
    default_url = assignments.get("browser_express_crash_upload_url", "")
    if default_url != PRODUCTION_CRASH_UPLOAD_URL:
        findings.append(
            Finding(
                "ERROR",
                "browser_express_crash_upload_url default must be "
                f"{PRODUCTION_CRASH_UPLOAD_URL}",
            )
        )
    if default_url in FORBIDDEN_ENDPOINTS:
        findings.append(
            Finding(
                "ERROR",
                "browser_express_crash_upload_url default is a forbidden inherited endpoint",
            )
        )
    product = assignments.get("browser_express_crash_product", "")
    if product != "BrowserExpress_Android":
        findings.append(
            Finding(
                "ERROR",
                "browser_express_crash_product default must be BrowserExpress_Android",
            )
        )
    return findings


def check_forbidden_endpoint_literals(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for relative_path in FILES_FOR_FORBIDDEN_ENDPOINT_SCAN:
        path = root / relative_path
        if not path.is_file():
            continue
        text = _read_text(path)
        for endpoint in FORBIDDEN_ENDPOINTS:
            if endpoint in text:
                findings.append(
                    Finding(
                        "ERROR",
                        f"forbidden inherited endpoint {endpoint} found in {relative_path}",
                    )
                )
    return findings


def check_gn_args(
    gn_args_file: Path | None,
    require_official_config: bool,
    default_crash_url: str = PRODUCTION_CRASH_UPLOAD_URL,
) -> list[Finding]:
    if gn_args_file is None:
        if require_official_config:
            return [Finding("ERROR", "--require-official-config needs an args.gn file")]
        return []

    if not gn_args_file.is_file():
        return [Finding("ERROR", f"missing args.gn file: {gn_args_file}")]

    args = parse_gn_assignments(_read_text(gn_args_file))
    is_official_build = args.get("is_official_build") == "true"
    require_url = require_official_config or is_official_build
    crash_url = args.get("browser_express_crash_upload_url", default_crash_url)
    product = args.get("browser_express_crash_product", "BrowserExpress_Android")

    findings: list[Finding] = []
    if require_url and not crash_url:
        findings.append(
            Finding(
                "ERROR",
                f"{gn_args_file} must set browser_express_crash_upload_url",
            )
        )
    if crash_url in FORBIDDEN_ENDPOINTS:
        findings.append(
            Finding(
                "ERROR",
                f"{gn_args_file} uses forbidden inherited endpoint {crash_url}",
            )
        )
    if crash_url and not crash_url.startswith("https://"):
        findings.append(
            Finding(
                "ERROR",
                f"{gn_args_file} crash upload URL must use https://",
            )
        )
    if require_url and not product:
        findings.append(
            Finding(
                "ERROR",
                f"{gn_args_file} must set browser_express_crash_product",
            )
        )
    return findings


def check_symbol_archives(
    out_dir: Path | None,
    symbol_archives: list[Path],
    require_symbols: bool,
) -> list[Finding]:
    findings: list[Finding] = []
    archives = list(symbol_archives)
    if require_symbols and not archives:
        if out_dir is None:
            findings.append(
                Finding("ERROR", "--require-symbols needs --symbols-archive or --out-dir")
            )
        else:
            archives = find_symbol_archives(out_dir)
            if not archives:
                findings.append(
                    Finding(
                        "ERROR",
                        f"no symbol archive found under {out_dir / 'dist'}",
                    )
                )

    for archive in archives:
        if not _has_nonempty_file(archive):
            findings.append(Finding("ERROR", f"symbol archive is missing or empty: {archive}"))
    return findings


def check_tracking_doc(root: Path) -> list[Finding]:
    tracking_doc = root / "docs/production_crash_observability_file_tracking.md"
    patch_dir = root / "docs/production_crash_observability_patches"
    if not tracking_doc.is_file():
        return [Finding("ERROR", "missing production crash file-tracking doc")]

    findings: list[Finding] = []
    text = _read_text(tracking_doc)
    for match in re.finditer(r"\* `([^`]+\.patch)`", text):
        patch_name = match.group(1)
        if "/" in patch_name:
            path = root / patch_name
        else:
            path = patch_dir / patch_name
        if not _has_nonempty_file(path):
            findings.append(
                Finding("ERROR", f"tracking doc lists missing patch artifact: {patch_name}")
            )
    return findings


def run_checks(args: argparse.Namespace) -> list[Finding]:
    root = args.root.resolve()
    gn_args_file = args.gn_args_file
    if gn_args_file is None and args.out_dir is not None:
        candidate = args.out_dir / "args.gn"
        if candidate.exists():
            gn_args_file = candidate

    findings: list[Finding] = []
    findings.extend(check_required_files(root))
    findings.extend(check_buildflags(root))
    findings.extend(check_forbidden_endpoint_literals(root))
    findings.extend(check_gn_args(gn_args_file, args.require_official_config))
    findings.extend(
        check_symbol_archives(args.out_dir, args.symbols_archive, args.require_symbols)
    )
    if args.check_tracking_doc:
        findings.extend(check_tracking_doc(root))
    return findings


def create_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Validate Browser Express crash release configuration."
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="Path to //brave. Defaults to this script's repository root.",
    )
    parser.add_argument(
        "--out-dir",
        type=Path,
        help="Chromium output directory, for example ../out/android_Release_arm64.",
    )
    parser.add_argument(
        "--gn-args-file",
        type=Path,
        help="Path to args.gn. Defaults to <out-dir>/args.gn when --out-dir is set.",
    )
    parser.add_argument(
        "--require-official-config",
        action="store_true",
        help="Require a non-empty owned crash upload URL even if args.gn is not official.",
    )
    parser.add_argument(
        "--require-symbols",
        action="store_true",
        help="Require at least one non-empty Android symbol archive.",
    )
    parser.add_argument(
        "--symbols-archive",
        action="append",
        type=Path,
        default=[],
        help="Path to a generated symbol archive. May be passed more than once.",
    )
    parser.add_argument(
        "--check-tracking-doc",
        action="store_true",
        help="Verify listed production-crash patch artifacts exist.",
    )
    return parser


def main(argv: list[str]) -> int:
    parser = create_arg_parser()
    args = parser.parse_args(argv)
    findings = run_checks(args)
    if findings:
        for finding in findings:
            print(finding, file=sys.stderr)
        return 1

    print("Browser Express crash release gate passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
