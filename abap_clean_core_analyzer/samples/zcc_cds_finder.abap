*&---------------------------------------------------------------------*
*& Report ZCC_CDS_FINDER
*&---------------------------------------------------------------------*
*& SAP sisteminizde tablo -> released CDS View eslesmesini olusturur.
*& DDL dependency tablosundan where-used list yaparak
*& hangi CDS view'in hangi tabloyu kullandigini bulur.
*& Sonucu Eclipse plugin icin dosya olarak export edebilirsiniz.
*&
*& Gereksinim: S/4HANA 1909+ veya ABAP Platform 7.50+
*&---------------------------------------------------------------------*
REPORT zcc_cds_finder.

TYPES: BEGIN OF ty_result,
         table_name  TYPE c LENGTH 30,
         cds_view    TYPE c LENGTH 40,
         cds_descr   TYPE c LENGTH 60,
         released    TYPE c LENGTH 12,
         dep_count   TYPE i,
         score       TYPE i,
       END OF ty_result,
       tt_result TYPE STANDARD TABLE OF ty_result WITH EMPTY KEY.

TYPES: BEGIN OF ty_export,
         table_name TYPE c LENGTH 30,
         cds_view   TYPE c LENGTH 40,
       END OF ty_export.

DATA: gt_result  TYPE tt_result,
      gt_best    TYPE tt_result,
      gt_export  TYPE TABLE OF ty_export.

*----------------------------------------------------------------------*
* Selection Screen
*----------------------------------------------------------------------*
SELECTION-SCREEN BEGIN OF BLOCK b1 WITH FRAME TITLE TEXT-001.
  PARAMETERS:     p_table TYPE c LENGTH 30 DEFAULT '*' LOWER CASE.
  PARAMETERS:     p_rel   TYPE abap_bool AS CHECKBOX DEFAULT abap_true.
SELECTION-SCREEN END OF BLOCK b1.

SELECTION-SCREEN BEGIN OF BLOCK b2 WITH FRAME TITLE TEXT-002.
  PARAMETERS:     p_down  TYPE abap_bool AS CHECKBOX DEFAULT space.
  PARAMETERS:     p_path  TYPE string DEFAULT 'C:\temp\cds_mapping.txt' LOWER CASE.
SELECTION-SCREEN END OF BLOCK b2.

*----------------------------------------------------------------------*
INITIALIZATION.
  TEXT-001 = 'Arama Parametreleri'.
  TEXT-002 = 'Export'.

*----------------------------------------------------------------------*
START-OF-SELECTION.
  PERFORM find_cds_views.
  PERFORM check_release_state.
  PERFORM calculate_scores.
  PERFORM pick_best_per_table.
  PERFORM display_results.
  IF p_down = abap_true.
    PERFORM download_mapping.
  ENDIF.

*&---------------------------------------------------------------------*
*& Form find_cds_views
*&---------------------------------------------------------------------*
FORM find_cds_views.

  DATA: lv_upper TYPE c LENGTH 30,
        lv_count TYPE i.

  IF p_table <> '*'.
    lv_upper = to_upper( p_table ).
  ENDIF.

* ── Yontem 1: DDLDEPENDENCY (S/4HANA 1909+) ──────────────────────
  TRY.
      DATA: lv_where_dep TYPE string.
      IF p_table = '*'.
        lv_where_dep = |OBJECTTYPE = 'TABL'|
          && | AND OBJECTNAME NOT LIKE 'Z%'|
          && | AND OBJECTNAME NOT LIKE 'Y%'|.
      ELSE.
        lv_where_dep = |OBJECTTYPE = 'TABL' AND OBJECTNAME = '{ lv_upper }'|.
      ENDIF.

      SELECT ('DDLNAME') AS cds_view, ('OBJECTNAME') AS table_name
        FROM ('DDLDEPENDENCY')
        WHERE (lv_where_dep)
        INTO CORRESPONDING FIELDS OF TABLE @gt_result.

      DELETE gt_result WHERE cds_view(2) <> 'I_' AND cds_view(2) <> 'C_'.
      lv_count = lines( gt_result ).
      IF lv_count > 0.
        WRITE: / |Yontem 1 (DDLDEPENDENCY): { lv_count } sonuc.|.
        RETURN.
      ENDIF.
    CATCH cx_sy_dynamic_osql_error cx_root.
      WRITE: / 'DDLDEPENDENCY bulunamadi, alternatif deneniyor...'.
  ENDTRY.

