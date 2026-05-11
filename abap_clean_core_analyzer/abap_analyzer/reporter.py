import json
from datetime import datetime
from collections import Counter

from .rules import Severity


class ConsoleReporter:

    SYMBOLS = {
        Severity.CRITICAL: "[CRITICAL]",
        Severity.WARNING:  " [WARNING]",
        Severity.INFO:     "    [INFO]",
    }

    def generate(self, result):
        lines = []
        sep = "=" * 70

        lines.append(sep)
        lines.append("  ABAP Clean Core Analysis Report")
        lines.append(f"  Date: {datetime.now().strftime('%Y-%m-%d %H:%M')}")
        lines.append(sep)

        for fr in result.files:
            lines.append(f"\n  File: {fr.path}")
            lines.append(f"  Statements analyzed: {fr.statement_count}")
            lines.append("-" * 70)

            if not fr.findings:
                lines.append("  No findings - Clean Core compatible!")
                continue

            for f in sorted(fr.findings, key=lambda x: (x.severity.level, x.line_start)):
                sym = self.SYMBOLS[f.severity]
                lines.append(
                    f"\n{sym} {f.rule_id} - {f.rule_name} "
                    f"(Line {f.line_start}-{f.line_end})"
                )
                lines.append(f"  > {f.matched_text}")
                lines.append(f"  Suggestion: {f.suggestion}")
                if f.clean_core_api:
                    lines.append(f"  Clean Core API: {f.clean_core_api}")

        lines.append(f"\n{sep}")
        lines.append("  Summary")
        lines.append(sep)

        critical = result.count_by_severity(Severity.CRITICAL)
        warning = result.count_by_severity(Severity.WARNING)
        info = result.count_by_severity(Severity.INFO)

        lines.append(f"  CRITICAL : {critical}")
        lines.append(f"  WARNING  : {warning}")
        lines.append(f"  INFO     : {info}")
        lines.append(f"  Total    : {critical + warning + info}")

        cat_counter = Counter()
        for fr in result.files:
            for f in fr.findings:
                cat_counter[f.category.value] += 1

        if cat_counter:
            lines.append("\n  By Category:")
            for cat, count in cat_counter.most_common():
                lines.append(f"    {cat}: {count}")

        lines.append(sep)
        return "\n".join(lines)


class HTMLReporter:

    def generate(self, result):
        critical = result.count_by_severity(Severity.CRITICAL)
        warning = result.count_by_severity(Severity.WARNING)
        info = result.count_by_severity(Severity.INFO)
        total = critical + warning + info

        rows = []
        for fr in result.files:
            for f in sorted(fr.findings, key=lambda x: (x.severity.level, x.line_start)):
                sev = f.severity.value
                rows.append(f"""
                <tr>
                    <td><span class="badge badge-{sev}">{sev.upper()}</span></td>
                    <td>{f.rule_id}</td>
                    <td>{f.rule_name}</td>
                    <td>{fr.path}</td>
                    <td>{f.line_start}-{f.line_end}</td>
                    <td><code>{_esc(f.matched_text)}</code></td>
                    <td>{_esc(f.suggestion)}</td>
                    <td>{_esc(f.clean_core_api)}</td>
                </tr>""")

        findings_rows = "\n".join(rows)
        now = datetime.now().strftime("%Y-%m-%d %H:%M")
        file_count = len(result.files)

        return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>ABAP Clean Core Analysis Report</title>
