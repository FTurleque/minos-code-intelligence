package com.minos.explorer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A minimal HTTP/1.1 client over a socket, for tests that must send the headers a browser forbids a script to
 * set ({@code Host}, {@code Origin}): the request is written as given and the whole answer is read.
 */
final class RawHttp {

    /** A status, the answer headers by lower-case name (last value wins, all values in {@code raw}) and a body. */
    record Response(int status, Map<String, String> headers, String body, String raw) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private RawHttp() {
    }

    static Response get(int port, String path, Map<String, String> headers) throws IOException {
        return send(port, "GET", path, headers, null);
    }

    /** Sends one request to the loopback and reads the answer until the server closes the connection. */
    static Response send(int port, String method, String path, Map<String, String> headers, String body)
            throws IOException {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("Host", "127.0.0.1:" + port);
        all.putAll(headers);
        all.put("Connection", "close");
        byte[] content = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (body != null) all.put("Content-Length", Integer.toString(content.length));
        StringBuilder request = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        all.forEach((name, value) -> {
            if (value != null) request.append(name).append(": ").append(value).append("\r\n");
        });
        request.append("\r\n");
        try (Socket socket = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port)) {
            socket.setSoTimeout(20_000);
            OutputStream output = socket.getOutputStream();
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            output.write(content);
            output.flush();
            return parse(readAll(socket.getInputStream()));
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        input.transferTo(bytes);
        return bytes.toByteArray();
    }

    private static Response parse(byte[] bytes) {
        String raw = new String(bytes, StandardCharsets.UTF_8);
        int end = raw.indexOf("\r\n\r\n");
        if (end < 0) throw new IllegalStateException("no HTTP header block in: " + raw);
        String[] lines = raw.substring(0, end).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            int colon = lines[index].indexOf(':');
            headers.put(lines[index].substring(0, colon).toLowerCase(Locale.ROOT), lines[index].substring(colon + 1).trim());
        }
        String body = raw.substring(end + 4);
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) body = unchunk(body);
        return new Response(status, headers, body, raw);
    }

    private static String unchunk(String chunked) {
        StringBuilder body = new StringBuilder();
        byte[] bytes = chunked.getBytes(StandardCharsets.UTF_8);
        int position = 0;
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        while (position < bytes.length) {
            int lineEnd = position;
            while (bytes[lineEnd] != '\r') lineEnd++;
            int size = Integer.parseInt(new String(bytes, position, lineEnd - position, StandardCharsets.US_ASCII).trim(), 16);
            if (size == 0) break;
            decoded.write(bytes, lineEnd + 2, size);
            position = lineEnd + 2 + size + 2;
        }
        return body.append(decoded.toString(StandardCharsets.UTF_8)).toString();
    }
}