* ── Yontem 2: DD26S (DDIC view-table dependencies) ───────────────
  IF p_table = '*'.
    SELECT viewname AS cds_view, tabname AS table_name
      FROM dd26s
      WHERE viewname LIKE 'I_%'
        AND tabname NOT LIKE 'Z%'
        AND tabname NOT LIKE 'Y%'
      INTO CORRESPONDING FIELDS OF TABLE @gt_result
      UP TO 50000 ROWS.
  ELSE.
    SELECT viewname AS cds_view, tabname AS table_name
      FROM dd26s
      WHERE tabname = @lv_upper
        AND ( viewname LIKE 'I_%' OR viewname LIKE 'C_%' )
      INTO CORRESPONDING FIELDS OF TABLE @gt_result.
  ENDIF.

  lv_count = lines( gt_result ).
  IF lv_count > 0.
    WRITE: / |Yontem 2 (DD26S): { lv_count } sonuc.|.
    RETURN.
  ENDIF.

* ── Yontem 3: DD02L + TADIR (CDS entity name = SQL view name) ────
  IF p_table <> '*'.
    DATA: lt_ddls TYPE TABLE OF ty_result.
    SELECT obj_name
      FROM tadir
      WHERE pgmid  = 'R3TR'
        AND object = 'DDLS'
        AND ( obj_name LIKE 'I_%' OR obj_name LIKE 'C_%' )
      INTO TABLE @DATA(lt_names)
      UP TO 5000 ROWS.

    WRITE: / |Yontem 3 (TADIR+DDDDLSRC text search): { lines( lt_names ) } CDS source taranacak...|.

    DATA: lv_search TYPE string.
    lv_search = to_lower( lv_upper ).

    LOOP AT lt_names INTO DATA(ls_name).
      DATA: lv_src TYPE string.
      CLEAR lv_src.
      TRY.
          SELECT SINGLE ('SOURCE')
            FROM ('DDDDLSRC')
            WHERE ('DDLNAME') = @ls_name-obj_name
              AND ('AS4LOCAL') = 'A'
            INTO @lv_src.
        CATCH cx_sy_dynamic_osql_error cx_root.
          EXIT.
      ENDTRY.
      IF lv_src IS NOT INITIAL.
        DATA(lv_src_lower) = to_lower( lv_src ).
        IF lv_src_lower CS lv_search.
          APPEND VALUE ty_result(
            table_name = lv_upper
            cds_view   = ls_name-obj_name
          ) TO gt_result.
        ENDIF.
      ENDIF.
    ENDLOOP.
  ENDIF.

  lv_count = lines( gt_result ).
  WRITE: / |{ lv_count } CDS dependency found.|.

ENDFORM.

*&---------------------------------------------------------------------*
*& Form check_release_state
*&---------------------------------------------------------------------*
FORM check_release_state.

  DATA: lv_descr TYPE c LENGTH 60,
        lv_where TYPE string,
        lv_state TYPE c LENGTH 12,
        lv_rel_available TYPE abap_bool VALUE abap_false.

  LOOP AT gt_result ASSIGNING FIELD-SYMBOL(<r>).

    CLEAR: lv_descr, lv_state.
    <r>-released = 'N/A'.

    IF lv_rel_available = abap_true OR sy-index = 1.
      lv_where = |OBJECTTYPE = 'DDLS' AND OBJECTNAME = '{ <r>-cds_view }'|.
      TRY.
          SELECT SINGLE ('RELEASESTATE')
            FROM ('I_APIRELEASEDSTATE')
            WHERE (lv_where)
            INTO @lv_state.
          lv_rel_available = abap_true.
          <r>-released = COND #( WHEN sy-subrc = 0 AND lv_state IS NOT INITIAL
                                THEN lv_state ELSE 'NOT_REL' ).
        CATCH cx_sy_dynamic_osql_error cx_root.
          lv_where = |OBJECT_TYPE = 'DDLS' AND OBJECT_NAME = '{ <r>-cds_view }'|.
          TRY.
              SELECT SINGLE ('RELEASE_STATE')
                FROM ('ARS_W_API_STATE')
                WHERE (lv_where)
                INTO @lv_state.
              lv_rel_available = abap_true.
              <r>-released = COND #( WHEN sy-subrc = 0 AND lv_state IS NOT INITIAL
                                    THEN lv_state ELSE 'NOT_REL' ).
            CATCH cx_sy_dynamic_osql_error cx_root.
              lv_rel_available = abap_false.
          ENDTRY.
      ENDTRY.
    ENDIF.

    SELECT SINGLE ddtext
      FROM dd25t
      WHERE viewname   = @<r>-cds_view
        AND ddlanguage = @sy-langu
      INTO @lv_descr.
    IF sy-subrc <> 0.
      SELECT SINGLE ddtext
        FROM dd25t
        WHERE viewname   = @<r>-cds_view
          AND ddlanguage = 'E'
        INTO @lv_descr.
    ENDIF.
    <r>-cds_descr = lv_descr.

  ENDLOOP.

  IF lv_rel_available = abap_false.
    WRITE: / 'Release state bilgisi bulunamadi - tum CDS viewler listeleniyor.'.
    p_rel = abap_false.
  ENDIF.

  IF p_rel = abap_true.
    DELETE gt_result WHERE released <> 'RELEASED'.
  ENDIF.

  WRITE: / |{ lines( gt_result ) } entries after filter.|.