<style>
*{{box-sizing:border-box;margin:0;padding:0}}
body{{font-family:'Segoe UI',Tahoma,sans-serif;background:#f0f2f5;padding:24px;color:#333}}
.container{{max-width:1400px;margin:0 auto}}
.header{{background:linear-gradient(135deg,#1a73e8,#0d47a1);color:#fff;padding:32px;border-radius:12px;margin-bottom:24px}}
.header h1{{font-size:24px;margin-bottom:8px}}
.header p{{opacity:.9;font-size:14px}}
.cards{{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin-bottom:24px}}
.card{{background:#fff;padding:20px;border-radius:10px;box-shadow:0 2px 8px rgba(0,0,0,.08);text-align:center}}
.card .num{{font-size:36px;font-weight:700}}
.card .label{{font-size:13px;color:#666;margin-top:4px}}
.card.critical .num{{color:#d32f2f}}
.card.warning .num{{color:#f57c00}}
.card.info .num{{color:#1976d2}}
.card.total .num{{color:#333}}
.panel{{background:#fff;border-radius:10px;box-shadow:0 2px 8px rgba(0,0,0,.08);overflow:hidden;margin-bottom:24px}}
.panel-header{{padding:16px 20px;border-bottom:1px solid #eee;font-weight:600;font-size:16px}}
table{{width:100%;border-collapse:collapse;font-size:13px}}
th{{background:#fafafa;padding:12px 16px;text-align:left;font-weight:600;border-bottom:2px solid #eee;position:sticky;top:0}}
td{{padding:12px 16px;border-bottom:1px solid #f0f0f0;vertical-align:top}}
tr:hover{{background:#f8f9fa}}
.badge{{display:inline-block;padding:3px 10px;border-radius:12px;font-size:11px;font-weight:700;color:#fff}}
.badge-critical{{background:#d32f2f}}
.badge-warning{{background:#f57c00}}
.badge-info{{background:#1976d2}}
code{{background:#f5f5f5;padding:2px 6px;border-radius:4px;font-size:12px;word-break:break-all}}
.footer{{text-align:center;color:#999;font-size:12px;margin-top:24px}}
</style>
</head>
<body>
<div class="container">
    <div class="header">
        <h1>ABAP Clean Core Analysis Report</h1>
        <p>Generated: {now} &nbsp;|&nbsp; Files analyzed: {file_count}</p>
    </div>
    <div class="cards">
        <div class="card critical"><div class="num">{critical}</div><div class="label">Critical</div></div>
        <div class="card warning"><div class="num">{warning}</div><div class="label">Warning</div></div>
        <div class="card info"><div class="num">{info}</div><div class="label">Info</div></div>
        <div class="card total"><div class="num">{total}</div><div class="label">Total Findings</div></div>
    </div>
    <div class="panel">
        <div class="panel-header">Findings Detail</div>
        <table>
            <thead>
                <tr>
                    <th>Severity</th>
                    <th>Rule</th>
                    <th>Name</th>
                    <th>File</th>
                    <th>Lines</th>
                    <th>Matched Code</th>
                    <th>Suggestion</th>
                    <th>Clean Core API</th>
                </tr>
            </thead>
            <tbody>{findings_rows}
            </tbody>
        </table>
    </div>
    <div class="footer">ABAP Clean Core Analyzer v1.0.0</div>
</div>
</body>
</html>"""


class JSONReporter:

    def generate(self, result):
        data = {
            "meta": {
                "tool": "ABAP Clean Core Analyzer",
                "version": "1.0.0",
                "date": datetime.now().isoformat(),
                "files_analyzed": len(result.files),
            },
            "summary": {
                "total": result.total_findings,
                "critical": result.count_by_severity(Severity.CRITICAL),
                "warning": result.count_by_severity(Severity.WARNING),
                "info": result.count_by_severity(Severity.INFO),
            },
            "files": [],
        }

        for fr in result.files:
            file_data = {
                "path": fr.path,
                "statements": fr.statement_count,
                "findings": [
                    {
                        "rule_id": f.rule_id,
                        "rule_name": f.rule_name,
                        "severity": f.severity.value,
                        "category": f.category.value,
                        "line_start": f.line_start,
                        "line_end": f.line_end,
                        "message": f.message,
                        "suggestion": f.suggestion,
                        "clean_core_api": f.clean_core_api,
                    }
                    for f in fr.findings
                ],
            }
            data["files"].append(file_data)

        return json.dumps(data, indent=2, ensure_ascii=False)


def _esc(text):
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )
