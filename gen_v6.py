import json, copy

with open(r'C:\Users\10119350\Downloads\Jumbo_OCR_purchaseOrder_schema_CORRECTED_v5.json', 'r', encoding='utf-8') as f:
    v5 = json.load(f)

print("=== V5 DESCRIPTION LENGTHS ===")
print("--- headerFields ---")
for fld in v5['headerFields']:
    d = fld['description']
    flag = " <<OVER 500!>>" if len(d) >= 500 else ""
    print(f"  {fld['name']}: {len(d)}{flag}")
print("--- lineItemFields ---")
for fld in v5['lineItemFields']:
    d = fld['description']
    flag = " <<OVER 500!>>" if len(d) >= 500 else ""
    print(f"  {fld['name']}: {len(d)}{flag}")

NEW_DESC = {
    "purchaseOrder": (
        "PO number in top-right header.\n"
        "LABELS: Purchase Order No.\n"
        "POS: top-right, above Purchase Order Date.\n"
        "FORMAT: 7-10 digit numeric.\n"
        "TRADE ex: 1000000688 (starts 1). RETAIL ex: 3200000060, 3200000189, 3200000107 (starts 32).\n"
        "SKIP: Ref No (alphanumeric like 160426NP01, or dash in RETAIL), Exchange Rate."
    ),
    "taxId": (
        "Company TRN from top-left letterhead, labeled TRN: (no space before colon).\n"
        "LABELS: TRN: in letterhead area.\n"
        "POS: top-left, below company name and address.\n"
        "FORMAT: 15-digit numeric. Ex: 100021104300003.\n"
        "TRADE & RETAIL: same value and label in both variants.\n"
        "SKIP: SUPPLIER INFO TRN (labeled TRN : with space, goes to taxIdNumber)."
    ),
    "netAmount": (
        "Doc-level total excl. VAT. SAP: netAmount = excl. tax.\n"
        "LABELS: Total Amount column in Total Quantity summary row.\n"
        "POS: summary row, 3rd-to-last column (before VAT Amount, before Amt Incl. VAT).\n"
        "FORMAT: plain decimal, no comma. Ex: 14004.09.\n"
        "TRADE ex: 14004.09 (2 items). RETAIL ex: 6513.47, 13027.76, 8956.33 (1 item).\n"
        "SKIP: line item cells (use comma separator, e.g. 5,047.76).\n"
        "Note: VAT%=0, equals grossAmount."
    ),
    "grossAmount": (
        "Doc-level total incl. VAT. SAP: grossAmount = incl. tax.\n"
        "LABELS: Amount Including VAT, last/rightmost column of Total Quantity summary row.\n"
        "POS: summary row, rightmost amount column.\n"
        "FORMAT: plain decimal, no comma.\n"
        "TRADE ex: 14004.09 (2 items). RETAIL ex: 6513.47, 13027.76, 8956.33 (1 item).\n"
        "SKIP: individual line item rows.\n"
        "Note: VAT%=0, equals netAmount."
    ),
    "currencyCode": (
        "Currency code from Currency : field in top-right header.\n"
        "LABELS: Currency : (with space before colon).\n"
        "POS: top-right, below Payment Terms, above Exchange Rate.\n"
        "FORMAT: 3-letter ISO 4217. Ex: AED (all 4 samples: 1000000688, 3200000060, 3200000189, 3200000107).\n"
        "TRADE & RETAIL: same label and position.\n"
        "SKIP: Exchange Rate (numeric 1.00 on next line)."
    ),
    "documentDate": (
        "PO creation date from Purchase Order Date : field in top-right header.\n"
        "LABELS: Purchase Order Date : (with space before colon).\n"
        "POS: top-right, below Purchase Order No.\n"
        "FORMAT: YYYY-MM-DD (in and out).\n"
        "TRADE ex: 2026-04-16 (1000000688). RETAIL ex: 2026-04-09 (3200000060), 2026-04-16 (3200000189), 2026-04-13 (3200000107).\n"
        "SKIP: Ref No (160426NP01 or dash in RETAIL), line item Valid Till dates."
    ),
    "deliveryDate": (
        "Expected delivery date in document header. ALWAYS EMPTY - no header-level delivery date field in any Jumbo PO variant.\n"
        "DO NOT use line item Valid Till dates (those go to DeliveryDate line item field).\n"
        "TRADE: NOT PRESENT. RETAIL: NOT PRESENT.\n"
        "Leave empty (null)."
    ),
    "paymentTerms": (
        "Payment terms from Payment Terms : field in top-right header.\n"
        "LABELS: Payment Terms : (with space before colon).\n"
        "POS: top-right, below Ref No.\n"
        "FORMAT: free text. Ex: 30 Days Net (all 4 samples).\n"
        "TRADE & RETAIL: same label and value in both variants.\n"
        "SKIP: Shipping Terms : label (below Payment Terms; value EMPTY in all samples)."
    ),
    "taxIdNumber": (
        "Supplier TRN from SUPPLIER INFO boxed block, labeled TRN : (with space before colon).\n"
        "LABELS: TRN : inside SUPPLIER INFO section.\n"
        "POS: inside SUPPLIER INFO block, below supplier name.\n"
        "FORMAT: 15-digit numeric. Ex: 100021104300003.\n"
        "TRADE & RETAIL: same block structure and label in both variants.\n"
        "SKIP: company letterhead TRN (labeled TRN: without space, goes to taxId)."
    ),
    "receiverId": (
        "Primary delivery site code for entire order, from Site column of line items table.\n"
        "When multi-item (TRADE LOCAL has 2 items): all items share same site, extract shared value.\n"
        "FORMAT: alphanumeric, starts with JRET-.\n"
        "TRADE ex: JRET-Mall Of Emirates-SR (physical store, 1000000688).\n"
        "RETAIL ex: JRET-Virtual Supplier (virtual node, 3200000060, 3200000189, 3200000107).\n"
        "SKIP: Delivery Location : address block on page 2 (postal address, not site code)."
    ),
    "quantity": (
        "Total order quantity from Total Quantity summary row, Quantity column (first numeric in row).\n"
        "DO NOT sum individual line item quantities, use only the printed summary row value.\n"
        "FORMAT: integer or decimal, no thousand separator.\n"
        "TRADE ex: 2 (2 items, each qty 1, in 1000000688).\n"
        "RETAIL ex: 1 (1 item, qty 1, in 3200000060, 3200000189, 3200000107).\n"
        "TRADE & RETAIL: same row label Total Quantity."
    ),
    "discount": (
        "Discount total if printed in document. ALWAYS EMPTY - not present in any Jumbo PO variant.\n"
        "No discount label or column in header or line items table.\n"
        "TRADE: NOT PRESENT (1000000688).\n"
        "RETAIL: NOT PRESENT (3200000060, 3200000189, 3200000107).\n"
        "Leave empty (null)."
    ),
    "totalVAT": (
        "Total VAT from Total Quantity summary row, VAT Amount column (2nd-to-last column).\n"
        "FORMAT: decimal. Ex: 0.00 (VAT%=0 in all 4 samples).\n"
        "POS: summary row, VAT Amount column.\n"
        "TRADE: 0.00 (1000000688). RETAIL: 0.00 (3200000060, 3200000189, 3200000107).\n"
        "SKIP: individual line item VAT Amount cells (those go to VATValue line item field)."
    ),
    "vendorNo": (
        "Vendor ERP ID from SUPPLIER INFO section, if printed. ALWAYS EMPTY - not printed in any of 4 known Jumbo PO samples.\n"
        "SUPPLIER INFO block has name, address, phone, TRN but no vendor number.\n"
        "TRADE: NOT VISIBLE (1000000688).\n"
        "RETAIL: NOT VISIBLE (3200000060, 3200000189, 3200000107).\n"
        "Leave empty (null)."
    ),
    "vendorAdress": (
        "Full vendor address from SUPPLIER INFO boxed block on page 1.\n"
        "LABELS: below Attn: label in SUPPLIER INFO.\n"
        "POS: inside SUPPLIER INFO block.\n"
        "FORMAT: multi-line (name, address, phone, TRN). Ex: JUMBO ELECTRONICS CO. LTD. (L.L.C.) P.O. BOX 3426, Al Gurg Building... TRN : 100021104300003.\n"
        "TRADE & RETAIL: same block structure and content in all 4 samples.\n"
        "Note: field named vendorAdress (typo kept); maps to SAP senderAddress."
    ),
    "deliveryAdress": (
        "Full delivery address from page 2 under Delivery Location : label.\n"
        "LABELS: Delivery Location : (page 2).\n"
        "POS: page 2, below amount-in-words and Instruction lines.\n"
        "FORMAT: multi-line text.\n"
        "TRADE ex: physical Jumbo store (1000000688): JRET-Mall Of Emirates-SR, Shaikh Zayed Rd, Dubai UAE.\n"
        "RETAIL ex: personal home address: MARYAM, P.O.BOX MCM356892, dubai UAE.\n"
        "Note: typo in field name kept; maps to SAP receiverAddress."
    ),
    "validity": (
        "Order validity date if printed in document header. ALWAYS EMPTY - no header-level validity date in any Jumbo PO variant.\n"
        "DO NOT use line item Valid Till dates (those go to DeliveryDate line item field).\n"
        "TRADE: NOT PRESENT. RETAIL: NOT PRESENT.\n"
        "Leave empty (null) for all current Jumbo PO formats."
    ),
    "description_li": (
        "Product name from Description column, LINE 2 of cell only.\n"
        "CELL STRUCTURE: Line 1 = model code (skip); Line 2 = product name (EXTRACT).\n"
        "Ex: 55 Inch OLED TV, 65 Inch QD OLED 4K TV, 75 Mini LED 4K TV, 75 Inch QLED MINILED 4K TV.\n"
        "TRADE: 2 items (S.No 10, 20) extract per row. RETAIL: 1 item (S.No 10 only).\n"
        "SKIP: Total Quantity summary rows (no S.No)."
    ),
    "netAmount_li": (
        "Line item total excl. VAT. SAP: netAmount = excl. tax.\n"
        "COL: Total Amount (3rd-to-last, before VAT Amount and Amt Incl. VAT).\n"
        "FORMAT: comma-separated in PDF (e.g. 5,047.76), extract as plain decimal (5047.76).\n"
        "Only from rows with valid S.No.\n"
        "TRADE ex: 5047.76 (S.No 10), 8956.33 (S.No 20) in 1000000688. RETAIL ex: 6513.47, 13027.76, 8956.33.\n"
        "SKIP: Total Quantity summary row (goes to header netAmount).\n"
        "Note: VAT%=0, equals grossAmount."
    ),
    "quantity_li": (
        "Item quantity from Quantity column.\n"
        "FORMAT: integer or decimal, > 0. Ex: 1 EA in all observed samples.\n"
        "TRADE: 2 items (each qty 1). RETAIL: 1 item (qty 1).\n"
        "SKIP: Total Quantity summary row (no valid S.No).\n"
        "TRADE & RETAIL: same column labeled Quantity."
    ),
    "unitPrice": (
        "Price per unit from Unit Price column.\n"
        "FORMAT: comma-separated in PDF (e.g. 5,047.76), extract as plain decimal (5047.76).\n"
        "Only from rows with valid S.No. Skip Total Quantity and summary rows.\n"
        "TRADE: 2 items with different prices (5047.76, 8956.33 in 1000000688). RETAIL: 1 item.\n"
        "Note: qty=1 in all samples, so unitPrice = netAmount per line.\n"
        "TRADE & RETAIL: same column labeled Unit Price."
    ),
    "materialNumber": (
        "Model/article code from Article Code column, LINE 1 of cell only.\n"
        "CELL STRUCTURE: Line 1 = model code (EXTRACT); Line 2 = EAN barcode (goes to barcode field).\n"
        "If combined (K-55XR80/4548736160460), extract part before slash.\n"
        "FORMAT: alphanumeric. Ex: K-55XR80, K-65XR80M2, K-75XR50, K-75XR90.\n"
        "TRADE: 2 items (K-55XR80, K-65XR80M2 in 1000000688). RETAIL: 1 item (K-75XR50 in 3200000060, K-75XR90 in 3200000189, K-65XR80M2 in 3200000107)."
    ),
    "itemNumber": (
        "Sequential line item number from S.No column. Confirms a valid row.\n"
        "FORMAT: numeric, multiples of 10 (10, 20, 30...).\n"
        "TRADE: 2 items, S.No 10 and 20 (1000000688). RETAIL: 1 item, S.No 10 only (3200000060, 3200000189, 3200000107).\n"
        "SKIP: rows without S.No (Total Quantity summary row is NOT a line item)."
    ),
    "unitOfMeasure": (
        "UOM from UOM column. FORMAT: standard abbreviation.\n"
        "Ex: EA (confirmed in all 4 samples: 1000000688, 3200000060, 3200000189, 3200000107). Other: PCS, BOX.\n"
        "Only from rows with valid S.No.\n"
        "TRADE & RETAIL: EA confirmed in both variants, column labeled UOM."
    ),
    "grossAmount_li": (
        "Line item total incl. VAT. SAP: grossAmount = incl. tax.\n"
        "COL: Amount Including VAT (last/rightmost amount column).\n"
        "FORMAT: comma-separated in PDF (e.g. 5,047.76), extract as plain decimal (5047.76).\n"
        "Only from rows with valid S.No. Skip Total Quantity summary row.\n"
        "TRADE ex: 5047.76 (S.No 10), 8956.33 (S.No 20) in 1000000688. RETAIL ex: 6513.47, 13027.76, 8956.33.\n"
        "Note: VAT%=0, equals netAmount."
    ),
    "VATValue": (
        "VAT amount per line item from VAT Amount column (2nd-to-last column).\n"
        "FORMAT: decimal. Ex: 0.00 (VAT%=0 in all 4 samples).\n"
        "Only from rows with valid S.No.\n"
        "TRADE: 0.00 per item. RETAIL: 0.00 per item.\n"
        "SKIP: Total Quantity summary row (that goes to header totalVAT).\n"
        "Note: VATValue field name kept for compatibility; maps to SAP tax.amount."
    ),
    "DeliveryDate": (
        "Price validity date per line item from Valid Till column (NOT physical delivery date).\n"
        "LABELS: Valid Till (both variants).\n"
        "INPUT FORMAT: Mon DD, YYYY (e.g. Apr 16, 2026). OUTPUT: YYYY-MM-DD.\n"
        "TRADE: 2 items, extract Valid Till per row (both 2026-04-16 in 1000000688). RETAIL: 1 item.\n"
        "SKIP: summary rows. NOTE: header deliveryDate always empty, do NOT copy here.\n"
        "Note: DeliveryDate field name kept for compatibility; maps to SAP deliveryDate."
    ),
    "barcode": (
        "EAN barcode from Article Code column, LINE 2 of cell only.\n"
        "CELL STRUCTURE: Line 1 = model code (materialNumber); Line 2 = EAN-13 barcode (EXTRACT HERE).\n"
        "If combined (K-55XR80/4548736160460), extract numeric part after slash.\n"
        "FORMAT: 13-digit EAN-13. Ex: 4548736160460, 4548736169210, 4548736160453, 4548736170513.\n"
        "TRADE: 2 items (4548736160460 for K-55XR80, 4548736170513 for K-65XR80M2 in 1000000688). RETAIL: 1 item.\n"
        "Only from rows with valid S.No."
    ),
    "deliveryLocation": (
        "Delivery site code per line item from Site column.\n"
        "FORMAT: alphanumeric, starts with JRET-.\n"
        "TRADE ex: JRET-Mall Of Emirates-SR (physical store, 1000000688), both rows share same site.\n"
        "RETAIL ex: JRET-Virtual Supplier (virtual node, 3200000060, 3200000189, 3200000107).\n"
        "Only from rows with valid S.No.\n"
        "SKIP: Delivery Location : address block on page 2 (postal address, not site code).\n"
        "TRADE & RETAIL: column labeled Site in both."
    ),
}