ENDFORM.

*&---------------------------------------------------------------------*
*& Form calculate_scores
*&---------------------------------------------------------------------*
FORM calculate_scores.

  DATA: lt_dep_count TYPE TABLE OF ty_result.

  LOOP AT gt_result ASSIGNING FIELD-SYMBOL(<r>).
    <r>-score = 0.

    IF <r>-released = 'RELEASED'.
      <r>-score = <r>-score + 100.
    ENDIF.

    IF <r>-cds_view CP 'I_*'.
      <r>-score = <r>-score + 20.
    ENDIF.

    IF <r>-cds_view CP 'C_*'.
      <r>-score = <r>-score + 10.
    ENDIF.

    IF strlen( <r>-cds_view ) < 25.
      <r>-score = <r>-score + 5.
    ENDIF.

    SELECT COUNT(*)
      FROM ddldependency
      WHERE ddlname = @<r>-cds_view
        AND objecttype = 'TABL'
      INTO @<r>-dep_count.

    IF <r>-dep_count <= 2.
      <r>-score = <r>-score + 30.
    ELSEIF <r>-dep_count <= 5.
      <r>-score = <r>-score + 10.
    ENDIF.

    IF <r>-cds_view NS 'TP' AND <r>-cds_view NS 'Cube'
       AND <r>-cds_view NS 'Query' AND <r>-cds_view NS 'Anal'.
      <r>-score = <r>-score + 15.
    ENDIF.
  ENDLOOP.

  SORT gt_result BY table_name score DESCENDING.

ENDFORM.

*&---------------------------------------------------------------------*
*& Form pick_best_per_table
*&---------------------------------------------------------------------*
FORM pick_best_per_table.

  DATA: lv_prev_table TYPE c LENGTH 30.

  CLEAR gt_best.
  LOOP AT gt_result INTO DATA(ls_row).
    IF ls_row-table_name <> lv_prev_table.
      APPEND ls_row TO gt_best.
      lv_prev_table = ls_row-table_name.
    ENDIF.
  ENDLOOP.

  WRITE: / |{ lines( gt_best ) } unique table-to-CDS mappings found.|.

ENDFORM.

*&---------------------------------------------------------------------*
*& Form display_results
*&---------------------------------------------------------------------*
FORM display_results.

  TRY.
      DATA: lo_alv TYPE REF TO cl_salv_table.
      cl_salv_table=>factory(
        IMPORTING r_salv_table = lo_alv
        CHANGING  t_table = gt_best ).

      lo_alv->get_functions( )->set_all( ).
      lo_alv->get_columns( )->set_optimize( ).

      DATA(lo_cols) = lo_alv->get_columns( ).
      TRY.
          lo_cols->get_column( 'TABLE_NAME' )->set_short_text( 'Table' ).
          lo_cols->get_column( 'CDS_VIEW' )->set_short_text( 'CDS View' ).
          lo_cols->get_column( 'CDS_DESCR' )->set_short_text( 'Descr.' ).
          lo_cols->get_column( 'RELEASED' )->set_short_text( 'Released' ).
          lo_cols->get_column( 'DEP_COUNT' )->set_short_text( 'Deps' ).
          lo_cols->get_column( 'SCORE' )->set_short_text( 'Score' ).
        CATCH cx_salv_not_found.
      ENDTRY.

      DATA(lo_sorts) = lo_alv->get_sorts( ).
      TRY.
          lo_sorts->add_sort( 'TABLE_NAME' ).
        CATCH cx_salv_not_found cx_salv_existing cx_salv_data_error.
      ENDTRY.

      lo_alv->display( ).

    CATCH cx_salv_error INTO DATA(lx).
      MESSAGE lx->get_text( ) TYPE 'E'.
  ENDTRY.

ENDFORM.

*&---------------------------------------------------------------------*
*& Form download_mapping
*&---------------------------------------------------------------------*
FORM download_mapping.

  DATA: lt_lines TYPE TABLE OF string,
        lv_filename TYPE string.

  LOOP AT gt_best INTO DATA(ls_row).
    APPEND |{ ls_row-table_name }={ ls_row-cds_view }| TO lt_lines.
  ENDLOOP.

  lv_filename = p_path.

  cl_gui_frontend_services=>gui_download(
    EXPORTING
      filename = lv_filename
    CHANGING
      data_tab = lt_lines
    EXCEPTIONS
      OTHERS   = 1 ).

  IF sy-subrc = 0.
    MESSAGE |{ lines( lt_lines ) } mapping exported to { lv_filename }| TYPE 'S'.
  ELSE.
    MESSAGE |Export failed.| TYPE 'E'.
  ENDIF.

ENDFORM.
