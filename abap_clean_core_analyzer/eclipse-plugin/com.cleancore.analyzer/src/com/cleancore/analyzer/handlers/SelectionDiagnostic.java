package com.cleancore.analyzer.handlers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.model.IWorkbenchAdapter;
import org.eclipse.ui.progress.IDeferredWorkbenchAdapter;
import org.eclipse.ui.progress.IElementCollector;

/**
 * Pure static utility that builds a textual diagnostic report for a Project
 * Explorer selection. Used by:
 *
 *   - {@code InspectSelectionHandler} (manual command)
 *   - {@code PackageAnalyzeHandler}   (automatic fallback when analysis
 *                                       could not produce any source)
 *
 * The output is plain text divided into sections:
 *   === Identity ===
 *   === Active Part ===
 *   === Adapters ===
 *   === Getters ===
 *   === Content Provider ===
 *
 * Every probe is wrapped in try/catch so a single failure cannot eat the rest
 * of the report. The total length is capped at ~100 KB.
 */
public final class SelectionDiagnostic {

    private static final int MAX_REPORT_BYTES = 100 * 1024;
    private static final int MAX_VALUE_CHARS  = 200;
    private static final int MAX_CHILDREN_SHOWN = 5;
    private static final int DEFERRED_TIMEOUT_SECONDS = 5;

    /** Skip these method names when reflectively snapshotting getters. */
    private static final Set<String> GETTER_SKIP_LIST = new HashSet<>(
        Arrays.asList("getClass", "hashCode", "toString", "getAdapter",
                      "wait", "notify", "notifyAll"));

    private static final String IADTOBJREF =
        "com.sap.adt.tools.core.model.adtcore.IAdtObjectReference";
    private static final String IADTOBJREF_PROVIDER =
        "com.sap.adt.tools.core.IAdtObjectReferenceProvider";
    private static final String IPROPERTY_SOURCE =
        "org.eclipse.ui.views.properties.IPropertySource";

    private SelectionDiagnostic() {}

    // ── Public entry point ───────────────────────────────────────────

    /**
     * Builds a multi-section diagnostic report for one selected node.
     * Never throws; on internal failure returns a single-line error report.
     */
    public static String collect(Object node, IWorkbenchPart activePart) {
        StringBuilder sb = new StringBuilder(8 * 1024);
        try {
            appendIdentity(sb, node);
            appendActivePart(sb, activePart);
            appendAdapters(sb, node);
            appendGetters(sb, node);
            appendContentProvider(sb, node, activePart);
        } catch (Throwable t) {
            sb.append("\n!! SelectionDiagnostic.collect aborted: ")
              .append(t.getClass().getName()).append(": ").append(t.getMessage())
              .append("\n");
        }
        return cap(sb.toString());
    }

    /**
     * Builds a report covering multiple nodes (used by automatic fallback).
     */
    public static String collectAll(Iterable<?> nodes, IWorkbenchPart activePart) {
        StringBuilder sb = new StringBuilder(16 * 1024);
        int idx = 0;
        for (Object node : nodes) {
            idx++;
            sb.append("######################## Node #").append(idx)
              .append(" ########################\n");
            sb.append(collect(node, activePart));
            sb.append("\n");
            if (sb.length() > MAX_REPORT_BYTES) {
                sb.append("\n... (report capped, ").append(idx)
                  .append(" nodes shown)\n");
                break;
            }
        }
        return cap(sb.toString());
    }

    // ── Section: Identity ────────────────────────────────────────────

    private static void appendIdentity(StringBuilder sb, Object node) {
        sb.append("=== Identity ===\n");
        if (node == null) {
            sb.append("node = null\n\n");
            return;
        }
        sb.append("toString()      : ").append(truncate(safeToString(node))).append("\n");
        sb.append("Class           : ").append(node.getClass().getName()).append("\n");
        sb.append("ClassLoader     : ");
        try {
            ClassLoader cl = node.getClass().getClassLoader();
            sb.append(cl == null ? "<bootstrap>" : cl.toString()).append("\n");
        } catch (Throwable t) {
            sb.append("<error: ").append(t.getClass().getSimpleName()).append(">\n");
        }
        sb.append("Hierarchy       : ").append(classHierarchy(node.getClass())).append("\n");
        sb.append("Interfaces      : ").append(interfaceList(node.getClass())).append("\n\n");
    }

