package com.cleancore.analyzer.handlers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
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
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.model.IWorkbenchAdapter;
import org.eclipse.ui.progress.IDeferredWorkbenchAdapter;

/**
 * Diagnostic command. Right-click any node in Project Explorer and choose
 * "Inspect Selection (Clean Core Diagnostic)". A dialog opens with a copyable
 * report describing:
 *
 *   - The Java class of the selected node and its full class hierarchy
 *   - Which Eclipse / ADT interfaces it (or its adapter) responds to
 *   - The return values of common no-arg getters (getName, getType, ...)
 *   - Whether the node yields children via IWorkbenchAdapter /
 *     IDeferredWorkbenchAdapter / CommonNavigator
 *
 * This makes it possible to fix expansion / source-fetch issues caused by
 * special wrapper nodes (e.g. "Favorite Packages") without guessing.
 */
public class InspectSelectionHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ISelection sel = HandlerUtil.getCurrentSelection(event);
        Shell shell = HandlerUtil.getActiveShell(event);
        IWorkbenchPart activePart = HandlerUtil.getActivePart(event);

        if (!(sel instanceof IStructuredSelection)
                || ((IStructuredSelection) sel).isEmpty()) {
            openReport(shell,
                "Inspect Selection (Clean Core Diagnostic)",
                "No structured selection found.\n"
              + "Right-click a node in Project Explorer first, then run "
              + "Inspect Selection again.");
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Clean Core Analyzer - Selection Diagnostic\n");
        sb.append("============================================\n\n");
        sb.append("Active part : ")
          .append(activePart == null ? "<null>" : activePart.getClass().getName())
          .append("\n\n");

        int idx = 0;
        for (Object node : ((IStructuredSelection) sel).toList()) {
            idx++;
            sb.append("--- Node #").append(idx).append(" ---\n");
            inspectNode(node, activePart, sb);
            sb.append("\n");
        }

        openReport(shell, "Inspect Selection (Clean Core Diagnostic)", sb.toString());
        return null;
    }

    // ── Public helper so PackageAnalyzeHandler can reuse it for fallback ──

    /**
     * Builds the same diagnostic text used by the dialog, for one or more
     * nodes. Used as the automatic fallback when analysis returned 0 sources.
     */
    public static String buildReport(IStructuredSelection ss,
                                     IWorkbenchPart activePart) {
        StringBuilder sb = new StringBuilder();
        sb.append("Clean Core Analyzer - Selection Diagnostic\n");
        sb.append("============================================\n\n");
        sb.append("Active part : ")
          .append(activePart == null ? "<null>" : activePart.getClass().getName())
          .append("\n\n");
        int idx = 0;
        for (Object node : ss.toList()) {
            idx++;
            sb.append("--- Node #").append(idx).append(" ---\n");
            inspectNode(node, activePart, sb);
            sb.append("\n");
        }
        return sb.toString();
    }

    // ── Core inspector ───────────────────────────────────────────────

    private static void inspectNode(Object node, IWorkbenchPart activePart,
                                    StringBuilder sb) {
        if (node == null) {
            sb.append("(null)\n");
            return;
        }

        sb.append("Class       : ").append(node.getClass().getName()).append("\n");
        sb.append("toString()  : ").append(safeToString(node)).append("\n");
        sb.append("Hierarchy   : ").append(classHierarchy(node.getClass())).append("\n");
        sb.append("Interfaces  : ").append(interfaceList(node.getClass())).append("\n");

        // Adapter probes
        sb.append("Adapters    :\n");
        probeAdapter(node, "IAdaptable",
            node instanceof IAdaptable ? node.getClass().getName() : null, sb);
        probeAdapter(node, "IResource",         adaptTo(node, IResource.class), sb);
        probeAdapter(node, "IFile",             adaptTo(node, IFile.class), sb);
        probeAdapter(node, "IProject",          adaptTo(node, IProject.class), sb);
        probeAdapter(node, "IEditorInput",      adaptTo(node, IEditorInput.class), sb);
        probeAdapter(node, "IWorkbenchAdapter",
            adaptTo(node, IWorkbenchAdapter.class), sb);
        probeAdapter(node, "IDeferredWorkbenchAdapter",
            adaptTo(node, IDeferredWorkbenchAdapter.class), sb);

        // ADT IAdtObjectReference (reflection)
        Object adtRef = AdtTraverser.getAdtObjectReference(node);
        probeAdapter(node, "IAdtObjectReference",
            adtRef != null ? adtRef.getClass().getName() : null, sb);
        if (adtRef != null) {
            sb.append("              .getType()  = ")
              .append(AdtTraverser.getObjectType(adtRef)).append("\n");
            sb.append("              .getName()  = ")
              .append(AdtTraverser.getObjectName(adtRef)).append("\n");
            sb.append("              .getUri()   = ")
              .append(AdtTraverser.getObjectUri(adtRef)).append("\n");
            sb.append("              .getPackageName() = ")
              .append(AdtTraverser.getPackageName(adtRef)).append("\n");
            sb.append("              destinationId    = ")
              .append(AdtTraverser.getDestinationId(node)).append("\n");
        }

        // Common no-arg getters
        sb.append("Getters     :\n");
        for (String mn : new String[] {
                "getName", "getElementName", "getDisplayName",
                "getObjectName", "getAdtObjectName",
                "getLabel", "getText", "getPackageName",
                "getProject", "getDestinationId", "getType", "getUri" }) {
            Object v = tryInvokeNoArg(node, mn);
            if (v != null) {
                sb.append("  .").append(mn).append("() = ")
                  .append(truncate(String.valueOf(v), 200)).append("\n");
            }
        }

        // Expansion probes
        sb.append("Expansion   :\n");
        List<String> reasons = new ArrayList<>();
        Object[] kids = AdtTraverser.expandChildrenViaWorkbench(
            node, activePart, new NullProgressMonitor(), reasons);
        if (kids != null && kids.length > 0) {
            sb.append("  Got ").append(kids.length)
              .append(" children via workbench adapter chain:\n");
            int show = Math.min(kids.length, 10);
            for (int i = 0; i < show; i++) {
                sb.append("    [").append(i).append("] ")
                  .append(kids[i] == null ? "<null>" : kids[i].getClass().getName())
                  .append(" -- ").append(safeToString(kids[i])).append("\n");
            }
            if (kids.length > show) {
                sb.append("    ... (+").append(kids.length - show)
                  .append(" more)\n");
            }
        } else {
            sb.append("  No children expanded.\n");
            for (String r : reasons) {
                sb.append("    - ").append(r).append("\n");
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static <T> String adaptTo(Object node, Class<T> cls) {
        if (cls.isInstance(node)) return node.getClass().getName();
        if (node instanceof IAdaptable) {
            Object a = ((IAdaptable) node).getAdapter(cls);
            if (a != null) return a.getClass().getName();
        }
        return null;
    }

    private static void probeAdapter(Object node, String label, String result,
                                     StringBuilder sb) {
        sb.append("  ").append(pad(label, 30)).append(" = ");
        if (result == null) sb.append("(not adaptable)\n");
        else sb.append(result).append("\n");
    }

    private static String classHierarchy(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        while (c != null && !c.equals(Object.class)) {
            if (!first) sb.append(" <- ");
            sb.append(c.getName());
            c = c.getSuperclass();
            first = false;
        }
        return sb.toString();
    }

    private static String interfaceList(Class<?> c) {
        Set<String> seen = new LinkedHashSet<>();
        Class<?> cur = c;
        while (cur != null && !cur.equals(Object.class)) {
            for (Class<?> i : cur.getInterfaces()) {
                seen.add(i.getName());
                addSuperInterfaces(i, seen);
            }
            cur = cur.getSuperclass();
        }
        return seen.isEmpty() ? "(none)" : String.join(", ", seen);
    }

    private static void addSuperInterfaces(Class<?> i, Set<String> seen) {
        for (Class<?> s : i.getInterfaces()) {
            if (seen.add(s.getName())) addSuperInterfaces(s, seen);
        }
    }

    private static Object tryInvokeNoArg(Object target, String name) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String safeToString(Object o) {
        if (o == null) return "<null>";
        try { return truncate(o.toString(), 200); }
        catch (Throwable t) { return "<toString threw " + t.getClass().getSimpleName() + ">"; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "... (+" + (s.length() - max) + " chars)";
    }

    private static String pad(String s, int n) {
        if (s == null) s = "";
        if (s.length() >= n) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    // ── Dialog ───────────────────────────────────────────────────────

    public static void openReport(Shell parent, String title, String body) {
        ReportDialog d = new ReportDialog(parent, title, body);
        d.open();
    }

    private static final class ReportDialog extends Dialog {
        private final String title;
        private final String body;
        private Text text;

        ReportDialog(Shell parent, String title, String body) {
            super(parent);
            this.title = title;
            this.body = body == null ? "" : body;
            setShellStyle(getShellStyle() | SWT.RESIZE);
        }

        @Override
        protected void configureShell(Shell newShell) {
            super.configureShell(newShell);
            newShell.setText(title);
        }

        @Override
        protected Point getInitialSize() {
            return new Point(900, 600);
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            area.setLayout(new GridLayout(1, false));

            Label hint = new Label(area, SWT.NONE);
            hint.setText(
                "Diagnostic report for the current selection. "
              + "Use the Copy button to share it.");

            text = new Text(area, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL
                                | SWT.H_SCROLL | SWT.READ_ONLY);
            GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
            gd.minimumHeight = 400;
            text.setLayoutData(gd);
            text.setText(body);
            return area;
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent) {
            Button copy = createButton(parent, IDialogConstants.CLIENT_ID + 1,
                "Copy", false);
            copy.addListener(SWT.Selection, new Listener() {
                @Override
                public void handleEvent(Event ev) {
                    Clipboard cb = new Clipboard(Display.getCurrent());
                    try {
                        cb.setContents(new Object[] { body },
                            new Transfer[] { TextTransfer.getInstance() });
                    } finally {
                        cb.dispose();
                    }
                }
            });
            createButton(parent, IDialogConstants.OK_ID,
                IDialogConstants.OK_LABEL, true);
        }
    }
}
