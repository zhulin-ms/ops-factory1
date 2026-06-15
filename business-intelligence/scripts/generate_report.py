#!/usr/bin/env python3
"""
BI Dashboard Excel Export — Exports all 8 BI tabs to a single XLSX workbook.

Usage:
    python generate_report.py                    # Generate CN + EN
    python generate_report.py --language zh      # Chinese only
    python generate_report.py --language en      # English only
    python generate_report.py --bi-url http://localhost:8093
"""

import argparse
import concurrent.futures
import copy
import json
import logging
import sys
from pathlib import Path
from typing import Optional, Tuple

import requests

SCRIPT_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPT_DIR))

from bi_client import BiApiClient, BiDataAdapter
from xlsx_builder import BiXlsxBuilder

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)
logger = logging.getLogger(__name__)


def print_banner(languages: list, bi_url: str) -> None:
    print("\n" + "=" * 70)
    print("  📊 BI Dashboard Excel Export")
    print("     BI 仪表板 Excel 导出")
    print("=" * 70)
    lang_str = " & ".join([("Chinese" if l == "zh" else "English") for l in languages])
    print(f"  🌐 Languages: {lang_str}")
    print(f"  🔗 BI URL: {bi_url}")


def main():
    parser = argparse.ArgumentParser(description="BI Dashboard Excel Export")
    parser.add_argument(
        "--language", "-l",
        choices=["en", "zh", "both"],
        default="both",
        help="Output language (en=English, zh=Chinese, both=Both)"
    )
    parser.add_argument(
        "--bi-url",
        default="http://localhost:8093",
        help="BI backend base URL"
    )
    parser.add_argument(
        "--output-dir",
        default="output",
        help="Output directory for generated files"
    )
    parser.add_argument(
        "--start-date",
        default=None,
        help="Start date for reporting period (e.g. 2024-04-01)"
    )
    parser.add_argument(
        "--end-date",
        default=None,
        help="End date for reporting period (e.g. 2025-04-01)"
    )

    args = parser.parse_args()

    if args.language == "both":
        languages = ["zh", "en"]
    else:
        languages = [args.language]

    print_banner(languages, args.bi_url)

    # Step 1: Fetch data from BI backend
    print("\n  ▶ Fetching data from BI backend...")
    if args.start_date or args.end_date:
        print(f"    Period: {args.start_date or '…'} ~ {args.end_date or '…'}")
    try:
        client = BiApiClient(base_url=args.bi_url)
        overview = client.get_overview(
            start_date=args.start_date,
            end_date=args.end_date,
        )
        adapter = BiDataAdapter(overview)
        all_tabs_raw = adapter.get_all_tabs()
        print(f"    ✓ Fetched {len(all_tabs_raw)} tabs")
    except (requests.RequestException, json.JSONDecodeError, OSError) as e:
        print(f"    ❌ Failed to fetch data: {e}")
        logger.exception("Failed to fetch BI data")
        sys.exit(1)

    # Step 2: Generate reports for each language
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    # Build period label for footer
    period_label = ""
    if args.start_date or args.end_date:
        start = args.start_date or "…"
        end = args.end_date or "…"
        period_label = f"{start} ~ {end}"

    all_paths = []
    results = []

    def _build_language(language: str) -> Tuple[str, Optional[Path], Optional[Exception]]:
        """Build a single-language workbook. Thread-safe for independent languages."""
        try:
            # Deep-copy the shared snapshot so each language adapter can mutate labels safely.
            localized_adapter = BiDataAdapter(copy.deepcopy(overview), language=language)
            all_tabs = localized_adapter.get_all_tabs()
            builder = BiXlsxBuilder(snapshot_data=all_tabs, language=language, period_label=period_label)
            filepath = builder.save(output_dir=output_dir)
            return language, filepath, None
        except (OSError, ValueError, TypeError, KeyError, AttributeError) as exc:
            return language, None, exc

    with concurrent.futures.ThreadPoolExecutor(max_workers=min(len(languages), 4)) as executor:
        futures = {executor.submit(_build_language, lang): lang for lang in languages}
        for future in concurrent.futures.as_completed(futures):
            lang, path, exc = future.result()
            lang_label = "ZH" if lang == "zh" else "EN"
            if exc:
                print(f"\n  [{lang_label}] ❌ Failed to build report: {exc}")
                logger.exception("Failed to build %s report", lang_label)
                results.append((lang, None, exc))
            else:
                print(f"\n  [{lang_label}] ✓ Saved: {path.name}")
                all_paths.append(path)
                results.append((lang, path, None))

    # Preserve deterministic order (zh before en) for the summary
    results.sort(key=lambda r: languages.index(r[0]))
    all_paths = [r[1] for r in results if r[1] is not None]

    # Summary
    print("\n" + "=" * 70)
    if all_paths:
        print("  ✅ All reports generated successfully!")
        print("=" * 70)
        print("\n  📄 Output files:")
        for path in all_paths:
            print(f"    • {path}")
    else:
        print("  ❌ No reports were generated.")
    print("=" * 70)


if __name__ == "__main__":
    main()
