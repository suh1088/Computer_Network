import webserver.ClientHandler;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MyWebServer {
    public static void main(String[] args) {
        int port = 8080;
        long slowDown = 0;
        boolean debug = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--port": port = Integer.parseInt(args[++i]); break;
                    case "--slowDown":
                        slowDown = Long.parseLong(args[++i]);
                        debug = true;
                        break;
                    default: throw new IllegalArgumentException("Unknown option");
                }
            }
            if (port < 1 || port > 65535 || slowDown < 0) {
                throw new IllegalArgumentException("Invalid option value");
            }
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            System.out.println("[INVALID_OPTIONS] " + "Usage: java MyWebServer --port 8080 [--slowDown milliseconds]");
            return;
        }

        ExecutorService threadPool = Executors.newFixedThreadPool(10);
        try (ServerSocket listeningSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port);
            while (true) {
                // 소켓 종료는 핸들러가 담당
                Socket clientSocket = listeningSocket.accept();
                threadPool.execute(new ClientHandler(clientSocket, slowDown, debug));
            }
        } catch (IOException e) {
            System.out.println("[SERVER_ERROR] " + e.toString());
        } finally {
            threadPool.shutdown();
        }
    }
}