# ABAP Clean Core Analyzer

ABAP kaynak kodlarini analiz ederek Clean Core uyumlulugununu kontrol eden Eclipse ADT eklentisi ve Python CLI araci.

SAP S/4HANA Clean Core stratejisine gore custom ABAP kodlarindaki anti-pattern'leri tespit eder ve released CDS View / API onerilerini sunar.

## Ozellikler

- **22 Clean Core kurali** - Veritabani erisimi, API kullanimi, UI, obsolete syntax, mimari kontrolleri
- **Tablo -> CDS View eslesmesi** - 100+ SAP tablosu icin released CDS View onerisi
- **Spesifik sinif/metod onerileri** - CDS view'i olmayan tablolar icin alternatif ABAP Cloud API'leri
- **Harici mapping destegi** - SAP sisteminizden export edilen `cds_mapping.txt` ile genisletilebilir
- **3 cikti formati** - Console, HTML rapor, JSON (Python CLI)

## Eclipse ADT Plugin

### Kurulum

1. Bu repoyu klonlayin
2. Eclipse'te **File > Import > Existing Projects into Workspace**
3. `eclipse-plugin/com.cleancore.analyzer` klasorunu secin
4. **Run > Run As > Eclipse Application** ile test edin

**Kalici kurulum icin:**
```
File > Export > Plug-in Development > Deployable plug-ins
Olusturulan JAR'i Eclipse ADT'nin dropins/ klasorune kopyalayin
```

### Kullanim

- ABAP kaynak kodunu Eclipse ADT'de acin
- **Ctrl+Shift+K** veya menu: **Clean Core > Analyze Current File**
- Sonuclar "Clean Core Analysis Results" view'inda goruntulenir

### Ekran Goruntusu

```
+----------+-------+------------------------+------+------------------+-------------------------+
| Severity | Rule  | Name                   | Line | Matched Code     | Clean Core API          |
+----------+-------+------------------------+------+------------------+-------------------------+
| CRITICAL | CC001 | Direct SAP Table SELECT| 158  | SELECT * FROM T..| I_PurchaseOrder         |
| CRITICAL | CC005 | Direct SAP Table MODIFY| 1990 | MODIFY VBAP FROM | I_SalesOrderItem        |
| WARNING  | CC023 | Selection Screen       | 16   | SELECTION-SCREEN | Fiori Elements Filter   |
+----------+-------+------------------------+------+------------------+-------------------------+
```

## Kurallar

### Veritabani Erisimi (CRITICAL)
| Kural | Aciklama |
|-------|----------|
| CC001 | Direct SELECT on SAP standard table |
| CC002 | Direct INSERT on SAP standard table |
| CC003 | Direct UPDATE on SAP standard table |
| CC004 | Direct DELETE on SAP standard table |
| CC005 | Direct MODIFY on SAP standard table |
| CC006 | Native SQL (EXEC SQL) |

### API Kullanimi (CRITICAL/WARNING)
| Kural | Aciklama |
|-------|----------|
| CC010 | CALL TRANSACTION |
| CC011 | SUBMIT Report |
| CC012 | Kernel Call |
| CC013 | Dynamic Program Generation |
| CC014 | RFC Destination Usage |

### Kullanici Arayuzu (WARNING/INFO)
| Kural | Aciklama |
|-------|----------|
| CC020 | Classic ALV (REUSE_ALV) |
| CC021 | GUI Download/Upload |
| CC022 | Classic Dynpro |
| CC023 | Selection Screen |
| CC024 | WRITE Statement |
| CC025 | Popup Function Module |

### Obsolete Syntax (WARNING/INFO)
| Kural | Aciklama |
|-------|----------|
| CC030 | FORM/PERFORM Subroutine |
| CC031 | TABLES Declaration |
| CC032 | WITH HEADER LINE |
| CC033 | OCCURS Keyword |
| CC034 | Obsolete Arithmetic |

