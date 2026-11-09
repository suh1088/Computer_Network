package webserver;

import http.HttpRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PostHandler {
    private final ClientHandler client;

    public PostHandler(ClientHandler client) {
        this.client = client;
    }

    public void handle(HttpRequest request) throws Exception {
        String contentType = request.getHeader("Content-Type");
        if (contentType == null
                || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("multipart/form-data")) {
            client.sendStatus("400 Bad Request");
            return;
        }

        String boundary = getParameter(contentType, "boundary");
        if (boundary == null || boundary.isEmpty()) {
            client.sendStatus("400 Bad Request");
            return;
        }

        byte[] body = request.getBody();
        // 문자 위치와 바이트 위치를 1:1로 맞춰 boundary 검색
        String multipart = new String(body, StandardCharsets.ISO_8859_1);
        String delimiter = "--" + boundary;
        int closingBoundary = findClosingBoundary(multipart, delimiter);

        if (closingBoundary == -1) {
            client.sendStatus("400 Bad Request");
            return;
        }

        int headerStart = delimiter.length() + 2;
        int headerEnd = multipart.indexOf("\r\n\r\n", headerStart);
        int dataEnd = closingBoundary - 2;

        if (headerEnd == -1 || headerEnd + 4 > dataEnd) {
            client.sendStatus("400 Bad Request");
            return;
        }

        // 나머지 파트 헤더는 무시하고 Content-Disposition만 확인
        String partHeaders = multipart.substring(headerStart, headerEnd);
        String disposition = getDisposition(partHeaders);
        String dispositionType = disposition.split(";", 2)[0].trim();
        String partName = getParameter(disposition, "name");
        String filename = getParameter(disposition, "filename");

        if (!dispositionType.equalsIgnoreCase("form-data")
                || !"file".equals(partName)
                || filename == null) {
            client.sendStatus("400 Bad Request");
            return;
        }

        filename = filename.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (filename.isEmpty() || filename.equals(".") || filename.equals("..")) {
            client.sendStatus("400 Bad Request");
            return;
        }

        // 헤더와 boundary 앞 CRLF를 제외한 원본 바이트 저장
        byte[] fileData = Arrays.copyOfRange(body, headerEnd + 4, dataEnd);
        Path file = Path.of("./docs/upload").resolve(filename);
        client.writeUpload(file, fileData);

        client.sendStatus("201 Created");
    }

    private int findClosingBoundary(String multipart, String delimiter) {
        String boundaryPattern = "(?:\\A|(?<=\\r\\n))" + Pattern.quote(delimiter)
                + "(?:--(?=\\r\\n|\\z)|(?=\\r\\n))";
        Matcher boundaries = Pattern.compile(boundaryPattern).matcher(multipart);

        int first = boundaries.find() ? boundaries.start() : -1;
        int second = boundaries.find() ? boundaries.start() : -1;

        // 시작/종료 boundary 두 개만 허용하고, 세 번째가 있으면 거부
        if (first != 0 || second == -1 || boundaries.find()
                || !multipart.startsWith(delimiter + "\r\n")
                || !multipart.startsWith(delimiter + "--", second)) {
            return -1;
        }

        return second;
    }

    private String getDisposition(String partHeaders) {
        String prefix = "Content-Disposition:";

        for (String line : partHeaders.split("\r\n")) {
            if (line.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return line.substring(prefix.length()).trim();
            }
        }

        return "";
    }

    private String getParameter(String header, String name) {
        String parameterPattern = ";\\s*" + name
                + "\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))\\s*(?=;|$)";
        Matcher parameter = Pattern.compile(parameterPattern, Pattern.CASE_INSENSITIVE)
                .matcher(header);

        if (!parameter.find()) {
            return null;
        }

        return parameter.group(1) != null ? parameter.group(1) : parameter.group(2);
    }
}
