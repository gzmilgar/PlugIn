*&---------------------------------------------------------------------*
*& Report ZSAMPLE_LEGACY_REPORT
*&---------------------------------------------------------------------*
*& Example ABAP program with non-Clean-Core patterns
*& Used for testing the ABAP Clean Core Analyzer
*&---------------------------------------------------------------------*
REPORT zsample_legacy_report.

TABLES: mara, marc.

PARAMETERS: p_matnr TYPE matnr,
            p_werks TYPE werks_d.

SELECT-OPTIONS: s_mtart FOR mara-mtart.

SELECTION-SCREEN BEGIN OF BLOCK b1 WITH FRAME TITLE TEXT-001.
  PARAMETERS: p_check AS CHECKBOX DEFAULT 'X'.
SELECTION-SCREEN END OF BLOCK b1.

DATA: lt_mara TYPE TABLE OF mara WITH HEADER LINE,
      ls_marc TYPE marc,
      lv_count TYPE i.

START-OF-SELECTION.

  SELECT * FROM mara
    INTO TABLE lt_mara
    WHERE matnr = p_matnr
      AND mtart IN s_mtart.

  SELECT SINGLE * FROM marc
    INTO @ls_marc
    WHERE matnr = @p_matnr
      AND werks = @p_werks.

  PERFORM process_data.
  PERFORM display_results.

  CALL FUNCTION 'GUI_DOWNLOAD'
    EXPORTING
      filename = 'C:\temp\output.txt'
    TABLES
      data_tab = lt_mara.

  CALL TRANSACTION 'MM03' AND SKIP FIRST SCREEN.

  SUBMIT rm07mlbs WITH matnr = p_matnr AND RETURN.

*&---------------------------------------------------------------------*
*& Form process_data
*&---------------------------------------------------------------------*
FORM process_data.
  DATA: ls_mara TYPE mara.

  LOOP AT lt_mara INTO ls_mara.
    ls_mara-aenam = sy-uname.
    MODIFY lt_mara FROM ls_mara.
  ENDLOOP.

  MODIFY mara FROM TABLE lt_mara.

  ADD 1 TO lv_count.
ENDFORM.

*&---------------------------------------------------------------------*
*& Form display_results
*&---------------------------------------------------------------------*
FORM display_results.
  CALL FUNCTION 'REUSE_ALV_GRID_DISPLAY'
    EXPORTING
      i_structure_name = 'MARA'
    TABLES
      t_outtab         = lt_mara.

  WRITE: / 'Processing completed'.
  WRITE: / 'Items processed:', lv_count.
ENDFORM.
