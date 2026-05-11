package com.cleancore.analyzer.handlers;

import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import com.cleancore.analyzer.core.ABAPAnalyzer;
import com.cleancore.analyzer.core.Finding;
import com.cleancore.analyzer.ui.CleanCoreResultView;

public class AnalyzeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IEditorPart editor = HandlerUtil.getActiveEditor(event);

        if (editor == null) {
            MessageDialog.openWarning(
                HandlerUtil.getActiveShell(event),
                "Clean Core Analyzer",
                "No active editor found.\n\n"
                + "Please open an ABAP source object (class, program, function module) "
                + "in the editor first, then run the analysis.");
            return null;
        }

        String source = getEditorText(editor);
        if (source == null || source.trim().isEmpty()) {
            MessageDialog.openWarning(
                HandlerUtil.getActiveShell(event),
                "Clean Core Analyzer",
                "The current editor does not contain readable ABAP source code.\n\n"
                + "This can happen when:\n"
                + "  - The active tab is a welcome page or info view\n"
                + "  - The ABAP object has not finished loading yet\n\n"
                + "Please open an ABAP source object (class, program, function module) "
                + "and try again with Ctrl+Shift+K.");
            return null;
        }

        ABAPAnalyzer analyzer = new ABAPAnalyzer();
        List<Finding> findings = analyzer.analyze(source);

        try {
            IWorkbenchPage page = PlatformUI.getWorkbench()
                .getActiveWorkbenchWindow().getActivePage();
            CleanCoreResultView view = (CleanCoreResultView) page.showView(
                CleanCoreResultView.ID);
            String title = editor.getTitle();
            view.setFindings(findings, title);
        } catch (Exception e) {
            showFallbackDialog(event, findings);
        }

        return null;
    }

    private String getEditorText(IEditorPart editor) {
        // 1) Direct ITextEditor
        if (editor instanceof ITextEditor) {
            String text = extractFromTextEditor((ITextEditor) editor);
            if (text != null) return text;
        }

        // 2) Adapter to ITextEditor (works for many ADT editors)
        ITextEditor adapted = editor.getAdapter(ITextEditor.class);
        if (adapted != null) {
            String text = extractFromTextEditor(adapted);
            if (text != null) return text;
        }

        // 3) Adapter to IDocument directly
        IDocument directDoc = editor.getAdapter(IDocument.class);
        if (directDoc != null) {
            return directDoc.get();
        }

        // 4) Reflection: try getDocumentProvider() on the editor itself
        //    (covers ADT AbapSourceEditor and similar non-standard editors)
        try {
            Method gdp = editor.getClass().getMethod("getDocumentProvider");
            Object dp = gdp.invoke(editor);
            if (dp instanceof IDocumentProvider) {
                IDocument doc = ((IDocumentProvider) dp).getDocument(editor.getEditorInput());
                if (doc != null) return doc.get();
            }
        } catch (Exception ignored) {}

        // 5) Reflection: try getSourceViewer().getDocument()
        try {
            Method gsv = findDeclaredMethod(editor.getClass(), "getSourceViewer");
            if (gsv != null) {
                gsv.setAccessible(true);
                Object viewer = gsv.invoke(editor);
                if (viewer instanceof ITextViewer) {
                    IDocument doc = ((ITextViewer) viewer).getDocument();
                    if (doc != null) return doc.get();
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private String extractFromTextEditor(ITextEditor textEditor) {
        IDocumentProvider provider = textEditor.getDocumentProvider();
        if (provider != null) {
            IDocument doc = provider.getDocument(textEditor.getEditorInput());
            if (doc != null) {
                return doc.get();
            }
        }
        return null;
    }

    private static Method findDeclaredMethod(Class<?> clazz, String name) {
        while (clazz != null) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    return m;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private void showFallbackDialog(ExecutionEvent event, List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        long critical = findings.stream()
            .filter(f -> f.getSeverity().getLevel() == 0).count();
        long warning = findings.stream()
            .filter(f -> f.getSeverity().getLevel() == 1).count();
        long info = findings.stream()
            .filter(f -> f.getSeverity().getLevel() == 2).count();

        sb.append("Analysis Complete\n\n");
        sb.append("CRITICAL: ").append(critical).append('\n');
        sb.append("WARNING:  ").append(warning).append('\n');
        sb.append("INFO:     ").append(info).append('\n');
        sb.append("Total:    ").append(findings.size()).append("\n\n");

        int limit = Math.min(findings.size(), 20);
        for (int i = 0; i < limit; i++) {
            Finding f = findings.get(i);
            sb.append(String.format("[%s] %s - %s (Line %d)\n",
                f.getSeverity().getLabel().toUpperCase(),
                f.getRuleId(), f.getRuleName(), f.getLineStart()));
        }
        if (findings.size() > 20) {
            sb.append("\n... and ").append(findings.size() - 20).append(" more findings.");
        }

        MessageDialog.openInformation(
            HandlerUtil.getActiveShell(event),
            "Clean Core Analysis Results",
            sb.toString());
    }
}
