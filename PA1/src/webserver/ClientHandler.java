package webserver;

import http.HttpParser;
import http.HttpRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ClientHandler implements Runnable{
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
    public void run(){
        try (Socket socket = clientSocket) {
            // HTTP 요청
            HttpRequest request;
            try {
                request = HttpParser.readRequest(socket.getInputStream());
            } catch (IOException e) {
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
                        handleGet(request);
                        break;
                    case "PUT":
                        handlePut(request);
                        break;
                    case "POST":
                        handlePost(request);
                        break;
                    default:
                        sendStatus("405 Method Not Allowed");
                        break;
                }
            } catch (java.nio.file.NoSuchFileException e) {
                sendStatus("404 Not Found");
            } catch (IllegalArgumentException e) {
                sendStatus("400 Bad Request");
            }
        } catch (IOException e) {
            System.out.println(requestInfo + " -> 처리 또는 응답 전송 실패: " + e.getMessage());
        }
    }

    private void handleGet(HttpRequest request) throws IOException {
        // document root에서 요청 파일 읽기
        String filename = request.getPath().split("\\?", 2)[0].substring(1);
        if (filename.startsWith("filename=")) filename = filename.substring(9);
        Path file = Path.of("./docs/public").resolve(filename);
        if (!Files.isRegularFile(file)) {
            sendStatus("404 Not Found");
            return;
        }
        byte[] body = Files.readAllBytes(file);

        String contentType = Files.probeContentType(file);
        if (contentType == null) {
            contentType = "application/octet-stream";
        }

        // 응답 헤더와 파일 내용 전송
        String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        OutputStream output = clientSocket.getOutputStream();
        output.write(header.getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
        System.out.println(requestInfo + " -> 200 OK");
    }

    private void handlePut(HttpRequest request) throws IOException {
        // URI에서 파일명을 얻어 업로드 경로 생성
        String filename = request.getPath().substring(request.getPath().lastIndexOf('/') + 1);
        Path uploadRoot = Path.of("./docs/upload");
        Path file = uploadRoot.resolve(filename);
        byte[] body = request.getBody();

        boolean existed;
        synchronized (FILE_WRITE_LOCK) {
            Files.createDirectories(uploadRoot);
            // 존재 확인과 저장을 함께 동기화
            existed = Files.exists(file);
            writeUpload(file, body);
        }

        // 응답 전송
        String status = existed ? "200 OK" : "201 Created";
        String header = "HTTP/1.1 " + status + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        OutputStream output = clientSocket.getOutputStream();
        output.write(header.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
        System.out.println(requestInfo + " -> " + status);
    }

    private void handlePost(HttpRequest request) throws IOException {
        String status = "400 Bad Request";

        // 형식이 잘못되면 이 블록을 나가 400 응답
        parseMultipart: {
            String contentType = request.getHeader("Content-Type");
            if (contentType == null
                    || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("multipart/form-data")) {
                break parseMultipart;
            }

            // boundary, name, filename의 매개변수 형식
            java.util.regex.Pattern parameterPattern = java.util.regex.Pattern.compile(
                    ";\\s*([^=;\\s]+)\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))\\s*(?=;|$)");
            java.util.regex.Matcher parameters = parameterPattern.matcher(contentType);
            String boundary = null;
            while (parameters.find()) {
                if (parameters.group(1).equalsIgnoreCase("boundary")) {
                    boundary = parameters.group(2) != null ? parameters.group(2) : parameters.group(3);
                }
            }
            if (boundary == null || boundary.isEmpty()) {
                break parseMultipart;
            }

            byte[] body = request.getBody();
            // 바이트와 문자 위치를 1:1로 맞춰 구분자 검색
            String multipart = new String(body, StandardCharsets.ISO_8859_1);
            String delimiter = "--" + boundary;
            if (!multipart.startsWith(delimiter + "\r\n")) {
                break parseMultipart;
            }

            String filename = null;
            byte[] fileData = null;
            int position = delimiter.length() + 2;
            while (true) {
                // 파트 헤더와 데이터의 경계 찾기
                int headerEnd = multipart.indexOf("\r\n\r\n", position);
                if (headerEnd == -1) {
                    break parseMultipart;
                }
                int dataStart = headerEnd + 4;
                String marker = "\r\n" + delimiter;
                int dataEnd = multipart.indexOf(marker, dataStart);
                while (dataEnd != -1) {
                    int next = dataEnd + marker.length();
                    if (multipart.startsWith("\r\n", next)
                            || (multipart.startsWith("--", next)
                            && (next + 2 == multipart.length()
                            || multipart.startsWith("\r\n", next + 2)))) {
                        break;
                    }
                    dataEnd = multipart.indexOf(marker, dataEnd + 1);
                }
                if (dataEnd == -1) {
                    break parseMultipart;
                }

                String disposition = null;
                for (String line : multipart.substring(position, headerEnd).split("\r\n")) {
                    int colon = line.indexOf(':');
                    if (colon <= 0) {
                        break parseMultipart;
                    }
                    if (line.substring(0, colon).equalsIgnoreCase("Content-Disposition")) {
                        disposition = line.substring(colon + 1).trim();
                    }
                }
                if (disposition == null
                        || !disposition.split(";", 2)[0].trim().equalsIgnoreCase("form-data")) {
                    break parseMultipart;
                }

                String partName = null;
                String partFilename = null;
                parameters = parameterPattern.matcher(disposition);
                while (parameters.find()) {
                    String value = parameters.group(2) != null ? parameters.group(2) : parameters.group(3);
                    if (parameters.group(1).equalsIgnoreCase("name")) {
                        partName = value;
                    } else if (parameters.group(1).equalsIgnoreCase("filename")) {
                        partFilename = value;
                    }
                }

                // name="file"인 첫 파일 파트의 원본 바이트 추출
                if (fileData == null && "file".equals(partName)) {
                    if (partFilename == null) {
                        break parseMultipart;
                    }
                    filename = partFilename.replace('\\', '/');
                    filename = filename.substring(filename.lastIndexOf('/') + 1);
                    if (filename.isEmpty() || filename.equals(".") || filename.equals("..")) {
                        break parseMultipart;
                    }
                    fileData = java.util.Arrays.copyOfRange(body, dataStart, dataEnd);
                }

                int afterBoundary = dataEnd + marker.length();
                if (multipart.startsWith("--", afterBoundary)) {
                    break;
                }
                position = afterBoundary + 2;
            }

            if (fileData == null) {
                break parseMultipart;
            }

            // PUT과 같은 잠금을 사용해 파일 저장
            Path uploadRoot = Path.of("./docs/upload");
            Path file = uploadRoot.resolve(filename);
            synchronized (FILE_WRITE_LOCK) {
                Files.createDirectories(uploadRoot);
                writeUpload(file, fileData);
            }
            status = "201 Created";
        }

        String header = "HTTP/1.1 " + status + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        OutputStream output = clientSocket.getOutputStream();
        output.write(header.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
        System.out.println(requestInfo + " -> " + status);
    }

    private void writeUpload(Path file, byte[] data) throws IOException {
        String worker = Thread.currentThread().getName();
        if (debug) System.out.println("ENTER " + worker + " " + file);
        try {
            if (slowDown > 0) {
                Thread.sleep(slowDown);
            }
            Files.write(file, data);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Upload interrupted", e);
        } finally {
            if (debug) System.out.println("EXIT " + worker + " " + file);
        }
    }

    private void sendStatus(String status) throws IOException {
        String header = "HTTP/1.1 " + status + "\r\n"
                + (status.startsWith("405") ? "Allow: GET, PUT, POST\r\n" : "")
                + "Content-Length: 0\r\nConnection: close\r\n\r\n";
        clientSocket.getOutputStream().write(header.getBytes(StandardCharsets.ISO_8859_1));
        clientSocket.getOutputStream().flush();
        System.out.println(requestInfo + " -> " + status);
    }
}
