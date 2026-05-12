package com.cleancore.analyzer.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.part.ViewPart;

import com.cleancore.analyzer.core.Finding;
import com.cleancore.analyzer.core.Severity;

public class CleanCoreResultView extends ViewPart {

    public static final String ID = "com.cleancore.analyzer.resultView";

    private TableViewer viewer;
    private Label summaryLabel;
    private Label effortLabel;
    private List<Finding> findings = new ArrayList<>();

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        summaryLabel = new Label(parent, SWT.NONE);
        summaryLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        summaryLabel.setText("No analysis results yet. Use Clean Core > Analyze Current File (Ctrl+Shift+K).");

        effortLabel = new Label(parent, SWT.NONE);
        effortLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        FontData[] fd = effortLabel.getFont().getFontData();
        for (FontData d : fd) d.setStyle(SWT.BOLD);
        effortLabel.setFont(new Font(parent.getDisplay(), fd));
        effortLabel.setText("");

        viewer = new TableViewer(parent,
            SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.BORDER);

        createColumns();

        Table table = viewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.setInput(findings);
    }

    private void createColumns() {
        addColumn("Severity", 80, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((Finding) element).getSeverity().getLabel().toUpperCase();
            }
            @Override
            public Color getForeground(Object element) {
                Display display = Display.getCurrent();
                Severity sev = ((Finding) element).getSeverity();
                switch (sev) {
                    case CRITICAL: return display.getSystemColor(SWT.COLOR_RED);
                    case WARNING: return new Color(display, 200, 120, 0);
                    default: return display.getSystemColor(SWT.COLOR_BLUE);
                }
            }
        });

        addColumn("Rule", 60, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((Finding) element).getRuleId();
            }
        });

        addColumn("Name", 180, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((Finding) element).getRuleName();
            }
        });

        addColumn("Line", 50, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return String.valueOf(((Finding) element).getLineStart());
            }
        });

        addColumn("Category", 120, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((Finding) element).getCategory().getLabel();
            }
        });

        addColumn("Matched Code", 250, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((Finding) element).getMatchedText();
            }
        });

        addColumn("Suggestion", 300, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((Finding) element).getSuggestion();
            }
        });

        addColumn("Clean Core API", 180, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((Finding) element).getCleanCoreApi();
            }
        });

        addColumn("Effort (Gun)", 80, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                double d = ((Finding) element).getEffortDays();
                if (d <= 0) return "-";
                if (d == Math.floor(d)) return String.valueOf((int) d);
                return String.format("%.2f", d);
            }
            @Override
            public Color getForeground(Object element) {
                double d = ((Finding) element).getEffortDays();
                Display display = Display.getCurrent();
                if (d >= 3.0) return display.getSystemColor(SWT.COLOR_RED);
                if (d >= 1.0) return new Color(display, 200, 120, 0);
                return display.getSystemColor(SWT.COLOR_DARK_GREEN);
            }
        });
    }

    private void addColumn(String title, int width, ColumnLabelProvider labelProvider) {
        TableViewerColumn col = new TableViewerColumn(viewer, SWT.NONE);
        col.getColumn().setText(title);
        col.getColumn().setWidth(width);
        col.getColumn().setResizable(true);
        col.getColumn().setMoveable(true);
        col.setLabelProvider(labelProvider);
    }

    public void setFindings(List<Finding> newFindings, String fileName) {
        this.findings = newFindings != null ? newFindings : new ArrayList<>();
        viewer.setInput(this.findings);
        viewer.refresh();

        long critical = findings.stream().filter(f -> f.getSeverity() == Severity.CRITICAL).count();
        long warning = findings.stream().filter(f -> f.getSeverity() == Severity.WARNING).count();
        long info = findings.stream().filter(f -> f.getSeverity() == Severity.INFO).count();

        double totalEffort = findings.stream().mapToDouble(Finding::getEffortDays).sum();

        String summary = String.format(
            "%s  |  Critical: %d   Warning: %d   Info: %d   Total: %d",
            fileName, critical, warning, info, findings.size());
        summaryLabel.setText(summary);

        String effortText = String.format(
            "Toplam Efor Tahmini: %.1f gun (%.1f adam/hafta)  |  "
            + "DB->CDS: %.1f gun   API->Released: %.1f gun   UI->Fiori: %.1f gun   Obsolete: %.1f gun",
            totalEffort, totalEffort / 5.0,
            findings.stream().filter(f -> f.getRuleId().compareTo("CC010") < 0).mapToDouble(Finding::getEffortDays).sum(),
            findings.stream().filter(f -> f.getRuleId().compareTo("CC010") >= 0 && f.getRuleId().compareTo("CC020") < 0).mapToDouble(Finding::getEffortDays).sum(),
            findings.stream().filter(f -> f.getRuleId().compareTo("CC020") >= 0 && f.getRuleId().compareTo("CC030") < 0).mapToDouble(Finding::getEffortDays).sum(),
            findings.stream().filter(f -> f.getRuleId().compareTo("CC030") >= 0).mapToDouble(Finding::getEffortDays).sum()
        );
        effortLabel.setText(effortText);

        summaryLabel.getParent().layout();
    }

    @Override
    public void setFocus() {
        viewer.getControl().setFocus();
    }
}
