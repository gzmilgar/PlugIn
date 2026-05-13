package com.cleancore.analyzer.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ABAPAnalyzer {

    private static final String MAPPING_FILE = "cds_mapping.txt";

    private static final Set<String> DB_RULES = new HashSet<>();
    static {
        DB_RULES.add("CC001"); DB_RULES.add("CC002"); DB_RULES.add("CC003");
        DB_RULES.add("CC004"); DB_RULES.add("CC005");
    }

    // ── Effort (gun/adam) ──────────────────────────────────────────
    private static final Map<String, Double> EFFORT_MAP = new HashMap<>();
    static {
        // DB Access: Table -> CDS View
        EFFORT_MAP.put("CC001", 0.25);  // SELECT
        EFFORT_MAP.put("CC002", 0.25);  // INSERT
        EFFORT_MAP.put("CC003", 0.25);  // UPDATE
        EFFORT_MAP.put("CC004", 0.25);  // DELETE
        EFFORT_MAP.put("CC005", 0.25);  // MODIFY
        EFFORT_MAP.put("CC006", 1.0);   // EXEC SQL
        // API Usage: BAPI/FM -> Released API
        EFFORT_MAP.put("CC010", 2.0);   // CALL TRANSACTION -> API
        EFFORT_MAP.put("CC011", 1.0);   // SUBMIT -> API
        EFFORT_MAP.put("CC012", 2.0);   // Kernel Call
        EFFORT_MAP.put("CC013", 3.0);   // Dynamic Program Generation
        EFFORT_MAP.put("CC014", 1.5);   // RFC Destination
        // UI
        EFFORT_MAP.put("CC020", 1.0);   // Classic ALV -> CL_SALV / Fiori
        EFFORT_MAP.put("CC021", 0.5);   // GUI_DOWNLOAD/UPLOAD
        EFFORT_MAP.put("CC022", 5.0);   // Dynpro -> Fiori
        EFFORT_MAP.put("CC023", 3.0);   // Selection Screen -> Fiori
        EFFORT_MAP.put("CC024", 0.5);   // WRITE -> Fiori/ALV
        EFFORT_MAP.put("CC025", 0.5);   // Popup FM
        // Obsolete Syntax
        EFFORT_MAP.put("CC030", 0.5);   // FORM/PERFORM -> CLASS/METHOD
        EFFORT_MAP.put("CC031", 0.25);  // TABLES declaration
        EFFORT_MAP.put("CC032", 0.25);  // WITH HEADER LINE
        EFFORT_MAP.put("CC033", 0.25);  // OCCURS
        EFFORT_MAP.put("CC034", 0.1);   // Obsolete arithmetic
        // Architecture
        EFFORT_MAP.put("CC040", 0.5);   // Dynamic ASSIGN
    }

    private static final Set<String> SKIP = new HashSet<>();
    static {
        SKIP.add("TABLE"); SKIP.add("DATA"); SKIP.add("CORRESPONDING");
        SKIP.add("SINGLE"); SKIP.add("DISTINCT"); SKIP.add("INTO");
        SKIP.add("FIELDS"); SKIP.add("SET"); SKIP.add("COUNT");
        SKIP.add("WHERE"); SKIP.add("AND"); SKIP.add("OR");
        SKIP.add("ON"); SKIP.add("AS"); SKIP.add("BY");
        SKIP.add("FOR"); SKIP.add("ALL"); SKIP.add("ENTRIES");
        SKIP.add("APPENDING"); SKIP.add("UP"); SKIP.add("TO");
        SKIP.add("ROWS"); SKIP.add("ORDER"); SKIP.add("GROUP");
        SKIP.add("SELECT"); SKIP.add("FROM"); SKIP.add("JOIN");
        SKIP.add("INNER"); SKIP.add("LEFT"); SKIP.add("RIGHT");
        SKIP.add("OUTER"); SKIP.add("CROSS"); SKIP.add("HAVING");
    }

    private static final Map<String, String> BUILTIN_MAP = new HashMap<>();
    static {
        BUILTIN_MAP.put("MARA", "I_Product");
        BUILTIN_MAP.put("MAKT", "I_ProductDescription");
        BUILTIN_MAP.put("MARC", "I_ProductPlant");
        BUILTIN_MAP.put("MARD", "I_ProductStorageLocation");
        BUILTIN_MAP.put("MARM", "I_ProductUnitsOfMeasure");
        BUILTIN_MAP.put("MBEW", "I_ProductValuation");
        BUILTIN_MAP.put("MLAN", "I_ProductTaxClassification");
        BUILTIN_MAP.put("MVKE", "I_ProductSalesDelivery");
        BUILTIN_MAP.put("EKKO", "I_PurchaseOrder");
        BUILTIN_MAP.put("EKPO", "I_PurchaseOrderItem");
        BUILTIN_MAP.put("EKBE", "I_PurchaseOrderHistory");
        BUILTIN_MAP.put("EBAN", "I_PurchaseRequisitionItem");
        BUILTIN_MAP.put("EKET", "I_PurchaseOrderScheduleLine");
        BUILTIN_MAP.put("VBAK", "I_SalesOrder");
        BUILTIN_MAP.put("VBAP", "I_SalesOrderItem");
        BUILTIN_MAP.put("VBEP", "I_SalesOrderScheduleLine");
        BUILTIN_MAP.put("VBPA", "I_SalesOrderPartner");
        BUILTIN_MAP.put("VBFA", "I_SalesDocumentFlow");
        BUILTIN_MAP.put("LIKP", "I_DeliveryDocument");
        BUILTIN_MAP.put("LIPS", "I_DeliveryDocumentItem");
        BUILTIN_MAP.put("VBRK", "I_BillingDocument");
        BUILTIN_MAP.put("VBRP", "I_BillingDocumentItem");
        BUILTIN_MAP.put("KONV", "I_SalesOrderPricingElement");
        BUILTIN_MAP.put("KNA1", "I_Customer");
        BUILTIN_MAP.put("KNB1", "I_CustomerCompany");
        BUILTIN_MAP.put("KNVV", "I_CustomerSalesArea");
        BUILTIN_MAP.put("LFA1", "I_Supplier");
        BUILTIN_MAP.put("LFB1", "I_SupplierCompany");
        BUILTIN_MAP.put("BUT000", "I_BusinessPartner");
        BUILTIN_MAP.put("ADRC", "I_Address");
        BUILTIN_MAP.put("BKPF", "I_JournalEntry");
        BUILTIN_MAP.put("BSEG", "I_OperationalAcctgDocItem");
        BUILTIN_MAP.put("ACDOCA", "I_JournalEntryItem");
        BUILTIN_MAP.put("RBKP", "I_InvoiceDocument");
        BUILTIN_MAP.put("SKA1", "I_GLAccountInChartOfAccounts");
        BUILTIN_MAP.put("SKAT", "I_GLAccountText");
        BUILTIN_MAP.put("T001", "I_CompanyCode");
        BUILTIN_MAP.put("T001W", "I_Plant");
        BUILTIN_MAP.put("T001L", "I_StorageLocation");
        BUILTIN_MAP.put("TCURR", "I_ExchangeRateRawData");
        BUILTIN_MAP.put("T005", "I_Country");
        BUILTIN_MAP.put("T006", "I_UnitOfMeasure");
        BUILTIN_MAP.put("DD07T", "I_DomainFixedValueText");
        BUILTIN_MAP.put("CSKS", "I_CostCenter");
        BUILTIN_MAP.put("CEPC", "I_ProfitCenter");
        BUILTIN_MAP.put("CAUFV", "I_ManufacturingOrder");
        BUILTIN_MAP.put("AFKO", "I_ManufacturingOrder");
        BUILTIN_MAP.put("AFPO", "I_ManufacturingOrderItem");
        BUILTIN_MAP.put("AFVC", "I_ManufacturingOrderOperation");
        BUILTIN_MAP.put("AFRU", "I_MfgOrderConfirmation");
        BUILTIN_MAP.put("AUFK", "I_ProductionOrder");
        BUILTIN_MAP.put("RESB", "I_ReservationDocumentItem");
        BUILTIN_MAP.put("STKO", "I_BillOfMaterial");
        BUILTIN_MAP.put("STPO", "I_BillOfMaterialItem");
        BUILTIN_MAP.put("MSEG", "I_MaterialDocumentItem");
        BUILTIN_MAP.put("MKPF", "I_MaterialDocumentHeader");
        BUILTIN_MAP.put("QALS", "I_InspectionLot");
        BUILTIN_MAP.put("EQUI", "I_Equipment");
        BUILTIN_MAP.put("T100", "CL_MESSAGE_HELPER=>GET_TEXT( ) veya MESSAGE..INTO");
        BUILTIN_MAP.put("T100W", "CL_MESSAGE_HELPER=>GET_TEXT( ) veya MESSAGE..INTO");
        BUILTIN_MAP.put("E070", "CL_CTS_API=>READ_TRANSPORT_REQUEST( )");
        BUILTIN_MAP.put("E071", "CL_CTS_API=>READ_TRANSPORT_REQUEST( )");
        BUILTIN_MAP.put("TADIR", "I_CatalogEntry / CL_ABAP_TYPEDESCR");
        BUILTIN_MAP.put("TRDIR", "CL_ABAP_TYPEDESCR / XCO_CP=>REPOSITORY");
        BUILTIN_MAP.put("SY", "CL_ABAP_SYST (System Fields API)");
        BUILTIN_MAP.put("TVARVC", "CL_TVARVC=>GET_VALUE( )");
        BUILTIN_MAP.put("NAST", "I_OutputRequestItem / BRF+");
        BUILTIN_MAP.put("USR01", "I_UserContactCard / CL_ABAP_CONTEXT_INFO");
        BUILTIN_MAP.put("USR02", "CL_ABAP_CONTEXT_INFO=>GET_USER_TECHNICAL_NAME( )");
        BUILTIN_MAP.put("TSTC", "XCO_CP=>REPOSITORY (Transaction metadata)");
        BUILTIN_MAP.put("TFDIR", "XCO_CP=>REPOSITORY (FM metadata)");
        BUILTIN_MAP.put("DD03L", "XCO_CP=>REPOSITORY (DDIC metadata)");
        BUILTIN_MAP.put("DD02L", "XCO_CP=>REPOSITORY (Table metadata)");
        BUILTIN_MAP.put("CDHDR", "I_ChangeDocument");
        BUILTIN_MAP.put("CDPOS", "I_ChangeDocumentItem");
        BUILTIN_MAP.put("TOBJ", "CL_ABAP_AUTH (Authorization Object)");
        BUILTIN_MAP.put("AGR_1251", "CL_ABAP_AUTH (Role/Auth API)");
        BUILTIN_MAP.put("SYST", "CL_ABAP_CONTEXT_INFO (System Context)");
    }

    private final ABAPParser parser = new ABAPParser();
    private final List<Rule> rules;
    private final int minLevel;
    private final Map<String, String> cdsMap;

    public ABAPAnalyzer() {
        this(Severity.INFO);
    }

    public ABAPAnalyzer(Severity minSeverity) {
        this.rules = CleanCoreRules.getAll();
        this.minLevel = minSeverity.getLevel();
        this.cdsMap = loadMergedMapping();
    }

    public List<Finding> analyze(String source) {
        List<ABAPStatement> statements = parser.parse(source);
        List<Finding> findings = new ArrayList<>();

        for (ABAPStatement stmt : statements) {
            for (Rule rule : rules) {
                if (rule.getSeverity().getLevel() > minLevel) {
                    continue;
                }
                Finding f = rule.check(stmt.getText(), stmt.getStartLine(), stmt.getEndLine());
                if (f != null) {
                    f = enrichFinding(f);
                    Double effort = EFFORT_MAP.get(f.getRuleId());
                    if (effort != null) f.setEffortDays(effort);
                    findings.add(f);
                }
            }
        }
        return findings;
    }

    /**
     * Birden cok ABAP objesini topluca analiz eder.
     * Her finding'e ait olduğu obje adi (objectName) eklenir.
     *
     * @param objectSources key: obje adi (ZCL_FOO, ZPROG_BAR vs.), value: kaynak kod
     * @return tum bulgular tek listede (objectName field'i set edilmis halde)
     */
    public List<Finding> analyzeMultiple(Map<String, String> objectSources) {
        List<Finding> all = new ArrayList<>();
        if (objectSources == null || objectSources.isEmpty()) {
            return all;
        }
        for (Map.Entry<String, String> entry : objectSources.entrySet()) {
            String objName = entry.getKey();
            String source = entry.getValue();
            if (source == null || source.trim().isEmpty()) continue;
            List<Finding> partial = analyze(source);
            for (Finding f : partial) {
                f.setObjectName(objName);
            }
            all.addAll(partial);
        }
        return all;
    }

    private Map<String, String> loadMergedMapping() {
        Map<String, String> merged = new HashMap<>(BUILTIN_MAP);

        for (String dir : getMappingSearchPaths()) {
            File f = new File(dir, MAPPING_FILE);
            if (f.exists() && f.canRead()) {
                Map<String, String> external = readMappingFile(f);
                if (!external.isEmpty()) {
                    merged.putAll(external);
                    break;
                }
            }
        }
        return merged;
    }

    private List<String> getMappingSearchPaths() {
        List<String> paths = new ArrayList<>();
        String userDir = System.getProperty("user.dir");
        if (userDir != null) paths.add(userDir);

        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            paths.add(userHome + File.separator + ".cleancore");
            paths.add(userHome);
        }

        String eclipseHome = System.getProperty("eclipse.home.location");
        if (eclipseHome != null) {
            String clean = eclipseHome.replace("file:/", "").replace("file:", "");
            paths.add(clean);
        }
        return paths;
    }

    private Map<String, String> readMappingFile(File file) {
        Map<String, String> map = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0 && eq < line.length() - 1) {
                    String table = line.substring(0, eq).trim().toUpperCase();
                    String cds = line.substring(eq + 1).trim();
                    if (!table.isEmpty() && !cds.isEmpty()) {
                        map.put(table, cds);
                    }
                }
            }
        } catch (Exception e) {
            // ignore - fall back to builtin mapping
        }
        return map;
    }

    private Finding enrichFinding(Finding f) {
        if (!DB_RULES.contains(f.getRuleId())) {
            return f;
        }
        String matched = f.getMatchedText().toUpperCase();
        for (String word : matched.split("[\\s,~@()*/]+")) {
            if (word.isEmpty() || word.length() < 2) continue;
            if (word.startsWith("Z") || word.startsWith("Y")) continue;
            if (word.startsWith("LT_") || word.startsWith("GT_")
                || word.startsWith("LS_") || word.startsWith("GS_")
                || word.startsWith("ET_") || word.startsWith("IT_")
                || word.startsWith("LV_") || word.startsWith("GV_")) continue;
            if (SKIP.contains(word)) continue;

            String cds = cdsMap.get(word);
            if (cds != null) {
                return new Finding(
                    f.getRuleId(), f.getRuleName(), f.getSeverity(), f.getCategory(),
                    f.getMessage(),
                    word + " -> " + cds,
                    f.getLineStart(), f.getLineEnd(),
                    f.getMatchedText(), cds
                );
            }
        }
        return f;
    }
}
