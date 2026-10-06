package com.github.catvod.spider;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;
import org.w3c.dom.Document;
import org.w3c.dom.DOMException;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

/** XPath context semantics used by Kazumi rules, including sibling axes and text nodes. */
final class KazumiXPath {
    final Node root;
    private final XPath xpath = XPathFactory.newInstance().newXPath();

    KazumiXPath(String html) {
        // Android's Harmony DocumentImpl has no owner document. jsoup W3CDom calls
        // Document.setUserData(), which crashes there even though desktop JAXP works.
        // Build the same parent/sibling-preserving DOM without jsoup's source back-links.
        // No XML text is parsed, so external entities and doctypes cannot be resolved.
        try {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Jsoup.parse(html).child(0).traverse(new NodeVisitor() {
                private Node parent = document;

                @Override
                public void head(org.jsoup.nodes.Node node, int depth) {
                    if (node instanceof Element element) {
                        org.w3c.dom.Element target;
                        try { target = document.createElement(element.tagName()); }
                        catch (DOMException invalidName) { target = document.createElement("span"); }
                        for (Attribute attribute : element.attributes()) {
                            try { target.setAttribute(attribute.getKey(), attribute.getValue()); }
                            catch (DOMException invalidName) { /* HTML-only attributes, e.g. @click. */ }
                        }
                        parent.appendChild(target);
                        parent = target;
                    } else if (node instanceof TextNode text) {
                        parent.appendChild(document.createTextNode(text.getWholeText()));
                    } else if (node instanceof DataNode data) {
                        parent.appendChild(document.createTextNode(data.getWholeData()));
                    }
                }

                @Override
                public void tail(org.jsoup.nodes.Node node, int depth) {
                    if (node instanceof Element) parent = parent.getParentNode();
                }
            });
            root = document;
        } catch (ParserConfigurationException error) {
            throw new IllegalStateException("Android DOM builder is unavailable", error);
        }
    }

    List<Node> nodes(Node context, String expression) throws Exception {
        if (expression == null || expression.trim().isEmpty()) throw new IllegalArgumentException("Kazumi XPath is empty");
        if (expression.trim().equals("//")) return List.of(context);
        NodeList values = (NodeList) xpath.evaluate(relative(expression), context, XPathConstants.NODESET);
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < values.getLength(); i++) nodes.add(values.item(i));
        return nodes;
    }

    String text(Node context, String expression) throws Exception {
        List<Node> values = nodes(context, expression);
        return values.isEmpty() ? "" : values.get(0).getTextContent().trim();
    }

    String href(Node context, String expression) throws Exception {
        List<Node> values = nodes(context, expression);
        return values.isEmpty() ? "" : attribute(values.get(0), "href");
    }

    static String attribute(Node node, String name) {
        Node value = node.getAttributes() == null ? null : node.getAttributes().getNamedItem(name);
        return value == null ? "" : value.getNodeValue().trim();
    }

    // Each union branch is relative to its context, not to a detached copy or the document root.
    private static String relative(String expression) {
        StringBuilder result = new StringBuilder();
        int start = 0;
        char quote = 0;
        int brackets = 0;
        for (int i = 0; i <= expression.length(); i++) {
            char c = i == expression.length() ? '|' : expression.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '[' || c == '(') brackets++;
            else if (c == ']' || c == ')') brackets--;
            else if (c == '|' && brackets == 0) {
                String part = expression.substring(start, i).trim();
                if (result.length() > 0) result.append(" | ");
                if (part.startsWith("/self::")) part = part.substring(1);
                else if (part.startsWith("/")) part = "." + part;
                result.append(part);
                start = i + 1;
            }
        }
        return result.toString();
    }
}
