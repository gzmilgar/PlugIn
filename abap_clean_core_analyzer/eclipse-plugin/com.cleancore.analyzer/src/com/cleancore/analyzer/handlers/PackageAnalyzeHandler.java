package com.cleancore.analyzer.handlers;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.progress.IProgressService;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import com.cleancore.analyzer.core.ABAPAnalyzer;
import com.cleancore.analyzer.core.Finding;
import com.cleancore.analyzer.ui.CleanCoreResultView;
import com.cleancore.analyzer.ui.DiagnosticDialog;

/**
 * Handler for analysing one or many ABAP objects/packages selected in the
 * Project Explorer (ADT).
 *
 * Two main modes:
 *
 *   1. Selected node is an ADT package (DEVC). We then use
 *      {@link AdtTraverser} (reflection) to fetch package children via the
 *      /sap/bc/adt/repository/nodestructure REST endpoint, recurse into
 *      subpackages, and read every Class/Program/FunctionGroup/Include source
 *      via the ADT REST {object}/source/main endpoint.
 *
 *   2. Selected node is a single ABAP object. We try:
 *        a) ADT REST source endpoint (fastest)
 *        b) Programmatically open it in an editor, read the document, close it
 *        c) IResource adaptation (file-backed projects)
 */
public class PackageAnalyzeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ISelection sel = HandlerUtil.getCurrentSelection(event);
        final Shell shell = HandlerUtil.getActiveShell(event);
        final IWorkbenchPart activePart = HandlerUtil.getActivePart(event);

        if (!(sel instanceof IStructuredSelection)
                || ((IStructuredSelection) sel).isEmpty()) {
            MessageDialog.openWarning(shell, "Clean Core Analyzer",
                "Lutfen Project Explorer'da bir ABAP paketi veya objesi secin.");
            return null;
        }

        final IStructuredSelection ss = (IStructuredSelection) sel;

        // 1) Ask the user for the maximum number of objects to analyse.
        final int limit = LimitInputDialog.prompt(shell);
        if (limit < 0) return null; // user cancelled

        final Map<String, String> sources = new LinkedHashMap<>();
        final List<String> errors = new ArrayList<>();
        final int[] processedCounter = new int[] { 0 };

        IProgressService progress = PlatformUI.getWorkbench().getProgressService();
        try {
            progress.busyCursorWhile(new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor monitor) {
                    monitor.beginTask("ABAP objeleri toplaniyor...", limit);
                    for (Object obj : ss.toList()) {
                        if (monitor.isCanceled()) break;
                        if (sources.size() >= limit) break;
                        try {
                            collectAny(obj, activePart, sources, errors,
                                       monitor, limit, processedCounter);
                        } catch (Exception ex) {
                            errors.add(safeName(obj) + ": " + ex.getMessage());
                        }
                    }
                    monitor.done();
                }
            });
        } catch (Exception ignored) {
            // user cancel or runner failure - continue with what we have
        }

        if (sources.isEmpty()) {
            String detail = errors.isEmpty()
                ? "Secilen elemanlardan kaynak kod okunamadi."
                : "Hatalar:\n - " + String.join("\n - ",
                    errors.subList(0, Math.min(errors.size(), 10)));

            String header =
                "Analiz hic obje uretemedi - tani raporu asagidadir.\n\n"
                + detail + "\n\n"
                + "Oneriler:\n"
                + "  - Aktif ABAP projesinde oldugunuzdan emin olun (ADT baglantisi)\n"
                + "  - Objeyi editor'de acin ve Ctrl+Shift+K kullanin\n"
                + "  - Veya dogrudan class/program dugumunu sag tiklayin\n"
                + "  - Favorite Packages yerine ABAP Repository altindan ayni\n"
                + "    pakete sag tik deneyin\n"
                + "  - Veya 'Inspect Selection (Clean Core Diagnostic)' komutunu\n"
                + "    (Ctrl+Shift+I) kullanin";

            String report = "";
            try {
                report = SelectionDiagnostic.collectAll(ss.toList(), activePart);
            } catch (Throwable t) {
                report = "Diagnostic collect failed: " + t.getMessage();
            }

            DiagnosticDialog.show(shell,
                "Clean Core Analyzer - Analiz Basarisiz",
                header, report);
            return null;
        }

        ABAPAnalyzer analyzer = new ABAPAnalyzer();
        List<Finding> findings = analyzer.analyzeMultiple(sources);

        try {
            IWorkbenchPage page = PlatformUI.getWorkbench()
                .getActiveWorkbenchWindow().getActivePage();
            CleanCoreResultView view = (CleanCoreResultView) page.showView(
                CleanCoreResultView.ID);
            String header = sources.size() == 1
                ? "1 obje: " + sources.keySet().iterator().next()
                : sources.size() + " obje analiz edildi";
            view.setFindings(findings, header);
        } catch (PartInitException e) {
            MessageDialog.openInformation(shell, "Clean Core Analyzer",
                "Analiz tamamlandi: " + sources.size() + " obje, "
                + findings.size() + " bulgu.\n\n"
                + "Sonuc view'i acilamadi: " + e.getMessage());
        }
        return null;
    }

    // ── Top-level dispatch ───────────────────────────────────────────

    private void collectAny(Object node, IWorkbenchPart activePart,
                            Map<String, String> sources,
                            List<String> errors, IProgressMonitor monitor,
                            int limit, int[] processed) {
        if (node == null) return;

        // Strategy A: ADT-aware (reflection)
        Object adtRef = AdtTraverser.getAdtObjectReference(node);
        if (adtRef != null) {
            String type = AdtTraverser.getObjectType(adtRef);
            String name = AdtTraverser.getObjectName(adtRef);
            String destId = AdtTraverser.getDestinationId(node);

            if (AdtTraverser.isPackage(type)) {
                Set<String> visited = new HashSet<>();
                traversePackage(node, name, destId, activePart,
                                sources, errors, monitor,
                                visited, limit, processed);
                return;
            }
            if (AdtTraverser.isAnalyzable(type)) {
                if (name != null && !sources.containsKey(name)) {
                    String src = fetchSourceChain(adtRef, node, destId, name);
                    if (src != null && !src.trim().isEmpty()) {
                        sources.put(name, src);
                    } else {
                        errors.add(name + ": kaynak kod okunamadi");
                    }
                }
                updateMonitor(monitor, processed, name);
                return;
            }
            // Interface/Table/DDLS etc. – filtered out by user choice
            return;
        }

        // Strategy A-fallback: workbench adapter (even without IAdtObjectReference)
        Object[] kids = AdtTraverser.expandChildrenViaWorkbench(node, activePart, monitor);
        if (kids != null && kids.length > 0) {
            Set<String> visited = new HashSet<>();
            String parentName = extractName(node);
            traversePackageByWorkbench(node, parentName, activePart,
                                       sources, errors, monitor,
                                       visited, limit, processed);
            return;
        }

        // Strategy A-fallback 2: direct repository objects without IAdtObjectReference
        //   (covers VirtualFolderNode-style wrappers whose adtRef path
        //    didn't trigger because the wrapper itself reports no DEVC type)
        List<Object> directRefs = AdtTraverser.getDirectRepositoryObjects(node);
        if (directRefs != null && !directRefs.isEmpty()) {
            Set<String> visited = new HashSet<>();
            String destId = AdtTraverser.getDestinationId(node);
            for (Object ref : directRefs) {
                if (monitor.isCanceled() || sources.size() >= limit) return;
                processDirectReference(ref, destId, activePart,
                                       sources, errors, monitor, visited,
                                       limit, processed);
            }
            return;
        }

        // Strategy B: IResource adapter (file-backed projects)
        IResource resource = adaptToResource(node);
        if (resource != null) {
            collectFromResource(resource, sources, monitor, limit, processed);
            if (!sources.isEmpty()) return;
        }

        // Strategy C: editor-based open/read/close (last resort)
        String name = extractName(node);
        if (name != null && !sources.containsKey(name)) {
            String src = readSourceViaEditor(node);
            if (src != null && !src.trim().isEmpty()) {
                sources.put(name, src);
            }
        }
        updateMonitor(monitor, processed, name);

        // Strategy D: nothing matched – try to unwrap wrapper nodes
        //             (Favorite Packages, search hits, recently-used etc.)
        if (sources.isEmpty() || !sources.containsKey(name)) {
            Object inner = AdtTraverser.unwrapWrapperNode(node);
            if (inner != null && inner != node) {
                collectAny(inner, activePart, sources, errors,
                           monitor, limit, processed);
            }
        }
    }

    // ── Package recursive traversal ──────────────────────────────────

    /**
     * Hybrid traversal:
     *   - If the live workbench gives us children (IWorkbenchAdapter /
     *     IDeferredWorkbenchAdapter), we recurse on those node objects directly.
     *   - Otherwise we fall back to nodestructure REST.
     */
    private void traversePackage(Object packageNode, String packageName,
                                 String destId, IWorkbenchPart activePart,
                                 Map<String, String> sources,
                                 List<String> errors,
                                 IProgressMonitor monitor,
                                 Set<String> visited,
                                 int limit, int[] processed) {
        if (packageName == null || visited.contains(packageName)) return;
        visited.add(packageName);
        if (monitor.isCanceled() || sources.size() >= limit) return;

        monitor.subTask("Paket okunuyor: " + packageName);

        // 0) Direct repository objects (VirtualFolderNode for Favorite Packages,
        //    search hits etc. that implement IAbapRepositoryObjectListProvider)
        if (packageNode != null) {
            List<Object> directRefs =
                AdtTraverser.getDirectRepositoryObjects(packageNode);
            if (directRefs != null && !directRefs.isEmpty()) {
                for (Object ref : directRefs) {
                    if (monitor.isCanceled() || sources.size() >= limit) return;
                    processDirectReference(ref, destId, activePart,
                                           sources, errors,
                                           monitor, visited,
                                           limit, processed);
                }
                return;
            }
        }

        // 1) Try workbench adapter first (no REST round-trip)
        List<String> diag = new ArrayList<>();
        Object[] kids = AdtTraverser.expandChildrenViaWorkbench(
            packageNode, activePart, monitor, diag);
        if (kids != null && kids.length > 0) {
            for (Object child : kids) {
                if (monitor.isCanceled() || sources.size() >= limit) return;
                handleWorkbenchChild(child, activePart, sources, errors,
                                     monitor, visited, limit, processed, destId);
            }
            return;
        }

        // 2) Fall back to nodestructure REST
        List<AdtRestParser.NodeInfo> children =
            AdtTraverser.listPackageChildren(destId, packageName, monitor);

        if (children == null || children.isEmpty()) {
            String reasonText = diag.isEmpty() ? "" : " [" + String.join("; ", diag) + "]";
            errors.add(packageName
                + ": cocuk obje listelenemedi (REST + workbench adapter bos)"
                + reasonText);
            return;
        }

        for (AdtRestParser.NodeInfo child : children) {
            if (monitor.isCanceled() || sources.size() >= limit) return;
            String type = child.type;
            String name = child.name;

            if (AdtTraverser.isPackage(type)) {
                traversePackage(null, name, destId, activePart,
                                sources, errors,
                                monitor, visited, limit, processed);
                continue;
            }
            if (!AdtTraverser.isAnalyzable(type)) continue;
            if (name == null || sources.containsKey(name)) continue;

            monitor.subTask((processed[0] + 1) + " / " + limit + ": "
                            + name + " (" + type + ")");
            String src = AdtTraverser.fetchSource(destId, child, monitor);
            if (src != null && !src.trim().isEmpty()) {
                sources.put(name, src);
            } else {
                errors.add(name + ": kaynak kod okunamadi");
            }
            updateMonitor(monitor, processed, name);
        }
    }

    /**
     * Handle a single {@code IAdtObjectReference} returned by a direct
     * repository-object provider (VirtualFolderNode etc.). Recurses into
     * subpackages via REST and fetches source for any analyzable type.
     */
    private void processDirectReference(Object ref, String destId,
                                        IWorkbenchPart activePart,
                                        Map<String, String> sources,
                                        List<String> errors,
                                        IProgressMonitor monitor,
                                        Set<String> visited,
                                        int limit, int[] processed) {
        if (ref == null) return;
        String type = AdtTraverser.getObjectType(ref);
        String name = AdtTraverser.getObjectName(ref);
        String uri  = AdtTraverser.getObjectUri(ref);
        if (name == null || name.isEmpty()) return;

        if (AdtTraverser.isPackage(type)) {
            // Recurse into subpackage (no live node, REST traversal)
            traversePackage(null, name, destId, activePart,
                            sources, errors, monitor,
                            visited, limit, processed);
            return;
        }
        if (!AdtTraverser.isAnalyzable(type)) return;
        if (sources.containsKey(name)) return;

        monitor.subTask((processed[0] + 1) + " / " + limit + ": "
                        + name + " (" + type + ")");
        AdtRestParser.NodeInfo info =
            new AdtRestParser.NodeInfo(type, name, uri);
        String src = AdtTraverser.fetchSource(destId, info, monitor);
        if (src != null && !src.trim().isEmpty()) {
            sources.put(name, src);
        } else {
            errors.add(name + ": kaynak kod okunamadi (" + type + ")");
        }
        updateMonitor(monitor, processed, name);
    }

    /**
     * Traversal that only uses workbench adapter (used when there is no
     * ADT reference at all on the selected node, but it still has children).
     */
    private void traversePackageByWorkbench(Object packageNode, String label,
                                            IWorkbenchPart activePart,
                                            Map<String, String> sources,
                                            List<String> errors,
                                            IProgressMonitor monitor,
                                            Set<String> visited,
                                            int limit, int[] processed) {
        if (label != null) {
            if (visited.contains(label)) return;
            visited.add(label);
        }
        if (monitor.isCanceled() || sources.size() >= limit) return;

        monitor.subTask("Genisletiliyor: " + (label == null ? "?" : label));
        Object[] kids = AdtTraverser.expandChildrenViaWorkbench(
            packageNode, activePart, monitor);
        if (kids == null || kids.length == 0) {
            errors.add((label == null ? "?" : label)
                + ": workbench adapter ile cocuk yok");
            return;
        }
        for (Object child : kids) {
            if (monitor.isCanceled() || sources.size() >= limit) return;
            handleWorkbenchChild(child, activePart, sources, errors,
                                 monitor, visited, limit, processed, null);
        }
    }

    /**
     * Process a single child returned by IWorkbenchAdapter / content provider.
     * Decides whether it is a package (recurse) or an ABAP object (fetch).
     */
    private void handleWorkbenchChild(Object child, IWorkbenchPart activePart,
                                      Map<String, String> sources,
                                      List<String> errors,
                                      IProgressMonitor monitor,
                                      Set<String> visited,
                                      int limit, int[] processed,
                                      String destId) {
        if (child == null) return;

        Object adtRef = AdtTraverser.getAdtObjectReference(child);
        String type = adtRef != null ? AdtTraverser.getObjectType(adtRef) : null;
        String name = adtRef != null ? AdtTraverser.getObjectName(adtRef) : null;
        if (name == null) name = extractName(child);
        if (destId == null && adtRef != null) {
            destId = AdtTraverser.getDestinationId(child);
        }

        if (type != null && AdtTraverser.isPackage(type)) {
            traversePackage(child, name, destId, activePart,
                            sources, errors,
                            monitor, visited, limit, processed);
            return;
        }

        // No reference / no recognised type → try to expand further;
        // it may be a "Source Library" / "Classes" group node in the tree.
        if (type == null) {
            // Recurse if it has children (category nodes), but cap recursion
            // by 'visited' set on the produced label.
            String label = "GRP:" + name;
            if (visited.contains(label)) return;
            visited.add(label);
            Object[] more = AdtTraverser.expandChildrenViaWorkbench(
                child, activePart, monitor);
            if (more != null && more.length > 0) {
                for (Object m : more) {
                    if (monitor.isCanceled() || sources.size() >= limit) return;
                    handleWorkbenchChild(m, activePart, sources, errors,
                                         monitor, visited, limit, processed, destId);
                }
            }
            return;
        }

        if (!AdtTraverser.isAnalyzable(type)) return;
        if (name == null || sources.containsKey(name)) return;

        monitor.subTask((processed[0] + 1) + " / " + limit + ": "
                        + name + " (" + type + ")");

        String src = fetchSourceChain(adtRef, child, destId, name);
        if (src != null && !src.trim().isEmpty()) {
            sources.put(name, src);
        } else {
            errors.add(name + ": kaynak kod okunamadi (" + type + ")");
        }
        updateMonitor(monitor, processed, name);
    }

    private void updateMonitor(IProgressMonitor monitor, int[] processed,
                               String name) {
        processed[0]++;
        try { monitor.worked(1); } catch (Exception ignored) {}
    }

    // ── Source fetch chain for single object ─────────────────────────

    private String fetchSourceChain(Object adtRef, Object node,
                                    String destId, String objectName) {
        // 1. ADT REST
        AdtRestParser.NodeInfo info = new AdtRestParser.NodeInfo(
            AdtTraverser.getObjectType(adtRef),
            objectName,
            AdtTraverser.getObjectUri(adtRef));
        String src = AdtTraverser.fetchSource(destId, info, null);
        if (src != null && !src.trim().isEmpty()) return src;

        // 2. Editor-based fallback
        src = readSourceViaEditor(node);
        if (src != null && !src.trim().isEmpty()) return src;

        // 3. IResource
        IResource res = adaptToResource(node);
        if (res instanceof IFile) {
            String content = readFileContent((IFile) res);
            if (content != null && !content.trim().isEmpty()) return content;
        }
        return null;
    }

    // ── IResource path ───────────────────────────────────────────────

    private IResource adaptToResource(Object obj) {
        if (obj instanceof IResource) return (IResource) obj;
        if (obj instanceof IAdaptable) {
            IResource r = ((IAdaptable) obj).getAdapter(IResource.class);
            if (r != null) return r;
            IFile f = ((IAdaptable) obj).getAdapter(IFile.class);
            if (f != null) return f;
        }
        return null;
    }

    private void collectFromResource(IResource resource,
                                     Map<String, String> sources,
                                     IProgressMonitor monitor,
                                     int limit, int[] processed) {
        if (sources.size() >= limit) return;
        if (resource instanceof IFile) {
            IFile file = (IFile) resource;
            if (!isAbapLike(file.getName())) return;
            String content = readFileContent(file);
            if (content != null && !content.trim().isEmpty()) {
                String key = stripExt(file.getName());
                if (!sources.containsKey(key)) {
                    sources.put(key, content);
                    updateMonitor(monitor, processed, key);
                }
            }
        } else if (resource instanceof IContainer) {
            try {
                IContainer container = (IContainer) resource;
                if (!container.isAccessible()) return;
                for (IResource child : container.members()) {
                    if (monitor.isCanceled()) return;
                    if (sources.size() >= limit) return;
                    collectFromResource(child, sources, monitor, limit, processed);
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean isAbapLike(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".abap")
            || lower.endsWith(".asprog")
            || lower.endsWith(".asclass")
            || lower.endsWith(".asinterface")
            || lower.endsWith(".asfugr")
            || lower.endsWith(".txt")
            || !lower.contains(".");
    }

    private String stripExt(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name;
        return name.substring(0, dot);
    }

    private String readFileContent(IFile file) {
        try (InputStream in = file.getContents()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            String charset = file.getCharset();
            return out.toString(charset != null ? charset : "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    // ── Editor-based fallback ────────────────────────────────────────

    private String readSourceViaEditor(final Object selObj) {
        final String[] result = new String[1];
        Display display = PlatformUI.getWorkbench().getDisplay();
        Runnable r = new Runnable() {
            @Override
            public void run() {
                IEditorPart opened = null;
                IWorkbenchPage page = null;
                try {
                    page = PlatformUI.getWorkbench()
                        .getActiveWorkbenchWindow().getActivePage();
                    if (page == null) return;

                    IEditorInput input = null;
                    if (selObj instanceof IAdaptable) {
                        input = ((IAdaptable) selObj).getAdapter(IEditorInput.class);
                    }
                    if (input == null) return;

                    String editorId = resolveEditorId(selObj);
                    opened = IDE.openEditor(page, input, editorId, false);
                    if (opened == null) return;
                    result[0] = getEditorText(opened);
                } catch (Exception ignored) {
                } finally {
                    if (opened != null && page != null) {
                        try { page.closeEditor(opened, false); } catch (Exception ignored2) {}
                    }
                }
            }
        };
        if (display.getThread() == Thread.currentThread()) {
            r.run();
        } else {
            display.syncExec(r);
        }
        return result[0];
    }

    private String resolveEditorId(Object selObj) {
        try {
            String name = extractName(selObj);
            if (name != null && !name.isEmpty()) {
                org.eclipse.ui.IEditorDescriptor desc = PlatformUI.getWorkbench()
                    .getEditorRegistry().getDefaultEditor(name);
                if (desc != null) return desc.getId();
            }
        } catch (Exception ignored) {}
        return "org.eclipse.ui.DefaultTextEditor";
    }

    private String getEditorText(IEditorPart editor) {
        if (editor instanceof ITextEditor) {
            String t = fromTextEditor((ITextEditor) editor);
            if (t != null) return t;
        }
        ITextEditor adapted = editor.getAdapter(ITextEditor.class);
        if (adapted != null) {
            String t = fromTextEditor(adapted);
            if (t != null) return t;
        }
        IDocument doc = editor.getAdapter(IDocument.class);
        if (doc != null) return doc.get();

        try {
            Method gdp = editor.getClass().getMethod("getDocumentProvider");
            Object dp = gdp.invoke(editor);
            if (dp instanceof IDocumentProvider) {
                IDocument d = ((IDocumentProvider) dp).getDocument(editor.getEditorInput());
                if (d != null) return d.get();
            }
        } catch (Exception ignored) {}

        try {
            Method gsv = findMethod(editor.getClass(), "getSourceViewer");
            if (gsv != null) {
                gsv.setAccessible(true);
                Object v = gsv.invoke(editor);
                if (v instanceof ITextViewer) {
                    IDocument d = ((ITextViewer) v).getDocument();
                    if (d != null) return d.get();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String fromTextEditor(ITextEditor te) {
        IDocumentProvider dp = te.getDocumentProvider();
        if (dp == null) return null;
        IDocument doc = dp.getDocument(te.getEditorInput());
        return doc != null ? doc.get() : null;
    }

    private Method findMethod(Class<?> c, String name) {
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
            }
            c = c.getSuperclass();
        }
        return null;
    }

    // ── Name extraction (reflective) ─────────────────────────────────

    private String extractName(Object obj) {
        if (obj == null) return "";
        if (obj instanceof IResource) return stripExt(((IResource) obj).getName());
        Object adtRef = AdtTraverser.getAdtObjectReference(obj);
        if (adtRef != null) {
            String n = AdtTraverser.getObjectName(adtRef);
            if (n != null && !n.isEmpty()) return n;
        }
        for (String mname : new String[]{
                "getName", "getElementName", "getDisplayName",
                "getObjectName", "getAdtObjectName" }) {
            try {
                Method m = obj.getClass().getMethod(mname);
                Object r = m.invoke(obj);
                if (r instanceof String && !((String) r).isEmpty()) {
                    return (String) r;
                }
            } catch (Exception ignored) {}
        }
        return obj.toString();
    }

    private String safeName(Object obj) {
        try { return extractName(obj); } catch (Exception e) { return "<unknown>"; }
    }
}
