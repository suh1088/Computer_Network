package http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

public class HttpParser {
    public static HttpRequest readRequest(InputStream input) throws IOException {
        String line = readLine(input);
        if (line == null) return null;
        String[] parts = line.split(" ", -1);
        if (parts.length != 3 || parts[0].isEmpty() || !parts[1].startsWith("/")
                || !parts[2].startsWith("HTTP/")) {
            throw new IOException("Invalid request line");
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        // 빈 줄까지 헤더 읽기
        while (true) {
            line = readLine(input);
            if (line == null) throw new IOException("Incomplete headers");
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IOException("Invalid header");
            String name = line.substring(0, colon);
            if (name.equalsIgnoreCase("Content-Length") && headers.containsKey(name)) {
                throw new IOException("Duplicate Content-Length");
            }
            headers.put(name, line.substring(colon + 1).trim());
        }
        int length = 0;
        String value = headers.get("Content-Length");
        if (headers.containsKey("Transfer-Encoding")) {
            throw new IOException("Unsupported body framing");
        }
        if (value != null) {
            try {
                if (!value.matches("[0-9]+")) throw new NumberFormatException();
                length = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Content-Length", e);
            }
        }
        // 본문을 지정된 바이트 수만큼 읽기
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (length > 0) {
            int count = input.read(buffer, 0, Math.min(buffer.length, length));
            if (count == -1) throw new IOException("Incomplete body");
            body.write(buffer, 0, count);
            length -= count;
        }
        return new HttpRequest(parts[0], parts[1], parts[2], headers, body.toByteArray());
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int next = input.read();
            if (next == -1) {
                if (line.size() == 0) return null;
                throw new IOException("Incomplete line");
            }
            if (next == '\r') {
                if (input.read() != '\n') throw new IOException("Expected CRLF");
                return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
            }
            if (next == '\n') throw new IOException("Expected CRLF");
            line.write(next);
        }
    }
}
