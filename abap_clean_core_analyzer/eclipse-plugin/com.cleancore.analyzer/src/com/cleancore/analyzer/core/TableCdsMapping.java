package com.cleancore.analyzer.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class TableCdsMapping {

    private TableCdsMapping() {}

    private static final Map<String, String> MAP;

    static {
        Map<String, String> m = new HashMap<>();

        // ══════════════════════════════════════════════════════════
        // Material Master (MM-MD)
        // Ref: SAP Help - CDS Views for Product Master
        // ══════════════════════════════════════════════════════════
        m.put("MARA", "I_Product");
        m.put("MAKT", "I_ProductDescription");
        m.put("MARC", "I_ProductPlant");
        m.put("MARD", "I_ProductStorageLocation");
        m.put("MARM", "I_ProductUnitsOfMeasure");
        m.put("MBEW", "I_ProductValuation");
        m.put("MLAN", "I_ProductTaxClassification");
        m.put("MVKE", "I_ProductSalesDelivery");
        m.put("MEAN", "I_ProductEAN");

        // ══════════════════════════════════════════════════════════
        // Purchasing (MM-PUR)
        // Ref: SAP Help - CDS Views for Purchase Orders
        // ══════════════════════════════════════════════════════════
        m.put("EKKO", "I_PurchaseOrder");
        m.put("EKPO", "I_PurchaseOrderItem");
        m.put("EKBE", "I_PurchaseOrderHistory");
        m.put("EBAN", "I_PurchaseRequisitionItem");
        m.put("EKET", "I_PurchaseOrderScheduleLine");
        m.put("EINA", "I_PurchasingInfoRecord");
        m.put("EINE", "I_PurgInfoRecdOrgPlantData");
        m.put("LFM1", "I_SupplierPurchasingOrg");

        // ══════════════════════════════════════════════════════════
        // Sales & Distribution (SD)
        // Ref: SAP Help - CDS Views for Sales
        // ══════════════════════════════════════════════════════════
        m.put("VBAK", "I_SalesOrder");
        m.put("VBAP", "I_SalesOrderItem");
        m.put("VBEP", "I_SalesOrderScheduleLine");
        m.put("VBKD", "I_SalesOrder (VBKD fields embedded)");
        m.put("VBPA", "I_SalesOrderPartner");
        m.put("VBFA", "I_SalesDocumentFlow");
        m.put("VBUK", "I_SalesOrder (status fields embedded)");
        m.put("VBUP", "I_SalesOrderItem (status fields embedded)");
        m.put("LIKP", "I_DeliveryDocument");
        m.put("LIPS", "I_DeliveryDocumentItem");
        m.put("VBRK", "I_BillingDocument");
        m.put("VBRP", "I_BillingDocumentItem");
        m.put("KONV", "I_SalesOrderPricingElement (PRCD_ELEMENTS)");

        // ══════════════════════════════════════════════════════════
        // Business Partner / Customer / Vendor
        // Ref: SAP Help - BP/Customer/Supplier CDS Views
        // Note: S/4HANA'da KNA1/LFA1 -> Business Partner modeline tasinmistir
        // ══════════════════════════════════════════════════════════
        m.put("KNA1", "I_Customer");
        m.put("KNB1", "I_CustomerCompany");
        m.put("KNVV", "I_CustomerSalesArea");
        m.put("LFA1", "I_Supplier");
        m.put("LFB1", "I_SupplierCompany");
        m.put("BUT000", "I_BusinessPartner");
        m.put("ADRC", "I_Address");

        // ══════════════════════════════════════════════════════════
        // Finance (FI)
        // Ref: SAP Help - CDS Views for GL Accounting
        // Note: S/4HANA'da BSEG artik fiziksel tablo degil, ACDOCA uzerinden okunur
        // ══════════════════════════════════════════════════════════
        m.put("BKPF", "I_JournalEntry");
        m.put("BSEG", "I_OperationalAcctgDocItem");
        m.put("ACDOCA", "I_JournalEntryItem");
        m.put("BSID", "I_JournalEntryItem");
        m.put("BSAD", "I_JournalEntryItem");
        m.put("BSIK", "I_JournalEntryItem");
        m.put("BSAK", "I_JournalEntryItem");
        m.put("RBKP", "I_InvoiceDocument");
        m.put("SKA1", "I_GLAccountInChartOfAccounts");
        m.put("SKAT", "I_GLAccountText");

        // ══════════════════════════════════════════════════════════
        // Organizational Data / Config
        // Ref: SAP Help - Organizational CDS Views
        // ══════════════════════════════════════════════════════════
        m.put("T001", "I_CompanyCode");
        m.put("T001W", "I_Plant");
        m.put("T001L", "I_StorageLocation");
        m.put("TCURR", "I_ExchangeRateRawData");
        m.put("TCURC", "I_Currency");
        m.put("T005", "I_Country");
        m.put("T005T", "I_CountryText");
        m.put("T006", "I_UnitOfMeasure");
        m.put("T006A", "I_UnitOfMeasure");
        m.put("DD07T", "I_DomainFixedValueText");

        // ══════════════════════════════════════════════════════════
        // Controlling (CO)
        // Ref: SAP Help - Controlling CDS Views
        // ══════════════════════════════════════════════════════════
        m.put("CSKS", "I_CostCenter");
        m.put("CSKA", "I_CostElement");
        m.put("CEPC", "I_ProfitCenter");
        m.put("CEPCT", "I_ProfitCenterText");

        // ══════════════════════════════════════════════════════════
        // Production / Manufacturing (PP)
        // Ref: SAP Help - CDS Views for Manufacturing Order
        // ══════════════════════════════════════════════════════════
        m.put("CAUFV", "I_ManufacturingOrder");
        m.put("CAUFVD", "I_ManufacturingOrder");
        m.put("AFKO", "I_ManufacturingOrder");
        m.put("AFPO", "I_ManufacturingOrderItem");
        m.put("AFVC", "I_ManufacturingOrderOperation");
        m.put("AFVV", "I_ManufacturingOrderOperation");
        m.put("AFRU", "I_MfgOrderConfirmation");
        m.put("AUFK", "I_ProductionOrder");
        m.put("RESB", "I_ReservationDocumentItem");
        m.put("STKO", "I_BillOfMaterial");
        m.put("STPO", "I_BillOfMaterialItem");
        m.put("PLKO", "I_ProductionRouting");
        m.put("PLPO", "I_ProductionRoutingOperation");

        // ══════════════════════════════════════════════════════════
        // Inventory / Material Documents (MM-IM)
        // Ref: SAP Help - Inventory Management CDS Views
        // Note: S/4HANA'da MSEG/MKPF -> MATDOC tablosuna tasinmistir
        // ══════════════════════════════════════════════════════════
        m.put("MSEG", "I_MaterialDocumentItem");
        m.put("MKPF", "I_MaterialDocumentHeader");
        m.put("MCHB", "I_MaterialStockByBatch");
        m.put("LQUA", "I_WarehouseAvailableStock");

        // ══════════════════════════════════════════════════════════
        // Quality Management (QM)
        // Ref: SAP Help - QM CDS Views
        // ══════════════════════════════════════════════════════════
        m.put("QALS", "I_InspectionLot");
        m.put("QAVE", "I_InspLotUsageDecision");

        // ══════════════════════════════════════════════════════════
        // Plant Maintenance (PM)
        // Ref: SAP Help - PM CDS Views
        // ══════════════════════════════════════════════════════════
        m.put("EQUI", "I_Equipment");
        m.put("ILOA", "I_MaintenanceObject");
        m.put("TPLNR", "I_FunctionalLocation");

        MAP = Collections.unmodifiableMap(m);
    }

    public static String lookup(String tableName) {
        if (tableName == null) return null;
        return MAP.get(tableName.toUpperCase().trim());
    }

    public static Map<String, String> getAll() {
        return MAP;
    }
}
