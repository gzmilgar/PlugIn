package com.cleancore.analyzer.handlers;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

/**
 * Handler for analyzing one or many ABAP objects/packages selected in the
 * Project Explorer (ADT).
 *
 * Strategy:
 *   1. Adapt the selected element to an {@link IResource}.
 *      - IFile  -> read its content directly.
 *      - IContainer (package/folder) -> recurse into its members.
 *   2. Fall back to programmatically opening the object in an editor
 *      (works for ADT virtual nodes that are not real workspace resources)
 *      and reading the editor's document, then closing it.
 *   3. Collected source map is fed into {@link ABAPAnalyzer#analyzeMultiple}.
 *   4. Results are pushed into {@link CleanCoreResultView}.
 */
public class PackageAnalyzeHandler extends AbstractHandler {

    private static final int MAX_OBJECTS = 500;

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ISelection sel = HandlerUtil.getCurrentSelection(event);
        Shell shell = HandlerUtil.getActiveShell(event);

        if (!(sel instanceof IStructuredSelection)
                || ((IStructuredSelection) sel).isEmpty()) {
            MessageDialog.openWarning(shell, "Clean Core Analyzer",
                "Lutfen Project Explorer'da bir ABAP paketi veya objesi secin,\n"
                + "ardindan sag tik > Analyze Package for Clean Core islemini deneyin.");
            return null;
        }

        final IStructuredSelection ss = (IStructuredSelection) sel;
        final Map<String, String> sources = new LinkedHashMap<>();
        final List<String> errors = new ArrayList<>();

        IProgressService progress = PlatformUI.getWorkbench().getProgressService();
        try {
            progress.busyCursorWhile(new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor monitor) {
                    monitor.beginTask("Kaynak kodlar toplaniyor...",
                                      Math.max(ss.size(), 1));
                    for (Object obj : ss.toList()) {
                        if (monitor.isCanceled()) break;
                        if (sources.size() >= MAX_OBJECTS) break;
                        try {
                            collect(obj, sources, monitor);
                        } catch (Exception ex) {
                            errors.add(safeName(obj) + ": " + ex.getMessage());
                        }
                        monitor.worked(1);
                    }
                    monitor.done();
                }
            });
        } catch (Exception ignored) {
            // user cancel or invocation issue - continue with what we have
        }

        if (sources.isEmpty()) {
            String detail = errors.isEmpty()
                ? "Secilen elemanlardan kaynak kod okunamadi."
                : "Hatalar:\n - " + String.join("\n - ", errors);
            MessageDialog.openWarning(shell, "Clean Core Analyzer",
                "Secilen elemanlardan ABAP kaynak kodu okunamadi.\n\n"
                + detail + "\n\n"
                + "Oneriler:\n"
                + "  1) Objeyi editor'de acin, ardindan Ctrl+Shift+K kullanin\n"
                + "  2) Paket dugumunu degil, dogrudan class/program dugumunu sag tiklayin\n"
                + "  3) Birden cok obje icin Ctrl/Shift ile coklu secim yapin");
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

    // ── Source collection ─────────────────────────────────────────────

    private void collect(Object obj, Map<String, String> sources,
                         IProgressMonitor monitor) {
        // 1) IResource adapter (file-backed projects)
        IResource resource = adaptToResource(obj);
        if (resource != null) {
            collectFromResource(resource, sources, monitor);
            if (!sources.isEmpty()) return;
        }

        // 2) Fallback: open the object in an editor, read document, close it
        String name = extractName(obj);
        if (name == null || name.isEmpty()) return;
        if (sources.containsKey(name)) return;

        String source = readSourceViaEditor(obj);
        if (source != null && !source.trim().isEmpty()) {
            sources.put(name, source);
        }
    }

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
                                     IProgressMonitor monitor) {
        if (resource instanceof IFile) {
            IFile file = (IFile) resource;
            if (!isAbapLike(file.getName())) return;
            String content = readFileContent(file);
            if (content != null && !content.trim().isEmpty()) {
                String key = stripExt(file.getName());
                sources.put(key, content);
            }
        } else if (resource instanceof IContainer) {
            try {
                IContainer container = (IContainer) resource;
                if (!container.isAccessible()) return;
                IResource[] children = container.members();
                for (IResource child : children) {
                    if (monitor.isCanceled()) return;
                    if (sources.size() >= MAX_OBJECTS) return;
                    collectFromResource(child, sources, monitor);
                }
            } catch (Exception ignored) {
                // container not accessible - skip
            }
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

    // ── Editor-based fallback (ADT virtual nodes) ─────────────────────

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
                    // editor could not be opened - skip
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

        // reflective getDocumentProvider()
        try {
            Method gdp = editor.getClass().getMethod("getDocumentProvider");
            Object dp = gdp.invoke(editor);
            if (dp instanceof IDocumentProvider) {
                IDocument d = ((IDocumentProvider) dp).getDocument(editor.getEditorInput());
                if (d != null) return d.get();
            }
        } catch (Exception ignored) {}

        // reflective getSourceViewer()
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

    // ── Name extraction (reflective, covers ADT internal types) ───────

    private String extractName(Object obj) {
        if (obj == null) return "";
        if (obj instanceof IResource) return stripExt(((IResource) obj).getName());
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
