package net.hypervisorx.backend.virtualbox;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny SOAP client for the VirtualBox web service ({@code vboxwebsrv}). Only does the few calls we need
 * for display, keyboard and mouse. Lifecycle stuff goes through VBoxManage.
 */
final class VboxSoap {
    private static final String NS = "http://www.virtualbox.org/";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI endpoint;
    private final ThreadLocal<DocumentBuilder> builders = ThreadLocal.withInitial(() -> {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    });

    VboxSoap(int port) {
        this.endpoint = URI.create("http://127.0.0.1:" + port + "/");
    }

    /** One call's result: element name to text values (repeated elements end up in a list). */
    static final class Response {
        final Map<String, List<String>> values = new LinkedHashMap<>();

        String get(String key) {
            List<String> v = values.get(key);
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        String ret() {
            return get("returnval");
        }
    }

    /**
     * Calls {@code method} with ordered name/value params. {@link List} values go out as repeated
     * elements (SOAP arrays).
     */
    Response call(String method, Object... params) throws IOException {
        StringBuilder body = new StringBuilder(256);
        body.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:vbox=\"").append(NS).append("\">")
                .append("<soap:Body><vbox:").append(method).append('>');
        for (int i = 0; i + 1 < params.length; i += 2) {
            String name = (String) params[i];
            Object value = params[i + 1];
            if (value instanceof List<?> list) {
                for (Object o : list) appendElement(body, name, o);
            } else {
                appendElement(body, name, value);
            }
        }
        body.append("</vbox:").append(method).append("></soap:Body></soap:Envelope>");
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"\"")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        Document doc;
        try {
            doc = builders.get().parse(new ByteArrayInputStream(resp.body()));
        } catch (Exception e) {
            throw new IOException("Malformed SOAP response from vboxwebsrv (HTTP " + resp.statusCode() + ")", e);
        }
        NodeList faults = doc.getElementsByTagNameNS("*", "Fault");
        if (faults.getLength() > 0) {
            Element f = (Element) faults.item(0);
            NodeList fs = f.getElementsByTagName("faultstring");
            String msg = fs.getLength() > 0 ? fs.item(0).getTextContent() : f.getTextContent();
            throw new VboxFault(method + ": " + msg.trim());
        }
        Response r = new Response();
        NodeList bodies = doc.getElementsByTagNameNS("*", "Body");
        if (bodies.getLength() == 0) return r;
        Node respNode = firstElement(bodies.item(0));
        if (respNode == null) return r;
        for (Node c = respNode.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() != Node.ELEMENT_NODE) continue;
            r.values.computeIfAbsent(c.getLocalName() != null ? c.getLocalName() : c.getNodeName(), k -> new ArrayList<>())
                    .add(c.getTextContent());
        }
        return r;
    }

    private static Node firstElement(Node parent) {
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE) return c;
        }
        return null;
    }

    private static void appendElement(StringBuilder sb, String name, Object value) {
        sb.append('<').append(name).append('>');
        String s = String.valueOf(value);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        sb.append("</").append(name).append('>');
    }

    static final class VboxFault extends IOException {
        VboxFault(String message) {
            super(message);
        }
    }
}
