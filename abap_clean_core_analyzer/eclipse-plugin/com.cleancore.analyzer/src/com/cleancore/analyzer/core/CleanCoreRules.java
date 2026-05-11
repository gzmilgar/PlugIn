package com.cleancore.analyzer.core;

import java.util.ArrayList;
import java.util.List;

public final class CleanCoreRules {

    private CleanCoreRules() {}

    public static List<Rule> getAll() {
        List<Rule> rules = new ArrayList<>();

        // ── Database Access (resolveTableMapping=true for CDS suggestions) ──
        rules.add(new Rule("CC001", "Direct SAP Table SELECT",
            Severity.CRITICAL, Category.DB_ACCESS,
            "Direct SELECT on SAP standard table",
            "Use released CDS Views (I_* / C_*) or released APIs instead of direct table access.",
            new String[]{
                "\\bSELECT\\b.+\\bFROM\\b\\s+(?!Z|Y|I_|C_|A_)([A-Z][A-Z0-9_/]+)",
                "\\bJOIN\\b\\s+(?!Z|Y|I_|C_|A_)([A-Z][A-Z0-9_/]+)"
            }, null, "CDS View (I_* / C_*)", true));

        rules.add(new Rule("CC002", "Direct SAP Table INSERT",
            Severity.CRITICAL, Category.DB_ACCESS,
            "Direct INSERT on SAP standard table",
            "Use released APIs (RAP / BAPI) for data creation.",
            new String[]{"\\bINSERT\\b\\s+(?!Z|Y)([A-Z][A-Z0-9_]+)\\s+FROM\\b"},
            null, "Released BAPI / RAP BO", true));

        rules.add(new Rule("CC003", "Direct SAP Table UPDATE",
            Severity.CRITICAL, Category.DB_ACCESS,
            "Direct UPDATE on SAP standard table",
            "Use released APIs for data modification.",
            new String[]{"\\bUPDATE\\b\\s+(?!Z|Y)([A-Z][A-Z0-9_]+)\\s+SET\\b"},
            null, "Released BAPI / RAP BO", true));

        rules.add(new Rule("CC004", "Direct SAP Table DELETE",
            Severity.CRITICAL, Category.DB_ACCESS,
            "Direct DELETE on SAP standard table",
            "Use released APIs for data deletion.",
            new String[]{"\\bDELETE\\b\\s+FROM\\s+(?!Z|Y)([A-Z][A-Z0-9_]+)"},
            null, "Released BAPI / RAP BO", true));

        rules.add(new Rule("CC005", "Direct SAP Table MODIFY",
            Severity.CRITICAL, Category.DB_ACCESS,
            "Direct MODIFY on SAP standard table",
            "Use released APIs instead of direct MODIFY.",
            new String[]{"\\bMODIFY\\b\\s+(?!Z|Y)([A-Z][A-Z0-9_]+)\\s+FROM\\b"},
            null, "Released BAPI / RAP BO", true));

        rules.add(new Rule("CC006", "Native SQL (EXEC SQL)",
            Severity.CRITICAL, Category.DB_ACCESS,
            "Native SQL usage detected",
            "Use ABAP SQL with CDS Views instead of native SQL.",
            new String[]{"\\bEXEC\\s+SQL\\b"},
            null, "ABAP SQL + CDS View"));

        // ── API Usage ────────────────────────────────────────────
        rules.add(new Rule("CC010", "CALL TRANSACTION",
            Severity.CRITICAL, Category.API_USAGE,
            "CALL TRANSACTION detected",
            "Use released APIs (BAPI / RAP) instead of CALL TRANSACTION.",
            new String[]{"\\bCALL\\s+TRANSACTION\\b"},
            null, "Released BAPI / RAP API"));

        rules.add(new Rule("CC011", "SUBMIT Report",
            Severity.WARNING, Category.API_USAGE,
            "SUBMIT (report call) detected",
            "Replace with released APIs or job scheduling via CL_JOB_SCHEDULER.",
            new String[]{"\\bSUBMIT\\b\\s+[A-Z]\\w+"},
            null, "Released API / CL_JOB_SCHEDULER"));

        rules.add(new Rule("CC012", "Kernel Call",
            Severity.CRITICAL, Category.API_USAGE,
            "Direct kernel call detected",
            "Kernel calls are not available in ABAP Cloud. Use released APIs.",
            new String[]{"\\bCALL\\b\\s+'[A-Z_]+'"},
            null, "Released API"));

        rules.add(new Rule("CC013", "Dynamic Program Generation",
            Severity.CRITICAL, Category.API_USAGE,
            "Dynamic program generation detected",
            "Dynamic code generation is restricted in ABAP Cloud. Redesign the logic.",
            new String[]{
                "\\bGENERATE\\s+SUBROUTINE\\s+POOL\\b",
                "\\bINSERT\\s+REPORT\\b",
                "\\bREAD\\s+REPORT\\b"
            }, null, "Static implementation / redesign"));

        rules.add(new Rule("CC014", "RFC Destination Usage",
            Severity.WARNING, Category.API_USAGE,
            "RFC call with destination detected",
            "Use Communication Arrangement / Communication Scenario for remote calls.",
            new String[]{"\\bCALL\\s+FUNCTION\\b.+\\bDESTINATION\\b"},
            null, "Communication Arrangement API"));

        // ── User Interface ───────────────────────────────────────
        rules.add(new Rule("CC020", "Classic ALV (REUSE_ALV)",
            Severity.WARNING, Category.UI,
            "Classic ALV function module detected",
            "Use CL_SALV_TABLE or RAP + Fiori Elements for list displays.",
            new String[]{"CALL\\s+FUNCTION\\s+'REUSE_ALV"},
            null, "CL_SALV_TABLE / Fiori Elements"));

        rules.add(new Rule("CC021", "GUI Download/Upload",
            Severity.WARNING, Category.UI,
            "GUI_DOWNLOAD or GUI_UPLOAD usage detected",
            "Use CL_GUI_FRONTEND_SERVICES or released file APIs.",
            new String[]{"CALL\\s+FUNCTION\\s+'GUI_(DOWNLOAD|UPLOAD)'"},
            null, "CL_GUI_FRONTEND_SERVICES"));

        rules.add(new Rule("CC022", "Classic Dynpro",
            Severity.CRITICAL, Category.UI,
            "Classic Dynpro (screen) processing detected",
            "Replace with RAP + Fiori Elements application.",
            new String[]{
                "\\bCALL\\s+SCREEN\\b",
                "\\bSET\\s+SCREEN\\b",
                "\\bLEAVE\\s+SCREEN\\b",
                "\\bSUPPRESS\\s+DIALOG\\b"
            }, null, "RAP + Fiori Elements"));

        rules.add(new Rule("CC023", "Selection Screen",
            Severity.WARNING, Category.UI,
            "Classic selection screen detected",
            "Use Fiori Elements with filter bar instead of selection screens.",
            new String[]{
                "\\bSELECTION-SCREEN\\b",
                "\\bSELECT-OPTIONS\\b"
            }, null, "Fiori Elements Filter Bar"));

        rules.add(new Rule("CC024", "WRITE Statement",
            Severity.WARNING, Category.UI,
            "WRITE statement (list output) detected",
            "Replace list output with CDS View + Fiori Elements or CL_SALV_TABLE.",
            new String[]{"^WRITE[\\s:/]"},
            new String[]{"\\bWRITE\\b\\s+\\S+\\s+TO\\b"},
            "CDS View + Fiori Elements"));

        rules.add(new Rule("CC025", "Popup Function Module",
            Severity.INFO, Category.UI,
            "Legacy popup function module detected",
            "Use released popup APIs or Fiori dialog patterns.",
            new String[]{"CALL\\s+FUNCTION\\s+'POPUP_"},
            null, "Fiori Dialog"));

        // ── Obsolete Syntax ──────────────────────────────────────
        rules.add(new Rule("CC030", "FORM/PERFORM Subroutine",
            Severity.WARNING, Category.OBSOLETE,
            "FORM/PERFORM subroutine detected (obsolete programming model)",
            "Refactor to CLASS/METHOD based OOP approach.",
            new String[]{
                "^FORM\\s+\\w+",
                "^PERFORM\\s+\\w+"
            }, null, "ABAP OOP (CLASS / METHOD)"));

        rules.add(new Rule("CC031", "TABLES Declaration",
            Severity.WARNING, Category.OBSOLETE,
            "TABLES work area declaration detected",
            "Use DATA with TYPE instead of TABLES declaration.",
            new String[]{"^TABLES[\\s:]"},
            null, "DATA ... TYPE ..."));

        rules.add(new Rule("CC032", "WITH HEADER LINE",
            Severity.WARNING, Category.OBSOLETE,
            "WITH HEADER LINE detected (obsolete internal table concept)",
            "Use separate work area: DATA wa TYPE ..., itab TYPE TABLE OF ...",
            new String[]{"\\bWITH\\s+HEADER\\s+LINE\\b"},
            null, "Typed internal table + work area"));

        rules.add(new Rule("CC033", "OCCURS Keyword",
            Severity.WARNING, Category.OBSOLETE,
            "OCCURS keyword detected (obsolete table definition)",
            "Use TYPE [STANDARD|SORTED|HASHED] TABLE OF instead.",
            new String[]{"\\bOCCURS\\b\\s+\\d+"},
            null, "TYPE TABLE OF"));

        rules.add(new Rule("CC034", "Obsolete Arithmetic",
            Severity.INFO, Category.OBSOLETE,
            "Obsolete arithmetic keyword detected",
            "Use inline expressions: result = a + b.",
            new String[]{
                "^ADD\\s+\\w+\\s+TO\\b",
                "^SUBTRACT\\s+\\w+\\s+FROM\\b",
                "^MULTIPLY\\s+\\w+\\s+BY\\b",
                "^DIVIDE\\s+\\w+\\s+BY\\b",
                "^COMPUTE\\s+"
            }, null, "Inline expressions"));

        // ── Architecture ─────────────────────────────────────────
        rules.add(new Rule("CC040", "Dynamic ASSIGN",
            Severity.INFO, Category.ARCHITECTURE,
            "Dynamic field symbol assignment detected",
            "Review for ABAP Cloud compatibility. Consider static typing or RTTI.",
            new String[]{"\\bASSIGN\\b.+\\(.*\\)"},
            null, "Static typing / RTTI"));

        return rules;
    }
}
