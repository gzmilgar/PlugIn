package com.cleancore.analyzer.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import com.cleancore.analyzer.ui.DiagnosticDialog;

/**
 * Right-click → "Inspect Selection (Clean Core Diagnostic)" (or Ctrl+Shift+I).
 *
 * Builds a textual diagnostic of the current selection via
 * {@link SelectionDiagnostic} and shows it in a {@link DiagnosticDialog}.
 *
 * Use this when "Analyze Package" silently fails — the report reveals the
 * actual Java class of the selected node, all adapter probes (IResource,
 * IWorkbenchAdapter, IDeferredWorkbenchAdapter, IAdtObjectReference …) and
 * a snapshot of every no-arg getter.
 */
public class InspectSelectionHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ISelection sel        = HandlerUtil.getCurrentSelection(event);
        Shell shell           = HandlerUtil.getActiveShell(event);
        IWorkbenchPart part   = HandlerUtil.getActivePart(event);

        if (!(sel instanceof IStructuredSelection)
                || ((IStructuredSelection) sel).isEmpty()) {
            DiagnosticDialog.show(shell,
                "Inspect Selection (Clean Core Diagnostic)",
                "No structured selection found.",
                "Right-click a node in Project Explorer (or Repository Tree) "
                + "first, then run Inspect Selection again.");
            return null;
        }

        IStructuredSelection ss = (IStructuredSelection) sel;
        String report = SelectionDiagnostic.collectAll(ss.toList(), part);

        String header =
            "Diagnostic report for " + ss.size() + " selected node(s).\n"
          + "Share this report (Copy to Clipboard) to debug analyse failures.";

        DiagnosticDialog.show(shell,
            "Inspect Selection (Clean Core Diagnostic)", header, report);
        return null;
    }
}
