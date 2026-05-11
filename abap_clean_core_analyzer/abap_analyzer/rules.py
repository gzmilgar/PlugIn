import re
from dataclasses import dataclass
from enum import Enum
from typing import Optional


class Severity(Enum):
    CRITICAL = "critical"
    WARNING = "warning"
    INFO = "info"

    @property
    def level(self):
        return {"critical": 0, "warning": 1, "info": 2}[self.value]


class Category(Enum):
    DB_ACCESS = "Database Access"
    API_USAGE = "API Usage"
    UI = "User Interface"
    OBSOLETE = "Obsolete Syntax"
    ARCHITECTURE = "Architecture"


@dataclass
class Finding:
    rule_id: str
    rule_name: str
    severity: Severity
    category: Category
    message: str
    suggestion: str
    line_start: int
    line_end: int
    matched_text: str
    clean_core_api: str = ""


class Rule:
    def __init__(self, rule_id, name, severity, category, description,
                 suggestion, patterns, exclude_patterns=None, clean_core_api=""):
        self.rule_id = rule_id
        self.name = name
        self.severity = Severity(severity)
        self.category = Category(category)
        self.description = description
        self.suggestion = suggestion
        self.patterns = [re.compile(p, re.IGNORECASE) for p in patterns]
        self.exclude_patterns = [re.compile(p, re.IGNORECASE) for p in (exclude_patterns or [])]
        self.clean_core_api = clean_core_api

    def check(self, text, start_line, end_line):
        upper = text.upper()
        for ep in self.exclude_patterns:
            if ep.search(upper):
                return None
        for p in self.patterns:
            m = p.search(upper)
            if m:
                return Finding(
                    rule_id=self.rule_id,
                    rule_name=self.name,
                    severity=self.severity,
                    category=self.category,
                    message=f"{self.description}: {m.group(0).strip()}",
                    suggestion=self.suggestion,
                    line_start=start_line,
                    line_end=end_line,
                    matched_text=m.group(0).strip(),
                    clean_core_api=self.clean_core_api,
                )
        return None


