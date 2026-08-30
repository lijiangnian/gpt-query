package com.vsme.vlinkconverter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class LocalProfileServer {
    private final String token = UUID.randomUUID().toString().replace("-", "");
    private volatile String content = "";
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread thread;

    synchronized void startIfNeeded() throws Exception {
        if (running && serverSocket != null && !serverSocket.isClosed()) return;
        serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        running = true;
        thread = new Thread(() -> {
            while (running) {
                try { handle(serverSocket.accept()); }
                catch (Exception ignored) { if (!running) break; }
            }
        }, "VLinkLocalProfile");
        thread.setDaemon(true);
        thread.start();
    }

    void setContent(String yaml) { content = yaml == null ? "" : yaml; }

    String getUrl() {
        if (serverSocket == null) return "";
        return "http://127.0.0.1:" + serverSocket.getLocalPort() + "/vlink.yaml?token=" + token;
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
             OutputStream os = s.getOutputStream()) {
            String first = reader.readLine();
            boolean ok = first != null && first.startsWith("GET ") && first.contains("token=" + token);
            byte[] body = (ok ? content : "Not found").getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 " + (ok ? "200 OK" : "404 Not Found") + "\r\n" +
                    "Content-Type: text/yaml; charset=utf-8\r\n" +
                    "Content-Length: " + body.length + "\r\n" +
                    "Cache-Control: no-store, no-cache, must-revalidate\r\n" +
                    "Connection: close\r\n\r\n";
            os.write(headers.getBytes(StandardCharsets.US_ASCII));
            os.write(body);
            os.flush();
        } catch (Exception ignored) { }
    }

    synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) { }
        serverSocket = null;
    }
}