    // ── Section: Active part ─────────────────────────────────────────

    private static void appendActivePart(StringBuilder sb, IWorkbenchPart part) {
        sb.append("=== Active Part ===\n");
        if (part == null) {
            sb.append("activePart      : <null>\n\n");
            return;
        }
        sb.append("Class           : ").append(part.getClass().getName()).append("\n");
        try {
            sb.append("Title           : ").append(part.getTitle()).append("\n");
        } catch (Throwable ignored) {}
        try {
            IWorkbenchPartSite site = part.getSite();
            if (site != null) {
                sb.append("Site.id         : ").append(site.getId()).append("\n");
                sb.append("Site.partName   : ").append(site.getRegisteredName()).append("\n");
            }
        } catch (Throwable ignored) {}
        sb.append("\n");
    }

    // ── Section: Adapter probe matrix ────────────────────────────────

    private static void appendAdapters(StringBuilder sb, Object node) {
        sb.append("=== Adapters ===\n");
        if (node == null) { sb.append("<null>\n\n"); return; }

        probe(sb, "IResource",       adaptName(node, IResource.class));
        probe(sb, "IFile",           adaptName(node, IFile.class));
        probe(sb, "IContainer",      adaptName(node, IContainer.class));
        probe(sb, "IProject",        adaptName(node, IProject.class));
        probe(sb, "IEditorInput",    adaptName(node, IEditorInput.class));

        // IWorkbenchAdapter
        IWorkbenchAdapter wa = adaptTo(node, IWorkbenchAdapter.class);
        if (wa == null) {
            probe(sb, "IWorkbenchAdapter", null);
        } else {
            sb.append("  IWorkbenchAdapter           : ").append(wa.getClass().getName()).append("\n");
            try {
                Object[] kids = wa.getChildren(node);
                int n = kids == null ? 0 : kids.length;
                sb.append("    .getChildren().length     = ").append(n).append("\n");
                int show = Math.min(MAX_CHILDREN_SHOWN, n);
                for (int i = 0; i < show; i++) {
                    Object k = kids[i];
                    sb.append("    [").append(i).append("] ")
                      .append(k == null ? "<null>" : k.getClass().getName())
                      .append(" -> ").append(truncate(safeToString(k))).append("\n");
                }
            } catch (Throwable t) {
                sb.append("    .getChildren() threw: ")
                  .append(t.getClass().getName()).append("\n");
            }
            try {
                String lbl = wa.getLabel(node);
                sb.append("    .getLabel()               = ").append(lbl).append("\n");
            } catch (Throwable ignored) {}
        }

        // IDeferredWorkbenchAdapter (5 sec timeout)
        IDeferredWorkbenchAdapter dwa = adaptTo(node, IDeferredWorkbenchAdapter.class);
        if (dwa == null) {
            probe(sb, "IDeferredWorkbenchAdapter", null);
        } else {
            sb.append("  IDeferredWorkbenchAdapter   : ").append(dwa.getClass().getName()).append("\n");
            try {
                final List<Object> collected = new ArrayList<>();
                final CountDownLatch latch = new CountDownLatch(1);
                IElementCollector collector = new IElementCollector() {
                    @Override public void add(Object element, IProgressMonitor mon) {
                        if (element != null) synchronized (collected) { collected.add(element); }
                    }
                    @Override public void add(Object[] elements, IProgressMonitor mon) {
                        if (elements != null) synchronized (collected) {
                            for (Object e : elements) if (e != null) collected.add(e);
                        }
                    }
                    @Override public void done() { latch.countDown(); }
                };
                dwa.fetchDeferredChildren(node, collector, new NullProgressMonitor());
                boolean finished = latch.await(DEFERRED_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                int n;
                synchronized (collected) { n = collected.size(); }
                sb.append("    fetchDeferredChildren     = ").append(n)
                  .append(finished ? " (done)" : " (timeout after "
                          + DEFERRED_TIMEOUT_SECONDS + "s)").append("\n");
                int show = Math.min(MAX_CHILDREN_SHOWN, n);
                synchronized (collected) {
                    for (int i = 0; i < show; i++) {
                        Object k = collected.get(i);
                        sb.append("    [").append(i).append("] ")
                          .append(k == null ? "<null>" : k.getClass().getName())
                          .append(" -> ").append(truncate(safeToString(k))).append("\n");
                    }
                }
            } catch (Throwable t) {
                sb.append("    fetchDeferredChildren threw: ")
                  .append(t.getClass().getName()).append("\n");
            }
        }

        // ADT IAdtObjectReference (Class.forName, may not exist on this Eclipse)
        Class<?> refCls = forName(IADTOBJREF);
        if (refCls == null) {
            sb.append("  IAdtObjectReference         : <class not loadable>\n");
        } else {
            Object adt = adaptToCls(node, refCls);
            if (adt == null) adt = AdtTraverser.getAdtObjectReference(node);
            if (adt == null) {
                probe(sb, "IAdtObjectReference", null);
            } else {
                sb.append("  IAdtObjectReference         : ")
                  .append(adt.getClass().getName()).append("\n");
                sb.append("    .getType()                = ")
                  .append(AdtTraverser.getObjectType(adt)).append("\n");
                sb.append("    .getName()                = ")
                  .append(AdtTraverser.getObjectName(adt)).append("\n");
                sb.append("    .getUri()                 = ")
                  .append(AdtTraverser.getObjectUri(adt)).append("\n");
                sb.append("    .getPackageName()         = ")
                  .append(AdtTraverser.getPackageName(adt)).append("\n");
                sb.append("    destinationId             = ")
                  .append(AdtTraverser.getDestinationId(node)).append("\n");
            }
        }

        Class<?> provCls = forName(IADTOBJREF_PROVIDER);
        if (provCls != null) {
            Object prov = adaptToCls(node, provCls);
            probe(sb, "IAdtObjectReferenceProvider",
                  prov != null ? prov.getClass().getName() : null);
        }

        Class<?> propCls = forName(IPROPERTY_SOURCE);
        if (propCls != null) {
            Object ps = adaptToCls(node, propCls);
            probe(sb, "IPropertySource",
                  ps != null ? ps.getClass().getName() : null);
        }

        // Unwrap candidate (helps fix Favorite Packages wrappers)
        try {
            Object inner = AdtTraverser.unwrapWrapperNode(node);
            if (inner != null && inner != node) {
                sb.append("  Wrapper unwrap candidate    : ")
                  .append(inner.getClass().getName())
                  .append(" -> ").append(truncate(safeToString(inner))).append("\n");
            } else {
                sb.append("  Wrapper unwrap candidate    : <none>\n");
            }
        } catch (Throwable ignored) {}
        sb.append("\n");
    }

    // ── Section: Reflective no-arg getter snapshot ───────────────────

    private static void appendGetters(StringBuilder sb, Object node) {
        sb.append("=== Getters ===\n");
        if (node == null) { sb.append("<null>\n\n"); return; }
        Set<String> seenSig = new HashSet<>();
        Class<?> cur = node.getClass();
        int printed = 0;
        while (cur != null && !cur.equals(Object.class)) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                String n = m.getName();
                if (GETTER_SKIP_LIST.contains(n)) continue;
                if (!(n.startsWith("get") || n.startsWith("is"))) continue;
                if (!seenSig.add(n)) continue;

                Class<?> rt = m.getReturnType();
                if (!isSimpleType(rt)) continue;

                try {
                    m.setAccessible(true);
                    Object v = m.invoke(node);
                    sb.append("  ").append(n).append("() = ")
                      .append(truncate(String.valueOf(v))).append("\n");
                    printed++;
                } catch (Throwable t) {
                    sb.append("  ").append(n).append("() threw ")
                      .append(t.getClass().getSimpleName()).append("\n");
                }
            }
            cur = cur.getSuperclass();
        }
        if (printed == 0) sb.append("  (no simple-return getters found)\n");
        sb.append("\n");
    }

    private static boolean isSimpleType(Class<?> t) {
        return t.equals(String.class)
            || t.equals(Boolean.class) || t.equals(boolean.class)
            || t.equals(Integer.class) || t.equals(int.class)
            || t.equals(Long.class)    || t.equals(long.class);
    }

    // ── Section: CommonNavigator content provider ────────────────────

    private static void appendContentProvider(StringBuilder sb, Object node,
                                              IWorkbenchPart part) {
        sb.append("=== Content Provider ===\n");
        if (part == null) { sb.append("(no active part)\n\n"); return; }
        try {
            Method gcv = findNoArg(part.getClass(), "getCommonViewer");
            if (gcv == null) gcv = findNoArg(part.getClass(), "getViewer");
            if (gcv == null) {
                sb.append("active part has no getCommonViewer/getViewer\n\n");
                return;
            }
            Object viewer = gcv.invoke(part);
            if (viewer == null) {
                sb.append("viewer is null\n\n");
                return;
            }
            sb.append("Viewer            : ").append(viewer.getClass().getName()).append("\n");
            Method gcp = findNoArg(viewer.getClass(), "getContentProvider");
            if (gcp == null) {
                sb.append("viewer has no getContentProvider\n\n");
                return;
            }
            Object provider = gcp.invoke(viewer);
            if (provider == null) {
                sb.append("content provider is null\n\n");
                return;
            }
            sb.append("ContentProvider   : ").append(provider.getClass().getName()).append("\n");
            Method gc = findOneArg(provider.getClass(), "getChildren", Object.class);
            if (gc == null) {
                sb.append("provider has no getChildren(Object)\n\n");
                return;
            }
            Object kids = gc.invoke(provider, node);
            if (kids instanceof Object[]) {
                Object[] arr = (Object[]) kids;
                sb.append("getChildren(node) = ").append(arr.length).append("\n");
                int show = Math.min(MAX_CHILDREN_SHOWN, arr.length);
                for (int i = 0; i < show; i++) {
                    Object k = arr[i];
                    sb.append("  [").append(i).append("] ")
                      .append(k == null ? "<null>" : k.getClass().getName())
                      .append(" -> ").append(truncate(safeToString(k))).append("\n");
                }
            } else {
                sb.append("getChildren returned: ")
                  .append(kids == null ? "null" : kids.getClass().getName()).append("\n");
            }
        } catch (Throwable t) {
            sb.append("Content provider probe failed: ")
              .append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
        }
        sb.append("\n");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static <T> String adaptName(Object node, Class<T> cls) {
        T t = adaptTo(node, cls);
        return t == null ? null : t.getClass().getName();
    }

    @SuppressWarnings("unchecked")
    private static <T> T adaptTo(Object node, Class<T> cls) {
        if (cls.isInstance(node)) return (T) node;
        if (node instanceof IAdaptable) {
            Object a = ((IAdaptable) node).getAdapter(cls);
            if (a != null) return (T) a;
        }
        return null;
    }

    private static Object adaptToCls(Object node, Class<?> cls) {
        if (cls.isInstance(node)) return node;
        if (node instanceof IAdaptable) {
            return ((IAdaptable) node).getAdapter(cls);
        }
        return null;
    }

    private static void probe(StringBuilder sb, String label, String value) {
        sb.append("  ").append(pad(label, 28)).append(": ")
          .append(value == null ? "(not adaptable)" : value).append("\n");
    }

    private static Class<?> forName(String fqn) {
        try { return Class.forName(fqn); }
        catch (Throwable t) { return null; }
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

    private static Method findNoArg(Class<?> c, String name) {
        try { return c.getMethod(name); } catch (NoSuchMethodException ignored) {}
        Class<?> cur = c;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static Method findOneArg(Class<?> c, String name, Class<?> paramType) {
        try { return c.getMethod(name, paramType); } catch (NoSuchMethodException ignored) {}
        Class<?> cur = c;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0].isAssignableFrom(paramType)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static String safeToString(Object o) {
        if (o == null) return "<null>";
        try { return String.valueOf(o); }
        catch (Throwable t) { return "<toString threw " + t.getClass().getSimpleName() + ">"; }
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        if (s.length() <= MAX_VALUE_CHARS) return s;
        return s.substring(0, MAX_VALUE_CHARS)
            + "... (+" + (s.length() - MAX_VALUE_CHARS) + " chars)";
    }

    private static String pad(String s, int n) {
        if (s == null) s = "";
        if (s.length() >= n) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    private static String cap(String s) {
        if (s == null) return "";
        if (s.length() <= MAX_REPORT_BYTES) return s;
        return s.substring(0, MAX_REPORT_BYTES)
            + "\n\n... (truncated at " + MAX_REPORT_BYTES + " bytes)\n";
    }
}