RULE_DEFS = [
    # ── Database Access ───────────────────────────────────────────────
    {
        "rule_id": "CC001",
        "name": "Direct SAP Table SELECT",
        "severity": "critical",
        "category": "Database Access",
        "description": "Direct SELECT on SAP standard table",
        "suggestion": "Use released CDS Views (I_* / C_*) or released APIs instead of direct table access.",
        "patterns": [
            r"\bSELECT\b.+\bFROM\b\s+(?!Z|Y|I_|C_|A_)([A-Z][A-Z0-9_/]+)",
            r"\bJOIN\b\s+(?!Z|Y|I_|C_|A_)([A-Z][A-Z0-9_/]+)",
        ],
        "clean_core_api": "CDS View (I_* / C_*)",
    },
    {
        "rule_id": "CC002",
        "name": "Direct SAP Table INSERT",
        "severity": "critical",
        "category": "Database Access",
        "description": "Direct INSERT on SAP standard table",
        "suggestion": "Use released APIs (RAP / BAPI) for data creation.",
        "patterns": [r"\bINSERT\b\s+(?!Z|Y)([A-Z][A-Z0-9_]+)\s+FROM\b"],
        "clean_core_api": "Released BAPI / RAP BO",
    },
    {
        "rule_id": "CC003",
        "name": "Direct SAP Table UPDATE",
        "severity": "critical",
        "category": "Database Access",
        "description": "Direct UPDATE on SAP standard table",
        "suggestion": "Use released APIs for data modification.",
        "patterns": [r"\bUPDATE\b\s+(?!Z|Y)([A-Z][A-Z0-9_]+)\s+SET\b"],
        "clean_core_api": "Released BAPI / RAP BO",
    },
    {
        "rule_id": "CC004",
        "name": "Direct SAP Table DELETE",
        "severity": "critical",
        "category": "Database Access",
        "description": "Direct DELETE on SAP standard table",
        "suggestion": "Use released APIs for data deletion.",
        "patterns": [r"\bDELETE\b\s+FROM\s+(?!Z|Y)([A-Z][A-Z0-9_]+)"],
        "clean_core_api": "Released BAPI / RAP BO",
    },
    {
        "rule_id": "CC005",
        "name": "Direct SAP Table MODIFY",
        "severity": "critical",
        "category": "Database Access",
        "description": "Direct MODIFY on SAP standard table",
        "suggestion": "Use released APIs instead of direct MODIFY.",
        "patterns": [r"\bMODIFY\b\s+(?!Z|Y)([A-Z][A-Z0-9_]+)\s+FROM\b"],
        "clean_core_api": "Released BAPI / RAP BO",
    },
    {
        "rule_id": "CC006",
        "name": "Native SQL (EXEC SQL)",
        "severity": "critical",
        "category": "Database Access",
        "description": "Native SQL usage detected",
        "suggestion": "Use ABAP SQL with CDS Views instead of native SQL.",
        "patterns": [r"\bEXEC\s+SQL\b"],
        "clean_core_api": "ABAP SQL + CDS View",
    },

    # ── API Usage ─────────────────────────────────────────────────────
    {
        "rule_id": "CC010",
        "name": "CALL TRANSACTION",
        "severity": "critical",
        "category": "API Usage",
        "description": "CALL TRANSACTION detected",
        "suggestion": "Use released APIs (BAPI / RAP) instead of CALL TRANSACTION.",
        "patterns": [r"\bCALL\s+TRANSACTION\b"],
        "clean_core_api": "Released BAPI / RAP API",
    },
    {
        "rule_id": "CC011",
        "name": "SUBMIT Report",
        "severity": "warning",
        "category": "API Usage",
        "description": "SUBMIT (report call) detected",
        "suggestion": "Replace with released APIs or job scheduling via CL_JOB_SCHEDULER.",
        "patterns": [r"\bSUBMIT\b\s+[A-Z]\w+"],
        "clean_core_api": "Released API / CL_JOB_SCHEDULER",
    },
    {
        "rule_id": "CC012",
        "name": "Kernel Call",
        "severity": "critical",
        "category": "API Usage",
        "description": "Direct kernel call detected",
        "suggestion": "Kernel calls are not available in ABAP Cloud. Use released APIs.",
        "patterns": [r"\bCALL\b\s+'[A-Z_]+'"],
        "clean_core_api": "Released API",
    },
    {
        "rule_id": "CC013",
        "name": "Dynamic Program Generation",
        "severity": "critical",
        "category": "API Usage",
        "description": "Dynamic program generation detected",
        "suggestion": "Dynamic code generation is restricted in ABAP Cloud. Redesign the logic.",
        "patterns": [
            r"\bGENERATE\s+SUBROUTINE\s+POOL\b",
            r"\bINSERT\s+REPORT\b",
            r"\bREAD\s+REPORT\b",
        ],
        "clean_core_api": "Static implementation / redesign",
    },
    {
        "rule_id": "CC014",
        "name": "RFC Destination Usage",
        "severity": "warning",
        "category": "API Usage",
        "description": "RFC call with destination detected",
        "suggestion": "Use Communication Arrangement / Communication Scenario for remote calls.",
        "patterns": [r"\bCALL\s+FUNCTION\b.+\bDESTINATION\b"],
        "clean_core_api": "Communication Arrangement API",
    },

    # ── User Interface ────────────────────────────────────────────────
    {
        "rule_id": "CC020",
        "name": "Classic ALV (REUSE_ALV)",
        "severity": "warning",
        "category": "User Interface",
        "description": "Classic ALV function module detected",
        "suggestion": "Use CL_SALV_TABLE or RAP + Fiori Elements for list displays.",
        "patterns": [r"CALL\s+FUNCTION\s+'REUSE_ALV"],
        "clean_core_api": "CL_SALV_TABLE / Fiori Elements",
    },
    {
        "rule_id": "CC021",
        "name": "GUI Download/Upload",
        "severity": "warning",
        "category": "User Interface",
        "description": "GUI_DOWNLOAD or GUI_UPLOAD usage detected",
        "suggestion": "Use CL_GUI_FRONTEND_SERVICES or released file APIs.",
        "patterns": [r"CALL\s+FUNCTION\s+'GUI_(DOWNLOAD|UPLOAD)'"],
        "clean_core_api": "CL_GUI_FRONTEND_SERVICES",
    },
    {
        "rule_id": "CC022",
        "name": "Classic Dynpro",
        "severity": "critical",
        "category": "User Interface",
        "description": "Classic Dynpro (screen) processing detected",
        "suggestion": "Replace with RAP + Fiori Elements application.",
        "patterns": [
            r"\bCALL\s+SCREEN\b",
            r"\bSET\s+SCREEN\b",
            r"\bLEAVE\s+SCREEN\b",
            r"\bSUPPRESS\s+DIALOG\b",
        ],
        "clean_core_api": "RAP + Fiori Elements",
    },
    {
        "rule_id": "CC023",
        "name": "Selection Screen",
        "severity": "warning",
        "category": "User Interface",
        "description": "Classic selection screen detected",
        "suggestion": "Use Fiori Elements with filter bar instead of selection screens.",
        "patterns": [
            r"\bSELECTION-SCREEN\b",
            r"\bSELECT-OPTIONS\b",
        ],
        "clean_core_api": "Fiori Elements Filter Bar",
    },
    {
        "rule_id": "CC024",
        "name": "WRITE Statement",
        "severity": "warning",
        "category": "User Interface",
        "description": "WRITE statement (list output) detected",
        "suggestion": "Replace list output with CDS View + Fiori Elements or CL_SALV_TABLE.",
        "patterns": [r"^WRITE[\s:/]"],
        "exclude_patterns": [r"\bWRITE\b\s+\S+\s+TO\b"],
        "clean_core_api": "CDS View + Fiori Elements",
    },
    {
        "rule_id": "CC025",
        "name": "Popup Function Module",
        "severity": "info",
        "category": "User Interface",
        "description": "Legacy popup function module detected",
        "suggestion": "Use released popup APIs or Fiori dialog patterns.",
        "patterns": [r"CALL\s+FUNCTION\s+'POPUP_"],
        "clean_core_api": "Fiori Dialog",
    },

    # ── Obsolete Syntax ───────────────────────────────────────────────
    {
        "rule_id": "CC030",
        "name": "FORM/PERFORM Subroutine",
        "severity": "warning",
        "category": "Obsolete Syntax",
        "description": "FORM/PERFORM subroutine detected (obsolete programming model)",
        "suggestion": "Refactor to CLASS/METHOD based OOP approach.",
        "patterns": [
            r"^FORM\s+\w+",
            r"^PERFORM\s+\w+",
        ],
        "clean_core_api": "ABAP OOP (CLASS / METHOD)",
    },
    {
        "rule_id": "CC031",
        "name": "TABLES Declaration",
        "severity": "warning",
        "category": "Obsolete Syntax",
        "description": "TABLES work area declaration detected",
        "suggestion": "Use DATA with TYPE instead of TABLES declaration.",
        "patterns": [r"^TABLES[\s:]"],
        "clean_core_api": "DATA ... TYPE ...",
    },
    {
        "rule_id": "CC032",
        "name": "WITH HEADER LINE",
        "severity": "warning",
        "category": "Obsolete Syntax",
        "description": "WITH HEADER LINE detected (obsolete internal table concept)",
        "suggestion": "Use separate work area: DATA wa TYPE ..., itab TYPE TABLE OF ...",
        "patterns": [r"\bWITH\s+HEADER\s+LINE\b"],
        "clean_core_api": "Typed internal table + work area",
    },
    {
        "rule_id": "CC033",
        "name": "OCCURS Keyword",
        "severity": "warning",
        "category": "Obsolete Syntax",
        "description": "OCCURS keyword detected (obsolete table definition)",
        "suggestion": "Use TYPE [STANDARD|SORTED|HASHED] TABLE OF instead.",
        "patterns": [r"\bOCCURS\b\s+\d+"],
        "clean_core_api": "TYPE TABLE OF",
    },
    {
        "rule_id": "CC034",
        "name": "Obsolete Arithmetic",
        "severity": "info",
        "category": "Obsolete Syntax",
        "description": "Obsolete arithmetic keyword detected",
        "suggestion": "Use inline expressions: result = a + b.",
        "patterns": [
            r"^ADD\s+\w+\s+TO\b",
            r"^SUBTRACT\s+\w+\s+FROM\b",
            r"^MULTIPLY\s+\w+\s+BY\b",
            r"^DIVIDE\s+\w+\s+BY\b",
            r"^COMPUTE\s+",
        ],
        "clean_core_api": "Inline expressions",
    },

    # ── Architecture ──────────────────────────────────────────────────
    {
        "rule_id": "CC040",
        "name": "Dynamic ASSIGN",
        "severity": "info",
        "category": "Architecture",
        "description": "Dynamic field symbol assignment detected",
        "suggestion": "Review for ABAP Cloud compatibility. Consider static typing or RTTI.",
        "patterns": [r"\bASSIGN\b.+\(.*\)"],
        "clean_core_api": "Static typing / RTTI",
    },
]


def get_rules():
    return [Rule(**d) for d in RULE_DEFS]
