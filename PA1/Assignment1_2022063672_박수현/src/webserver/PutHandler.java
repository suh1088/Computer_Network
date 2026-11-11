package webserver;

import http.HttpRequest;

import java.nio.file.Path;

public class PutHandler {
    private final ClientHandler client;

    public PutHandler(ClientHandler client) {
        this.client = client;
    }

    public void handle(HttpRequest request) throws Exception {
        // URI에서 파일명을 얻어 업로드 경로 생성
        String path = request.getPath();
        String filename = path.substring(path.lastIndexOf('/') + 1);
        Path file = Path.of("./docs/upload").resolve(filename);

        boolean existed = client.writeUpload(file, request.getBody());
        client.sendStatus(existed ? "200 OK" : "201 Created");
    }
}
