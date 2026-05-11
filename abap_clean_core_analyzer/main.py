import argparse
import sys
import os

from abap_analyzer.analyzer import ABAPAnalyzer
from abap_analyzer.reporter import ConsoleReporter, HTMLReporter, JSONReporter


def main():
    parser = argparse.ArgumentParser(
        description="ABAP Clean Core Analyzer - Detect non-Clean-Core patterns in ABAP source code",
    )
    parser.add_argument("path", help="Path to ABAP file or directory to analyze")
    parser.add_argument(
        "-f", "--format",
        choices=["console", "html", "json"],
        default="console",
        help="Output format (default: console)",
    )
    parser.add_argument(
        "-o", "--output",
        help="Save report to file instead of stdout",
    )
    parser.add_argument(
        "-s", "--severity",
        choices=["critical", "warning", "info"],
        default="info",
        help="Minimum severity level to report (default: info)",
    )

    args = parser.parse_args()

    if not os.path.exists(args.path):
        print(f"Error: Path not found: {args.path}", file=sys.stderr)
        sys.exit(1)

    analyzer = ABAPAnalyzer(min_severity=args.severity)

    if os.path.isdir(args.path):
        result = analyzer.analyze_directory(args.path)
    else:
        result = analyzer.analyze_file(args.path)

    reporters = {
        "console": ConsoleReporter,
        "html": HTMLReporter,
        "json": JSONReporter,
    }
    reporter = reporters[args.format]()
    output = reporter.generate(result)

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(output)
        print(f"Report saved to: {args.output}")
    else:
        print(output)


if __name__ == "__main__":
    main()