# Build v6
v6 = copy.deepcopy(v5)
v6['version'] = '6'

LI_DUAL = {'netAmount': 'netAmount_li', 'grossAmount': 'grossAmount_li', 'quantity': 'quantity_li', 'description': 'description_li'}

for fld in v6['headerFields']:
    if fld['name'] in NEW_DESC:
        fld['description'] = NEW_DESC[fld['name']]

for fld in v6['lineItemFields']:
    key = LI_DUAL.get(fld['name'], fld['name'])
    if key in NEW_DESC:
        fld['description'] = NEW_DESC[key]

out_path = r'C:\Users\10119350\Downloads\Jumbo_OCR_purchaseOrder_schema_CORRECTED_v6.json'
with open(out_path, 'w', encoding='utf-8') as f:
    json.dump(v6, f, ensure_ascii=False, indent=2)
print(f"\nv6 written to: {out_path}")

# VALIDATION
with open(out_path, 'r', encoding='utf-8') as f:
    v6c = json.load(f)
print(f"json.load: OK | version: {v6c['version']} | header: {len(v6c['headerFields'])} | lineItem: {len(v6c['lineItemFields'])}")

all_ok = True; max_len = 0; max_field = ""; total_v5 = 0; total_v6 = 0

print("\n--- headerFields v5->v6 ---")
for f5, f6 in zip(v5['headerFields'], v6c['headerFields']):
    l5, l6 = len(f5['description']), len(f6['description'])
    total_v5 += l5; total_v6 += l6
    flag = " <<OVER!>>" if l6 >= 500 else ""
    if l6 >= 500: all_ok = False
    if l6 > max_len: max_len = l6; max_field = f6['name']
    assert f5['name'] == f6['name']
    print(f"  {f6['name']}: {l5} -> {l6}{flag}")