### Mimari (INFO)
| Kural | Aciklama |
|-------|----------|
| CC040 | Dynamic ASSIGN |

## Tablo -> CDS View Mapping (Ornekler)

| SAP Tablosu | Clean Core Alternatifi |
|-------------|----------------------|
| EKKO | I_PurchaseOrder |
| EKPO | I_PurchaseOrderItem |
| VBAK | I_SalesOrder |
| VBAP | I_SalesOrderItem |
| MARA | I_Product |
| KNA1 | I_Customer |
| LFA1 | I_Supplier |
| BKPF | I_JournalEntry |
| ACDOCA | I_JournalEntryItem |
| CAUFV | I_ManufacturingOrder |
| T100W | CL_MESSAGE_HELPER=>GET_TEXT( ) |
| E070 | CL_CTS_API=>READ_TRANSPORT_REQUEST( ) |
| USR02 | CL_ABAP_CONTEXT_INFO=>GET_USER_TECHNICAL_NAME( ) |

Tam liste icin: `ABAPAnalyzer.java` > `BUILTIN_MAP`

## Harici CDS Mapping

SAP sisteminize ozel mapping olusturmak icin:

1. `samples/zcc_cds_finder.abap` raporunu SAP sisteminizde olusturun
2. Raporu calistirin - DDLDEPENDENCY/DD26S uzerinden where-used list yapar
3. Sonucu `cds_mapping.txt` olarak export edin
4. Dosyayi su konuma koyun:
```
C:\Users\<kullanici>\.cleancore\cds_mapping.txt
```

Dosya formati:
```
EKKO=I_PurchaseOrder
MARA=I_Product
T100W=CL_MESSAGE_HELPER=>GET_TEXT( )
```

Plugin bu dosyayi otomatik okur ve hardcoded mapping'in uzerine yazar.

## Python CLI

```bash
# Tek dosya analizi
python main.py samples/zsample_legacy_report.abap

# HTML rapor
python main.py program.abap -f html -o rapor.html

# JSON cikti
python main.py src/ -f json -o sonuc.json

# Sadece CRITICAL
python main.py program.abap -s critical
```

## Proje Yapisi

```
abap_clean_core_analyzer/
├── eclipse-plugin/                     # Eclipse ADT Plugin (Java)
│   └── com.cleancore.analyzer/
│       ├── META-INF/MANIFEST.MF
│       ├── plugin.xml                  # Menu, kisayol, view tanimlari
│       └── src/com/cleancore/analyzer/
│           ├── Activator.java
│           ├── core/
│           │   ├── ABAPAnalyzer.java   # Analiz motoru + CDS mapping
│           │   ├── ABAPParser.java     # ABAP kaynak kod parser
│           │   ├── CleanCoreRules.java # 22 kural tanimi
│           │   ├── Rule.java           # Kural motoru (regex)
│           │   ├── Finding.java        # Bulgu veri sinifi
│           │   ├── Severity.java       # CRITICAL/WARNING/INFO
│           │   └── Category.java       # DB_ACCESS/API/UI/OBSOLETE
│           ├── handlers/
│           │   └── AnalyzeHandler.java # Ctrl+Shift+K komutu
│           └── ui/
│               └── CleanCoreResultView.java  # Sonuc tablosu
├── abap_analyzer/                      # Python CLI (ayni kurallar)
│   ├── parser.py
│   ├── rules.py
│   ├── analyzer.py
│   └── reporter.py
├── samples/
│   ├── zsample_legacy_report.abap      # Test icin ornek ABAP kodu
│   ├── zcc_cds_finder.abap            # SAP CDS mapping raporu
│   └── zcc_diagnostic.abap            # SAP sistem teshis raporu
├── main.py                             # Python CLI giris noktasi
└── requirements.txt                    # Bagimlilk yok (stdlib)
```

## Gereksinimler

**Eclipse Plugin:** Eclipse 2020+ with PDE, Java 11+
**Python CLI:** Python 3.8+ (harici bagimlilk yok)

## Lisans

MIT
