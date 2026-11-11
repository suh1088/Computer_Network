package webserver;

import http.HttpParser;
import http.HttpRequest;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ClientHandler implements Runnable {
    // 모든 작업 스레드가 공유하는 파일 쓰기 잠금
    private static final Object FILE_WRITE_LOCK = new Object();

    private final Socket clientSocket;
    private final long slowDown;
    private final boolean debug;
    private String requestInfo = "Invalid request";

    // 처리할 클라이언트 소켓 저장
    public ClientHandler(Socket clientSocket) {
        this(clientSocket, 0, false);
    }

    public ClientHandler(Socket clientSocket, long slowDown, boolean debug) {
        this.clientSocket = clientSocket;
        this.slowDown = slowDown;
        this.debug = debug;
    }

    @Override
    public void run() {
        try (Socket socket = clientSocket) {
            // HTTP 요청
            HttpRequest request;

            try {
                request = HttpParser.readRequest(socket.getInputStream());
            } catch (Exception e) {
                sendStatus("400 Bad Request");
                return;
            }

            if (request == null) {
                return;
            }

            requestInfo = request.getMethod() + " " + request.getPath();

            try {
                switch (request.getMethod()) {
                    case "GET":
                        new GetHandler(this).handle(request);
                        break;

                    case "PUT":
                        new PutHandler(this).handle(request);
                        break;

                    case "POST":
                        new PostHandler(this).handle(request);
                        break;

                    default:
                        sendStatus("405 Method Not Allowed");
                        break;
                }
            } catch (Exception e) {
                if (e instanceof java.nio.file.NoSuchFileException) {
                    sendStatus("404 Not Found");
                } else if (e instanceof IllegalArgumentException) {
                    sendStatus("400 Bad Request");
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            System.out.println(requestInfo + " -> 처리 또는 응답 전송 실패: " + e.getMessage());
        }
    }

    // PUT과 POST가 같은 잠금을 사용해 존재 확인과 저장을 함께 처리
    boolean writeUpload(Path file, byte[] data) throws Exception {
        synchronized (FILE_WRITE_LOCK) {
            Files.createDirectories(Path.of("./docs/upload"));
            boolean existed = Files.exists(file);

            String worker = Thread.currentThread().getName();
            if (debug) {
                System.out.println("ENTER " + worker + " " + file);
            }

            try {
                if (slowDown > 0) {
                    try {
                        Thread.sleep(slowDown);
                    } catch (Exception e) {
                        Thread.currentThread().interrupt();
                        throw new Exception("Upload interrupted", e);
                    }
                }

                Files.write(file, data);
            } finally {
                if (debug) {
                    System.out.println("EXIT " + worker + " " + file);
                }
            }

            return existed;
        }
    }

    void sendStatus(String status) throws Exception {
        sendResponse(status, null, new byte[0]);
    }

    void sendResponse(String status, String contentType, byte[] body) throws Exception {
        String header = "HTTP/1.1 " + status + "\r\n"
                + (contentType != null ? "Content-Type: " + contentType + "\r\n" : "")
                + (status.startsWith("405") ? "Allow: GET, PUT, POST\r\n" : "")
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";

        OutputStream output = clientSocket.getOutputStream();
        output.write(header.getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();

        System.out.println(requestInfo + " -> " + status);
    }
}