print("\n--- lineItemFields v5->v6 ---")
for f5, f6 in zip(v5['lineItemFields'], v6c['lineItemFields']):
    l5, l6 = len(f5['description']), len(f6['description'])
    total_v5 += l5; total_v6 += l6
    flag = " <<OVER!>>" if l6 >= 500 else ""
    if l6 >= 500: all_ok = False
    if l6 > max_len: max_len = l6; max_field = f6['name']
    assert f5['name'] == f6['name']
    print(f"  {f6['name']}: {l5} -> {l6}{flag}")

print(f"\nAll < 500: {'YES' if all_ok else 'NO -- CHECK ABOVE'}")
print(f"Longest: {max_field} ({max_len} chars)")
print(f"Total chars: v5={total_v5} v6={total_v6} reduction={total_v5-total_v6}")
avg_v5 = total_v5 / 29
avg_v6 = total_v6 / 29
print(f"Avg chars: v5={avg_v5:.0f} v6={avg_v6:.0f}")

for f5, f6 in zip(v5['headerFields'], v6c['headerFields']):
    assert f5['setupType'] == f6['setupType'], f"setupType changed: {f5['name']}"
    assert f5['setup'] == f6['setup'], f"setup changed: {f5['name']}"
    assert f5['formattingType'] == f6['formattingType'], f"formattingType changed: {f5['name']}"
    assert f5.get('defaultExtractor') == f6.get('defaultExtractor'), f"defaultExtractor changed: {f5['name']}"
for f5, f6 in zip(v5['lineItemFields'], v6c['lineItemFields']):
    assert f5['setupType'] == f6['setupType'], f"setupType changed: {f5['name']}"
    assert f5['setup'] == f6['setup'], f"setup changed: {f5['name']}"
    assert f5['formattingType'] == f6['formattingType'], f"formattingType changed: {f5['name']}"
    assert f5.get('defaultExtractor') == f6.get('defaultExtractor'), f"defaultExtractor changed: {f5['name']}"
print("setup.* / formattingType / defaultExtractor: ALL UNCHANGED")
