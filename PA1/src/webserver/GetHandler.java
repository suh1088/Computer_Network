package webserver;

import http.HttpRequest;

import java.nio.file.Files;
import java.nio.file.Path;

public class GetHandler {
    private final ClientHandler client;

    public GetHandler(ClientHandler client) {
        this.client = client;
    }

    public void handle(HttpRequest request) throws Exception {
        // document root에서 요청 파일 읽기
        String filename = request.getPath().split("\\?", 2)[0].substring(1);
        if (filename.startsWith("filename=")) {
            filename = filename.substring(9);
        }

        Path file = Path.of("./docs/public").resolve(filename);
        if (!Files.isRegularFile(file)) {
            client.sendStatus("404 Not Found");
            return;
        }

        byte[] body = Files.readAllBytes(file);
        String contentType = Files.probeContentType(file);
        if (contentType == null) {
            contentType = "application/octet-stream";
        }

        // 응답 헤더와 파일 내용 전송
        client.sendResponse("200 OK", contentType, body);
    }
}
