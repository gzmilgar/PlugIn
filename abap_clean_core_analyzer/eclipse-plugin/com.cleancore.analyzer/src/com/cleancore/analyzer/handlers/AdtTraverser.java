package com.cleancore.analyzer.handlers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

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
            if (refCls.isInstance(node)) return node;
            if (node instanceof IAdaptable) {
                Object r = ((IAdaptable) node).getAdapter(refCls);
                if (r != null) return r;
            }
            // Some ADT nodes have getReference() / getObjectReference()
            for (String mname : new String[] {
                    "getObjectReference", "getReference", "getAdtObjectReference" }) {
                Object r = tryInvoke(node, mname);
                if (r != null && refCls.isInstance(r)) return r;
            }
        } catch (Throwable ignored) {}
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
        IProject project = adaptToProject(node);
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

    private static IProject adaptToProject(Object node) {
        if (node instanceof IProject) return (IProject) node;
        if (node instanceof IAdaptable) {
            Object p = ((IAdaptable) node).getAdapter(IProject.class);
            if (p instanceof IProject) return (IProject) p;
        }
        // Try a getProject() method
        Object p = tryInvoke(node, "getProject");
        if (p instanceof IProject) return (IProject) p;
        return null;
    }

    // ── Children listing via nodestructure REST ──────────────────────

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
