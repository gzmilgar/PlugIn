import os

from .parser import ABAPParser
from .rules import get_rules, Severity

ABAP_EXTENSIONS = {".abap", ".prog", ".clas", ".fugr", ".intf", ".txt"}


class FileResult:
    def __init__(self, path, statement_count, findings):
        self.path = path
        self.statement_count = statement_count
        self.findings = findings


class AnalysisResult:
    def __init__(self):
        self.files = []

    def add(self, file_result):
        self.files.append(file_result)

    @property
    def total_findings(self):
        return sum(len(f.findings) for f in self.files)

    def count_by_severity(self, severity):
        return sum(
            1
            for f in self.files
            for finding in f.findings
            if finding.severity == severity
        )


class ABAPAnalyzer:
    def __init__(self, min_severity="info"):
        self.parser = ABAPParser()
        self.rules = get_rules()
        self.min_level = Severity(min_severity).level

    def analyze_file(self, path):
        result = AnalysisResult()
        statements = self.parser.parse_file(path)
        findings = self._apply_rules(statements)
        result.add(FileResult(path, len(statements), findings))
        return result

    def analyze_directory(self, path, extensions=None):
        result = AnalysisResult()
        exts = extensions or ABAP_EXTENSIONS
        for root, _dirs, files in os.walk(path):
            for fname in sorted(files):
                if os.path.splitext(fname)[1].lower() in exts:
                    fpath = os.path.join(root, fname)
                    statements = self.parser.parse_file(fpath)
                    findings = self._apply_rules(statements)
                    result.add(FileResult(fpath, len(statements), findings))
        return result

    def analyze_source(self, source, name="<source>"):
        result = AnalysisResult()
        statements = self.parser.parse(source)
        findings = self._apply_rules(statements)
        result.add(FileResult(name, len(statements), findings))
        return result

    def _apply_rules(self, statements):
        findings = []
        for stmt in statements:
            for rule in self.rules:
                if rule.severity.level > self.min_level:
                    continue
                finding = rule.check(stmt.text, stmt.start_line, stmt.end_line)
                if finding:
                    findings.append(finding)
        return findings
