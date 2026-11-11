package http;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

public class HttpParser {
    public static HttpRequest readRequest(InputStream input) throws Exception {
        String line = readLine(input);
        if (line == null) {
            return null;
        }

        String[] parts = line.split(" ", -1);
        if (parts.length != 3
                || parts[0].isEmpty()
                || !parts[1].startsWith("/")
                || !parts[2].startsWith("HTTP/")) {
            throw new Exception("Invalid request line");
        }

        Map<String, String> headers = readHeaders(input);
        byte[] body = readBody(input, headers);

        return new HttpRequest(parts[0], parts[1], parts[2], headers, body);
    }

    private static Map<String, String> readHeaders(InputStream input) throws Exception {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        // 빈 줄까지 헤더 읽기
        while (true) {
            String line = readLine(input);
            if (line == null) {
                throw new Exception("Incomplete headers");
            }

            if (line.isEmpty()) {
                break;
            }

            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new Exception("Invalid header");
            }

            String name = line.substring(0, colon);
            if (name.equalsIgnoreCase("Content-Length") && headers.containsKey(name)) {
                throw new Exception("Duplicate Content-Length");
            }

            headers.put(name, line.substring(colon + 1).trim());
        }

        return headers;
    }

    private static byte[] readBody(InputStream input, Map<String, String> headers) throws Exception {
        int length = 0;
        String value = headers.get("Content-Length");

        if (headers.containsKey("Transfer-Encoding")) {
            throw new Exception("Unsupported body framing");
        }

        if (value != null) {
            try {
                if (!value.matches("[0-9]+")) {
                    throw new Exception();
                }

                length = Integer.parseInt(value);
            } catch (Exception e) {
                throw new Exception("Invalid Content-Length", e);
            }
        }

        // 본문을 지정된 바이트 수만큼 읽기
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];

        while (length > 0) {
            int count = input.read(buffer, 0, Math.min(buffer.length, length));
            if (count == -1) {
                throw new Exception("Incomplete body");
            }

            body.write(buffer, 0, count);
            length -= count;
        }

        return body.toByteArray();
    }

    private static String readLine(InputStream input) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();

        while (true) {
            int next = input.read();
            if (next == -1) {
                if (line.size() == 0) {
                    return null;
                }

                throw new Exception("Incomplete line");
            }

            if (next == '\r') {
                if (input.read() != '\n') {
                    throw new Exception("Expected CRLF");
                }

                return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
            }

            if (next == '\n') {
                throw new Exception("Expected CRLF");
            }

            line.write(next);
        }
    }
}
