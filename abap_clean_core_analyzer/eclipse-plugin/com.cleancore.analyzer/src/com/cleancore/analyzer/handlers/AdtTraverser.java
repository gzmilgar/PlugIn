package com.cleancore.analyzer.handlers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.model.IWorkbenchAdapter;
import org.eclipse.ui.progress.IDeferredWorkbenchAdapter;
import org.eclipse.ui.progress.IElementCollector;

/**
 * Reflection-based bridge to ADT (com.sap.adt.*) internal APIs.
 *
 * Every method is defensive: any reflective failure returns {@code null}
 * (or empty list) so the caller can fall back to other strategies.
 *
 * Why reflection?
 *   - Plugin must still load on Eclipse installations without ADT.
 *   - ADT internal APIs are not stable across versions; reflection makes
 *     graceful degradation easier.
 */
public final class AdtTraverser {

    private static final String IADTOBJECT_REF =
        "com.sap.adt.tools.core.model.adtcore.IAdtObjectReference";
    private static final String SESSION_FACTORY =
        "com.sap.adt.communication.session.AdtSystemSessionFactory";
    private static final String PROJECT_SVC_FACTORY =
        "com.sap.adt.tools.core.project.AdtProjectServiceFactory";

    private AdtTraverser() {}

    // ── Public helpers ───────────────────────────────────────────────

