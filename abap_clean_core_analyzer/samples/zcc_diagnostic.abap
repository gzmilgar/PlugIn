*&---------------------------------------------------------------------*
*& Report ZCC_DIAGNOSTIC
*&---------------------------------------------------------------------*
*& Sistemdeki CDS kaynaklarini kontrol eder
*&---------------------------------------------------------------------*
REPORT zcc_diagnostic.

WRITE: / '══════ CDS DIAGNOSTIC REPORT ══════'.
SKIP.

* 1. TADIR - DDLS objects
DATA: lv_cnt TYPE i.
SELECT COUNT(*) FROM tadir WHERE object = 'DDLS' INTO @lv_cnt.
WRITE: / |1. TADIR DDLS objects (total): { lv_cnt }|.

SELECT COUNT(*) FROM tadir
  WHERE object = 'DDLS' AND obj_name LIKE 'I_%'
  INTO @lv_cnt.
WRITE: / |   DDLS starting with I_: { lv_cnt }|.

SELECT obj_name FROM tadir
  WHERE object = 'DDLS' AND obj_name LIKE 'I_P%'
  INTO TABLE @DATA(lt_sample1)
  UP TO 10 ROWS.
WRITE: / '   Sample I_P* DDLS:'.
LOOP AT lt_sample1 INTO DATA(ls1).
  WRITE: / |     { ls1-obj_name }|.
ENDLOOP.

SKIP.

* 2. DD26S - View base table relationships
SELECT COUNT(*) FROM dd26s WHERE tabname = 'EKKO' INTO @lv_cnt.
WRITE: / |2. DD26S entries for EKKO: { lv_cnt }|.

SELECT viewname, tabname FROM dd26s
  WHERE tabname = 'EKKO'
  INTO TABLE @DATA(lt_dd26)
  UP TO 10 ROWS.
LOOP AT lt_dd26 INTO DATA(ls26).
  WRITE: / |     { ls26-viewname } <- { ls26-tabname }|.
ENDLOOP.

SKIP.

* 3. DDLDEPENDENCY (dynamic)
TRY.
    SELECT COUNT(*) FROM ('DDLDEPENDENCY') INTO @lv_cnt.
    WRITE: / |3. DDLDEPENDENCY (total rows): { lv_cnt }|.

    DATA: lt_types TYPE TABLE OF string.
    SELECT DISTINCT ('OBJECTTYPE')
      FROM ('DDLDEPENDENCY')
      INTO TABLE @lt_types
      UP TO 20 ROWS.
    WRITE: / '   Object types:'.
    LOOP AT lt_types INTO DATA(ls_type).
      WRITE: / |     { ls_type }|.
    ENDLOOP.

    DATA lv_where TYPE string.
    lv_where = |OBJECTNAME = 'EKKO'|.
    SELECT ('DDLNAME') AS c1, ('OBJECTNAME') AS c2, ('OBJECTTYPE') AS c3
      FROM ('DDLDEPENDENCY')
      WHERE (lv_where)
      INTO TABLE @DATA(lt_dep_sample)
      UP TO 10 ROWS.
    WRITE: / |   DDLDEPENDENCY for EKKO: { lines( lt_dep_sample ) }|.
    LOOP AT lt_dep_sample INTO DATA(ls_dep).
      WRITE: / |     { ls_dep-c1 } | { ls_dep-c2 } | { ls_dep-c3 }|.
    ENDLOOP.
  CATCH cx_sy_dynamic_osql_error.
    WRITE: / '3. DDLDEPENDENCY: TABLE DOES NOT EXIST'.
  CATCH cx_root INTO DATA(lx1).
    WRITE: / |3. DDLDEPENDENCY error: { lx1->get_text( ) }|.
ENDTRY.

SKIP.

* 4. DDDDLSRC (dynamic)
TRY.
    SELECT COUNT(*) FROM ('DDDDLSRC') INTO @lv_cnt.
    WRITE: / |4. DDDDLSRC (total rows): { lv_cnt }|.

    lv_where = |DDLNAME LIKE 'I_PURCHASE%'|.
    SELECT ('DDLNAME')
      FROM ('DDDDLSRC')
      WHERE (lv_where)
      INTO TABLE @DATA(lt_src_sample)
      UP TO 10 ROWS.
    WRITE: / |   I_PURCHASE* sources: { lines( lt_src_sample ) }|.
    LOOP AT lt_src_sample INTO DATA(ls_src).
      WRITE: / |     { ls_src }|.
    ENDLOOP.
  CATCH cx_sy_dynamic_osql_error.
    WRITE: / '4. DDDDLSRC: TABLE DOES NOT EXIST'.
  CATCH cx_root INTO DATA(lx2).
    WRITE: / |4. DDDDLSRC error: { lx2->get_text( ) }|.
ENDTRY.

SKIP.

* 5. DD02L - Check if I_PurchaseOrder exists as a view
SELECT COUNT(*) FROM dd02l
  WHERE tabname LIKE 'I_PURCHASE%' AND tabclass = 'VIEW'
  INTO @lv_cnt.
WRITE: / |5. DD02L I_PURCHASE% views: { lv_cnt }|.

SELECT tabname, tabclass FROM dd02l
  WHERE tabname LIKE 'I_PURCHASE%'
  INTO TABLE @DATA(lt_dd02)
  UP TO 10 ROWS.
LOOP AT lt_dd02 INTO DATA(ls_dd02).
  WRITE: / |     { ls_dd02-tabname } ({ ls_dd02-tabclass })|.
ENDLOOP.

SKIP.
WRITE: / '══════ END DIAGNOSTIC ══════'.
