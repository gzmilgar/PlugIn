package com.cleancore.analyzer.handlers;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Parses ADT nodestructure XML responses.
 *
 * Typical response (abridged):
 * <pre>
 *  &lt;asx:abap xmlns:asx="http://www.sap.com/abapxml" version="1.0"&gt;
 *    &lt;asx:values&gt;
 *      &lt;DATA&gt;
 *        &lt;TREE_CONTENT&gt;
 *          &lt;SEU_ADT_REPOSITORY_OBJ_NODE&gt;
 *            &lt;OBJECT_TYPE&gt;CLAS/OC&lt;/OBJECT_TYPE&gt;
 *            &lt;OBJECT_NAME&gt;ZNT_000_CL_001&lt;/OBJECT_NAME&gt;
 *            &lt;OBJECT_URI&gt;/sap/bc/adt/oo/classes/ZNT_000_CL_001&lt;/OBJECT_URI&gt;
 *            &lt;DESCRIPTION&gt;Some class&lt;/DESCRIPTION&gt;
 *          &lt;/SEU_ADT_REPOSITORY_OBJ_NODE&gt;
 *          ...
 *        &lt;/TREE_CONTENT&gt;
 *      &lt;/DATA&gt;
 *    &lt;/asx:values&gt;
 *  &lt;/asx:abap&gt;
 * </pre>
 */
public final class AdtRestParser {

    public static final class NodeInfo {
        public String type;
        public String name;
        public String uri;
        public String description;
        public String parentName;

        public NodeInfo() {}

        public NodeInfo(String type, String name, String uri) {
            this.type = type;
            this.name = name;
            this.uri = uri;
        }

        @Override
        public String toString() {
            return type + " " + name;
        }
    }

    private AdtRestParser() {}

    /**
     * Parses a nodestructure XML stream into a flat list of nodes.
     */
    public static List<NodeInfo> parseNodeStructure(InputStream in) {
        List<NodeInfo> out = new ArrayList<>();
        if (in == null) return out;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            dbf.setValidating(false);
            try {
                dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Throwable ignored) {}
            try {
                dbf.setFeature(
                    "http://xml.org/sax/features/external-general-entities", false);
            } catch (Throwable ignored) {}
            try {
                dbf.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Throwable ignored) {}

            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(in);

            // Try both the SEU_ADT_REPOSITORY_OBJ_NODE container and any
            // <objectReference> / <object> sibling formats some ADT versions emit.
            collect(doc.getDocumentElement(), out);
        } catch (Throwable ignored) {
            // bad xml, empty list returned
        }
        return out;
    }

    private static void collect(Element root, List<NodeInfo> out) {
        if (root == null) return;

        // Generic: any element whose name ends with NODE / OBJECT_REFERENCE
        // and contains both OBJECT_TYPE and OBJECT_NAME children counts.
        walk(root, out);
    }

    private static void walk(Node node, List<NodeInfo> out) {
        if (node == null) return;
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            Element el = (Element) node;
            String tag = el.getTagName();
            // Common containers:
            //   SEU_ADT_REPOSITORY_OBJ_NODE
            //   objectReference / object_reference
            //   OBJECT_REFERENCE
            //   adtcore:objectReference   (namespaced)
            if (looksLikeObjectNode(tag)) {
                NodeInfo n = extractNodeInfo(el);
                if (n != null) out.add(n);
                return; // do not recurse inside an obj node
            }
        }
        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            walk(kids.item(i), out);
        }
    }

    private static boolean looksLikeObjectNode(String tag) {
        if (tag == null) return false;
        String upper = tag.toUpperCase();
        return upper.endsWith("OBJ_NODE")
            || upper.endsWith("OBJECTREFERENCE")
            || upper.endsWith("OBJECT_REFERENCE")
            || upper.endsWith("OBJECTNODE")
            || (upper.contains("NODE") && upper.contains("REPOSITORY"));
    }

    private static NodeInfo extractNodeInfo(Element el) {
        NodeInfo n = new NodeInfo();
        n.type = firstText(el,
            "OBJECT_TYPE", "object_type", "type", "adtcore:type");
        n.name = firstText(el,
            "OBJECT_NAME", "object_name", "name", "adtcore:name");
        n.uri = firstText(el,
            "OBJECT_URI", "object_uri", "uri", "adtcore:uri");
        n.description = firstText(el,
            "DESCRIPTION", "description", "adtcore:description");
        n.parentName = firstText(el,
            "PARENT_NAME", "parent_name");

        // attribute fallback (XML attribute form, e.g. adtcore:type="CLAS/OC")
        if (n.type == null) n.type = el.getAttribute("type");
        if (n.name == null) n.name = el.getAttribute("name");
        if (n.uri == null) n.uri = el.getAttribute("uri");

        if (isBlank(n.type) || isBlank(n.name)) return null;
        return n;
    }

    private static String firstText(Element parent, String... tagNames) {
        for (String tn : tagNames) {
            NodeList nl = parent.getElementsByTagName(tn);
            if (nl != null && nl.getLength() > 0) {
                String t = textOf(nl.item(0));
                if (t != null && !t.isEmpty()) return t;
            }
        }
        return null;
    }

    private static String textOf(Node node) {
        if (node == null) return null;
        String t = node.getTextContent();
        return t == null ? null : t.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