    /** Try to obtain an {@code IAdtObjectReference} from a Project Explorer node. */
    public static Object getAdtObjectReference(Object node) {
        if (node == null) return null;
        try {
            Class<?> refCls = Class.forName(IADTOBJECT_REF);
            Object cur = node;
            // Up to 4 unwrap levels (favorite -> package -> ref etc.)
            for (int depth = 0; depth < 4 && cur != null; depth++) {
                if (refCls.isInstance(cur)) return cur;
                if (cur instanceof IAdaptable) {
                    Object r = ((IAdaptable) cur).getAdapter(refCls);
                    if (r != null) return r;
                }
                for (String mname : new String[] {
                        "getObjectReference", "getReference", "getAdtObjectReference" }) {
                    Object r = tryInvoke(cur, mname);
                    if (r != null && refCls.isInstance(r)) return r;
                }
                Object inner = unwrapWrapperNode(cur);
                if (inner == null || inner == cur) break;
                cur = inner;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Some Project Explorer extensions wrap the real ADT node inside a
     * container object (e.g. Favorite Packages, recent objects, search hits).
     * This tries a list of common getter names to retrieve the underlying
     * node. Returns {@code null} if no unwrap is possible.
     */
    public static Object unwrapWrapperNode(Object node) {
        if (node == null) return null;
        String cn = node.getClass().getName();
        // Cheap heuristic – never recurse from a plain ABAP object.
        if (cn.startsWith("java.")) return null;

        for (String mname : new String[] {
                "getElement", "getDelegate", "getReferencedObject",
                "getReferenced", "getReference", "getWrappedNode",
                "getWrapped", "getNode", "getPackage", "getValue",
                "getTarget", "getModel", "getModelObject",
                "getFavoritePackage", "getAdtPackage", "getAdtNode",
                "getAdtObject" }) {
            Object v = tryInvoke(node, mname);
            if (v == null || v == node) continue;
            // Skip primitives / strings / collections – not what we want.
            String vc = v.getClass().getName();
            if (vc.startsWith("java.lang.") || vc.startsWith("java.util.")) continue;
            return v;
        }
        return null;
    }

    public static String getObjectType(Object adtRef) {
        Object v = tryInvoke(adtRef, "getType");
        return v instanceof String ? (String) v : null;
    }

    public static String getObjectName(Object adtRef) {
        Object v = tryInvoke(adtRef, "getName");
        return v instanceof String ? (String) v : null;
    }

    public static String getObjectUri(Object adtRef) {
        Object v = tryInvoke(adtRef, "getUri");
        return v instanceof String ? (String) v : null;
    }

    public static String getPackageName(Object adtRef) {
        Object v = tryInvoke(adtRef, "getPackageName");
        return v instanceof String ? (String) v : null;
    }

    public static boolean isPackage(String type) {
        return type != null && type.startsWith("DEVC");
    }

    /** CLAS, PROG, FUGR, FUNC, INCL or PROG/I variants. */
    public static boolean isAnalyzable(String type) {
        if (type == null) return false;
        String t = type.toUpperCase();
        return t.startsWith("CLAS")
            || t.startsWith("PROG")
            || t.startsWith("FUGR")
            || t.startsWith("FUNC")
            || t.startsWith("INCL");
    }

    public static boolean isInterface(String type) {
        return type != null && type.toUpperCase().startsWith("INTF");
    }

    // ── Destination resolution ───────────────────────────────────────

    /** Walk node → IProject → IAbapProject → destinationId. All reflective. */
    public static String getDestinationId(Object node) {
        if (node == null) return null;
        IProject project = resolveProjectAnyWay(node);
        if (project == null) return null;

        try {
            Class<?> projFactoryCls = Class.forName(PROJECT_SVC_FACTORY);
            Method create = projFactoryCls.getMethod("createProjectService");
            Object service = create.invoke(null);
            // getAbapProject(IProject)
            Object abapProject = tryInvoke(service, "getAbapProject", new Class[] { IProject.class }, new Object[] { project });
            if (abapProject == null) return null;
            Object destData = tryInvoke(abapProject, "getDestinationData");
            if (destData != null) {
                Object id = tryInvoke(destData, "getDestinationId");
                if (id instanceof String) return (String) id;
            }
            // Some ADT versions expose getDestinationId() directly
            Object id2 = tryInvoke(abapProject, "getDestinationId");
            if (id2 instanceof String) return (String) id2;
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Best-effort IProject resolution. First tries direct adaptation
     * (IAdaptable.getAdapter(IProject.class) and node.getProject()). If that
     * fails, walks up TreeNode-style parent chain — required for
     * VirtualFolderNode (Favorite Packages category) and similar wrappers
     * whose project association lives on a parent node. Final fallback is
     * the workspace: if the user has a single open ABAP project, use it; or
     * the project of the currently active editor.
     */
    public static IProject resolveProjectAnyWay(Object node) {
        if (node == null) return null;
        IProject direct = adaptToProject(node);
        if (direct != null) return direct;

        // Walk parent chain (TreeNode.getParent etc.)
        Object cur = node;
        for (int depth = 0; depth < 12; depth++) {
            Object parent = tryInvoke(cur, "getParent");
            if (parent == null || parent == cur) break;
            IProject p = adaptToProject(parent);
            if (p != null) return p;
            cur = parent;
        }

        // Fallback: active editor's project (if it is ABAP)
        IProject active = findActiveAbapProject();
        if (active != null) return active;

        // Fallback: single open ABAP project in workspace (avoid ambiguity)
        return findSingleOpenAbapProject();
    }

    private static IProject findActiveAbapProject() {
        try {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null) return null;
            IWorkbenchWindow win = wb.getActiveWorkbenchWindow();
            if (win == null) return null;
            IWorkbenchPage page = win.getActivePage();
            if (page == null) return null;
            IEditorPart editor = page.getActiveEditor();
            if (editor == null) return null;
            IEditorInput input = editor.getEditorInput();
            if (input == null) return null;

            Object p = input.getAdapter(IProject.class);
            if (p instanceof IProject && isAbapProject((IProject) p))
                return (IProject) p;
            Object f = input.getAdapter(IFile.class);
            if (f instanceof IFile) {
                IProject pp = ((IFile) f).getProject();
                if (pp != null && isAbapProject(pp)) return pp;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static IProject findSingleOpenAbapProject() {
        try {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IProject[] projects = root.getProjects();
            IProject first = null;
            int count = 0;
            for (IProject p : projects) {
                if (p == null || !p.isOpen()) continue;
                if (isAbapProject(p)) {
                    if (first == null) first = p;
                    count++;
                    if (count > 1) {
                        // Multiple ABAP projects open. Prefer the active one
                        // (which we already tried via findActiveAbapProject).
                        // Otherwise just return the first to keep moving.
                        return first;
                    }
                }
            }
            return first;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isAbapProject(IProject p) {
        if (p == null) return false;
        try {
            Class<?> projFactoryCls = Class.forName(PROJECT_SVC_FACTORY);
            Method create = projFactoryCls.getMethod("createProjectService");
            Object service = create.invoke(null);
            Object abapProject = tryInvoke(service, "getAbapProject",
                new Class[] { IProject.class }, new Object[] { p });
            return abapProject != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static IProject adaptToProject(Object node) {
        if (node == null) return null;
        if (node instanceof IProject) return (IProject) node;
        if (node instanceof IAdaptable) {
            Object p = ((IAdaptable) node).getAdapter(IProject.class);
            if (p instanceof IProject) return (IProject) p;
        }
        // Try a getProject() method (covers IProjectProvider)
        Object p = tryInvoke(node, "getProject");
        if (p instanceof IProject) return (IProject) p;
        return null;
    }

    // ── Direct repository object lists (VirtualFolderNode etc.) ──────

    /**
     * Some ADT Project Explorer nodes (notably {@code VirtualFolderNode} used
     * for "Favorite Packages" categories and for search hits) implement
     * {@code IAbapRepositoryObjectListProvider} and similar interfaces that
     * expose the contained {@code IAdtObjectReference} list directly —
     * bypassing the lazy CommonNavigator content provider that otherwise
     * returns a {@code WaitMessageNode} placeholder.
     *
     * <p>We do not hard-code the API method names because they differ across
     * ADT versions. Strategy:
     *
     * <ol>
     *   <li>Collect every method declared on every implemented interface
     *       whose name contains "Object" / "Repository" / "Reference"
     *       (case-insensitive), regardless of parameter count.</li>
     *   <li>Add every method on the node's own class whose return type is
     *       an array, a {@code Collection}/{@code Iterable},
     *       a {@code Future}, or a {@code CompletionStage}.</li>
     *   <li>For each candidate, supply default arguments
     *       ({@code IProgressMonitor} → {@code NullProgressMonitor}, primitives
     *       → zero/false, anything else → skip).</li>
     *   <li>Resolve {@code Future}/{@code CompletionStage} with 10 sec timeout.</li>
     *   <li>Walk the result, keep only {@code IAdtObjectReference} instances.</li>
     * </ol>
     *
     * Returns the first candidate that yields a non-empty list of
     * references. If nothing matches, walks one unwrap level and retries.
     */
    public static List<Object> getDirectRepositoryObjects(Object node) {
        List<Object> out = new ArrayList<>();
        if (node == null) return out;
        Class<?> refCls;
        try { refCls = Class.forName(IADTOBJECT_REF); }
        catch (Throwable t) { return out; }

        List<Method> candidates = collectRefCandidateMethods(node);
        for (Method m : candidates) {
            Object[] args = supplyArgs(m);
            if (args == null) continue;
            try {
                m.setAccessible(true);
                Object r = m.invoke(node, args);
                r = resolveAsync(r);
                if (r == null) continue;
                List<Object> tmp = new ArrayList<>();
                collectReferences(r, refCls, tmp);
                if (!tmp.isEmpty()) {
                    out.addAll(tmp);
                    return out;
                }
            } catch (Throwable ignored) {}
        }

        // Fallback: try unwrapped inner node
        Object inner = unwrapWrapperNode(node);
        if (inner != null && inner != node) {
            List<Object> innerObjs = getDirectRepositoryObjects(inner);
            if (innerObjs != null) out.addAll(innerObjs);
        }
        return out;
    }

    private static List<Method> collectRefCandidateMethods(Object node) {
        Set<Method> set = new LinkedHashSet<>();

        // 1) Methods declared on each implemented interface that smell like
        //    object/repository/reference list providers.
        Set<Class<?>> ifaces = new LinkedHashSet<>();
        collectAllInterfaces(node.getClass(), ifaces);
        for (Class<?> i : ifaces) {
            String n = i.getName().toLowerCase();
            if (!(n.contains("object") || n.contains("repository")
                  || n.contains("reference") || n.contains("provider"))) continue;
            for (Method m : i.getMethods()) {
                if (!m.getName().toLowerCase().startsWith("get")) continue;
                set.add(m);
            }
            for (Method m : i.getDeclaredMethods()) {
                if (!m.getName().toLowerCase().startsWith("get")) continue;
                set.add(m);
            }
        }

        // 2) Methods on the node's own class with collection/array/future
        //    return types.
        Class<?> cur = node.getClass();
        while (cur != null && !cur.equals(Object.class)) {
            for (Method m : cur.getDeclaredMethods()) {
                if (!m.getName().toLowerCase().startsWith("get")) continue;
                Class<?> rt = m.getReturnType();
                if (rt.isArray()
                    || Iterable.class.isAssignableFrom(rt)
                    || Future.class.isAssignableFrom(rt)
                    || CompletionStage.class.isAssignableFrom(rt)) {
                    set.add(m);
                }
            }
            cur = cur.getSuperclass();
        }
        return new ArrayList<>(set);
    }

    private static void collectAllInterfaces(Class<?> c, Set<Class<?>> out) {
        while (c != null && !c.equals(Object.class)) {
            for (Class<?> i : c.getInterfaces()) {
                if (out.add(i)) collectAllInterfaces(i, out);
            }
            c = c.getSuperclass();
        }
    }

    private static Object[] supplyArgs(Method m) {
        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (IProgressMonitor.class.isAssignableFrom(t)) {
                args[i] = new NullProgressMonitor();
            } else if (t.isPrimitive()) {
                args[i] = defaultPrimitive(t);
            } else if (t.equals(String.class)) {
                args[i] = "";
            } else if (t.equals(Object.class)) {
                args[i] = null;
            } else {
                return null; // unsupported param type → skip
            }
        }
        return args;
    }

    private static Object defaultPrimitive(Class<?> c) {
        if (c == boolean.class) return Boolean.FALSE;
        if (c == byte.class)    return (byte) 0;
        if (c == short.class)   return (short) 0;
        if (c == int.class)     return 0;
        if (c == long.class)    return 0L;
        if (c == float.class)   return 0f;
        if (c == double.class)  return 0d;
        if (c == char.class)    return '\0';
        return null;
    }

    private static Object resolveAsync(Object r) {
        if (r == null) return null;
        try {
            if (r instanceof Future) {
                return ((Future<?>) r).get(10, TimeUnit.SECONDS);
            }
            if (r instanceof CompletionStage) {
                return ((CompletionStage<?>) r).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            }
        } catch (Throwable ignored) {}
        return r;
    }

    private static void collectReferences(Object r, Class<?> refCls,
                                          List<Object> out) {
        if (r == null) return;
        if (r instanceof Iterable) {
            for (Object o : (Iterable<?>) r) {
                if (o != null && refCls.isInstance(o)) out.add(o);
            }
        } else if (r.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(r);
            for (int i = 0; i < len; i++) {
                Object o = java.lang.reflect.Array.get(r, i);
                if (o != null && refCls.isInstance(o)) out.add(o);
            }
        } else if (refCls.isInstance(r)) {
            out.add(r);
        }
    }

    // ── Children listing via nodestructure REST ──────────────────────

    /**
     * Expands a Project Explorer node into its direct children using
     * pure Eclipse standard APIs. Tries, in order:
     *   1. {@link IWorkbenchAdapter#getChildren(Object)} (synchronous)
     *   2. {@link IDeferredWorkbenchAdapter#fetchDeferredChildren} (async, we collect)
     *   3. {@link IWorkbenchPart} active-part navigator content provider
     *
     * Returns an empty array if none worked. Never throws.
     */
    public static Object[] expandChildrenViaWorkbench(Object node,
                                                      IWorkbenchPart activePart,
                                                      IProgressMonitor monitor) {
        return expandChildrenViaWorkbench(node, activePart, monitor, null);
    }

    /**
     * Diagnostic variant: if {@code reasons} is non-null, each strategy that was
     * tried but produced no children is reported into it.
     */
    public static Object[] expandChildrenViaWorkbench(Object node,
                                                      IWorkbenchPart activePart,
                                                      IProgressMonitor monitor,
                                                      List<String> reasons) {
        if (node == null) {
            if (reasons != null) reasons.add("node is null");
            return new Object[0];
        }
        if (monitor == null) monitor = new NullProgressMonitor();

        // 1) IWorkbenchAdapter
        try {
            IWorkbenchAdapter wa = adapt(node, IWorkbenchAdapter.class);
            if (wa != null) {
                Object[] kids = wa.getChildren(node);
                if (kids != null && kids.length > 0) return kids;
                if (reasons != null) {
                    reasons.add("IWorkbenchAdapter returned 0 children");
                }
            } else if (reasons != null) {
                reasons.add("IWorkbenchAdapter not adaptable");
            }
        } catch (Throwable t) {
            if (reasons != null) {
                reasons.add("IWorkbenchAdapter threw: " + t.getClass().getSimpleName());
            }
        }

        // 2) IDeferredWorkbenchAdapter (ADT uses this for lazy loading)
        try {
            IDeferredWorkbenchAdapter dwa = adapt(node, IDeferredWorkbenchAdapter.class);
            if (dwa != null) {
                final List<Object> collected = new ArrayList<>();
                final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
                IElementCollector collector = new IElementCollector() {
                    @Override
                    public void add(Object element, IProgressMonitor mon) {
                        if (element != null) {
                            synchronized (collected) { collected.add(element); }
                        }
                    }
                    @Override
                    public void add(Object[] elements, IProgressMonitor mon) {
                        if (elements != null) {
                            synchronized (collected) {
                                for (Object e : elements) {
                                    if (e != null) collected.add(e);
                                }
                            }
                        }
                    }
                    @Override
                    public void done() { latch.countDown(); }
                };
                dwa.fetchDeferredChildren(node, collector, monitor);
                try {
                    // Asynchronous adapter — wait up to 30s for it to finish.
                    latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                synchronized (collected) {
                    if (!collected.isEmpty()) {
                        return collected.toArray();
                    }
                }
                if (reasons != null) {
                    reasons.add("IDeferredWorkbenchAdapter returned 0 children (timeout or empty)");
                }
            } else if (reasons != null) {
                reasons.add("IDeferredWorkbenchAdapter not adaptable");
            }
        } catch (Throwable t) {
            if (reasons != null) {
                reasons.add("IDeferredWorkbenchAdapter threw: " + t.getClass().getSimpleName());
            }
        }

        // 3) CommonNavigator content provider (Project Explorer)
        try {
            Object[] kids = expandViaContentProvider(activePart, node);
            if (kids != null && kids.length > 0) return kids;
            if (reasons != null) {
                reasons.add("CommonNavigator content provider returned no children"
                    + (activePart == null ? " (activePart null)" : ""));
            }
        } catch (Throwable t) {
            if (reasons != null) {
                reasons.add("CommonNavigator content provider threw: "
                    + t.getClass().getSimpleName());
            }
        }

        return new Object[0];
    }

    @SuppressWarnings("unchecked")
    private static <T> T adapt(Object obj, Class<T> cls) {
        if (cls.isInstance(obj)) return (T) obj;
        if (obj instanceof IAdaptable) {
            Object a = ((IAdaptable) obj).getAdapter(cls);
            if (a != null) return (T) a;
        }
        // org.eclipse.core.runtime.Adapters.adapt would do it too, but we
        // keep the dependency minimal.
        return null;
    }

    private static Object[] expandViaContentProvider(IWorkbenchPart activePart,
                                                     Object node) {
        if (activePart == null) return null;
        try {
            // CommonNavigator.getCommonViewer()
            Method gcv = findNoArg(activePart.getClass(), "getCommonViewer");
            if (gcv == null) gcv = findNoArg(activePart.getClass(), "getViewer");
            if (gcv == null) return null;
            Object viewer = gcv.invoke(activePart);
            if (viewer == null) return null;
            Method gcp = findNoArg(viewer.getClass(), "getContentProvider");
            if (gcp == null) return null;
            Object provider = gcp.invoke(viewer);
            if (provider == null) return null;
            Method gc = findMethod(provider.getClass(), "getChildren", Object.class);
            if (gc == null) return null;
            Object result = gc.invoke(provider, node);
            if (result instanceof Object[]) return (Object[]) result;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Method findNoArg(Class<?> c, String name) {
        try { return c.getMethod(name); } catch (NoSuchMethodException e) {}
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

    // ── Children listing via nodestructure REST (kept as last-resort) ──

    /**
     * Lists child nodes of an ADT package using the
     * /sap/bc/adt/repository/nodestructure endpoint.
     *
     * @return list of {@link AdtRestParser.NodeInfo}
     */
    public static List<AdtRestParser.NodeInfo> listPackageChildren(
            String destinationId, String packageName, IProgressMonitor monitor) {
        List<AdtRestParser.NodeInfo> empty = new ArrayList<>();
        if (destinationId == null || packageName == null) return empty;

        String relativeUri = "/sap/bc/adt/repository/nodestructure"
            + "?parent_name=" + packageName
            + "&parent_type=DEVC%2FK"
            + "&withShortDescriptions=true";

        byte[] bytes = restGet(destinationId, relativeUri, "POST", monitor);
        if (bytes == null || bytes.length == 0) return empty;
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            return AdtRestParser.parseNodeStructure(is);
        } catch (Throwable ignored) {
            return empty;
        }
    }

    // ── Source fetch via REST ────────────────────────────────────────

    /**
     * Fetches the ABAP source for a given ADT object reference.
     * Tries the standard {object}/source/main suffix and a few variants.
     */
    public static String fetchSource(String destinationId, AdtRestParser.NodeInfo node,
                                     IProgressMonitor monitor) {
        if (node == null || node.uri == null || destinationId == null) return null;
        String base = node.uri;
        if (base.startsWith("http")) {
            int idx = base.indexOf("/sap/bc/adt/");
            if (idx >= 0) base = base.substring(idx);
        }

        String[] candidates;
        String type = node.type == null ? "" : node.type.toUpperCase();
        if (type.startsWith("FUGR/F")) {
            // single function module - no direct source endpoint in nodestructure
            candidates = new String[] { base + "/source/main", base };
        } else {
            candidates = new String[] {
                base + "/source/main",
                base + "/source",
                base
            };
        }
        for (String c : candidates) {
            byte[] bytes = restGet(destinationId, c, "GET", monitor);
            if (bytes != null && bytes.length > 0) {
                String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                if (looksLikeAbap(text)) return text;
            }
        }
        return null;
    }

    private static boolean looksLikeAbap(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String t = text.toLowerCase();
        // Tiny heuristic – avoid returning HTML error pages.
        if (t.startsWith("<html") || t.startsWith("<!doctype")) return false;
        return true;
    }

    // ── REST core (ADT session → URLConnection fallback) ─────────────

    /**
     * Performs a REST call via ADT session (reflection) or, if that fails,
     * via {@link HttpURLConnection} as a last-resort fallback.
     */
    private static byte[] restGet(String destinationId, String relativeUri,
                                  String httpMethod, IProgressMonitor monitor) {
        // 1. Try ADT session reflectively.
        byte[] data = restViaAdtSession(destinationId, relativeUri, httpMethod, monitor);
        if (data != null) return data;
        // 2. We cannot reliably build a URL without destination metadata,
        //    so we skip the HttpURLConnection fallback unless we know the host.
        //    (Host can be resolved via destination data; not implemented here.)
        return null;
    }

    private static byte[] restViaAdtSession(String destinationId, String relativeUri,
                                            String httpMethod, IProgressMonitor monitor) {
        if (monitor == null) monitor = new NullProgressMonitor();
        try {
            Class<?> sessionFactoryCls = Class.forName(SESSION_FACTORY);
            Method create = sessionFactoryCls.getMethod(
                "createStatelessSession", String.class, String.class);
            Object session = create.invoke(null, destinationId, "Clean Core Analyzer");
            if (session == null) return null;

            URI uri = URI.create(relativeUri);
            Method createRest = findMethod(session.getClass(),
                "createRestResource", URI.class);
            if (createRest == null) return null;
            Object restRes = createRest.invoke(session, uri);
            if (restRes == null) return null;

            // Find a "get" method that accepts (IProgressMonitor, IContentHandler)
            // We provide a byte[] content handler implemented via dynamic proxy.
            Class<?> handlerCls = Class.forName(
                "com.sap.adt.communication.content.IContentHandler");

            Object handler = createByteArrayContentHandler(handlerCls);
            if (handler == null) return null;

            Method get = findMethod(restRes.getClass(), "get",
                IProgressMonitor.class, handlerCls);
            if (get == null) {
                // Some versions: get(IProgressMonitor, Map, IContentHandler)
                get = findGet3(restRes.getClass(), handlerCls);
                if (get == null) return null;
                Object res = get.invoke(restRes, monitor,
                    new java.util.HashMap<String, String>(), handler);
                return extractBytes(res);
            }

            Object res;
            if ("POST".equalsIgnoreCase(httpMethod)) {
                // For POST, try post(IProgressMonitor, IContentHandler, IContent body, IContentHandler bodyHandler)
                // We don't have a body; many ADT endpoints accept POST without body for nodestructure.
                Method post = findMethod(restRes.getClass(), "post",
                    IProgressMonitor.class, handlerCls);
                if (post != null) {
                    res = post.invoke(restRes, monitor, handler);
                } else {
                    res = get.invoke(restRes, monitor, handler);
                }
            } else {
                res = get.invoke(restRes, monitor, handler);
            }
            return extractBytes(res);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findGet3(Class<?> c, Class<?> handlerCls) {
        for (Method m : c.getMethods()) {
            if (!"get".equals(m.getName())) continue;
            if (m.getParameterCount() != 3) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p[0].equals(IProgressMonitor.class)
                && (p[2].equals(handlerCls) || handlerCls.isAssignableFrom(p[2]))) {
                return m;
            }
        }
        return null;
    }

    private static byte[] extractBytes(Object result) {
        if (result == null) return null;
        if (result instanceof byte[]) return (byte[]) result;
        if (result instanceof String) {
            return ((String) result).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        // Some content handlers return the raw bytes back through invocation handler state.
        Object inner = tryInvoke(result, "getBytes");
        if (inner instanceof byte[]) return (byte[]) inner;
        return null;
    }

    /**
     * Builds a dynamic proxy that implements ADT's IContentHandler<byte[]>,
     * returning the raw response bytes.
     */
    private static Object createByteArrayContentHandler(Class<?> handlerCls) {
        try {
            ClassLoader cl = handlerCls.getClassLoader();
            java.lang.reflect.InvocationHandler ih =
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        try {
                            if ("getContentTypes".equals(name)
                                    || "getSupportedContentTypes".equals(name)) {
                                return new String[] { "*/*" };
                            }
                            if ("getResourceType".equals(name)
                                    || "getType".equals(name)) {
                                return byte[].class;
                            }
                            if ("deserialize".equals(name)) {
                                // (InputStream, IResponse) usually
                                for (Object a : args) {
                                    if (a instanceof InputStream) {
                                        return readAll((InputStream) a);
                                    }
                                }
                                return null;
                            }
                            if ("serialize".equals(name)) {
                                return null;
                            }
                        } catch (Throwable ignored) {}
                        if (method.getReturnType().equals(boolean.class)) return Boolean.FALSE;
                        return null;
                    }
                };
            return java.lang.reflect.Proxy.newProxyInstance(
                cl, new Class[] { handlerCls }, ih);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ── Reflection utilities ─────────────────────────────────────────

    private static Method findMethod(Class<?> c, String name, Class<?>... paramTypes) {
        try {
            return c.getMethod(name, paramTypes);
        } catch (NoSuchMethodException ignored) {}
        Class<?> cur = c;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() != paramTypes.length) continue;
                Class<?>[] p = m.getParameterTypes();
                boolean ok = true;
                for (int i = 0; i < p.length; i++) {
                    if (!p[i].isAssignableFrom(paramTypes[i])
                        && !paramTypes[i].isAssignableFrom(p[i])) {
                        ok = false; break;
                    }
                }
                if (ok) {
                    m.setAccessible(true);
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static Object tryInvoke(Object target, String method) {
        return tryInvoke(target, method, new Class[0], new Object[0]);
    }

    private static Object tryInvoke(Object target, String method,
                                    Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        try {
            Method m = findMethod(target.getClass(), method, paramTypes);
            if (m == null) return null;
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }
}
