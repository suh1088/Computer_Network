package http;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public class HttpRequest {
    private final String method;
    private final String path;
    private final String version;
    private final Map<String, String> headers;
    private final byte[] body;

    public HttpRequest(String method, String path, String version,
                       Map<String, String> headers, byte[] body) {
        this.method = method;
        this.path = path;
        this.version = version;
        Map<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(headers);
        this.headers = Collections.unmodifiableMap(copy);
        this.body = body.clone();
    }

    public String getMethod() { return method; }
    // 쿼리를 포함한 요청 대상 원문
    public String getPath() { return path; }
    public String getVersion() { return version; }
    public Map<String, String> getHeaders() { return headers; }
    public String getHeader(String name) { return headers.get(name); }
    public byte[] getBody() { return body.clone(); }
}
