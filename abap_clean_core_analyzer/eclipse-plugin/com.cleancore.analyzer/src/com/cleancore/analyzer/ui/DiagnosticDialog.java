package com.cleancore.analyzer.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Read-only scrollable dialog used to display a diagnostic report produced
 * by {@code SelectionDiagnostic}. The user can copy the entire report to the
 * clipboard with a single click — useful for sharing the failure report.
 *
 * <ul>
 *   <li>800 x 600 default size, resizable.</li>
 *   <li>Monospace font (JFace text font).</li>
 *   <li>{@code SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER}.</li>
 *   <li>"Copy to Clipboard" + "Close" buttons.</li>
 * </ul>
 */
public final class DiagnosticDialog extends Dialog {

    private final String title;
    private final String header;
    private final String body;
    private Text reportArea;

    public DiagnosticDialog(Shell parent, String title, String header, String body) {
        super(parent);
        this.title  = title  == null ? "Clean Core Diagnostic" : title;
        this.header = header == null ? ""                       : header;
        this.body   = body   == null ? ""                       : body;
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
    }

    public static void show(Shell parent, String title, String header, String body) {
        new DiagnosticDialog(parent, title, header, body).open();
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText(title);
    }

    @Override
    protected Point getInitialSize() {
        return new Point(800, 600);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new GridLayout(1, false));

        if (header != null && !header.isEmpty()) {
            Label hdr = new Label(area, SWT.WRAP);
            hdr.setText(header);
            GridData hgd = new GridData(SWT.FILL, SWT.BEGINNING, true, false);
            hgd.widthHint = 760;
            hdr.setLayoutData(hgd);
        }

        Label hint = new Label(area, SWT.NONE);
        hint.setText("Use \"Copy to Clipboard\" to share this report.");

        reportArea = new Text(area,
            SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.minimumHeight = 400;
        gd.minimumWidth  = 760;
        reportArea.setLayoutData(gd);

        Font mono = JFaceResources.getTextFont();
        if (mono != null) reportArea.setFont(mono);
        reportArea.setText(body);
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        Button copy = createButton(parent, IDialogConstants.CLIENT_ID + 1,
            "Copy to Clipboard", false);
        copy.addListener(SWT.Selection, new Listener() {
            @Override
            public void handleEvent(Event ev) {
                Clipboard cb = new Clipboard(Display.getCurrent());
                try {
                    String text = reportArea != null && !reportArea.isDisposed()
                        ? reportArea.getText()
                        : body;
                    cb.setContents(new Object[] { text },
                        new Transfer[] { TextTransfer.getInstance() });
                } finally {
                    cb.dispose();
                }
            }
        });
        createButton(parent, IDialogConstants.OK_ID,
            IDialogConstants.CLOSE_LABEL, true);
    }
}
