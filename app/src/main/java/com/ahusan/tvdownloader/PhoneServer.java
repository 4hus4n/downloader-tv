package com.ahusan.tvdownloader;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Tiny web page on the TV so a link can be sent from a phone on the same Wi-Fi. */
public class PhoneServer {

    public interface Callback { void onLink(String url); }

    private final Callback callback;
    private ServerSocket server;
    private Thread thread;
    private int port = -1;

    public PhoneServer(Callback cb) { this.callback = cb; }

    public int getPort() { return port; }

    public synchronized void start() {
        if (server != null) return;
        for (int p = 8090; p < 8100; p++) {
            try { server = new ServerSocket(p); port = p; break; } catch (Exception ignored) { }
        }
        if (server == null) return;
        final ServerSocket s = server;
        thread = new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    Socket sock = s.accept();
                    new Thread(() -> handle(sock)).start();
                } catch (Exception e) { break; }
            }
        }, "PhoneServer");
        thread.start();
    }

    public synchronized void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
        port = -1;
    }

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(10000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) { sock.close(); return; }
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:"))
                    contentLength = Integer.parseInt(line.substring(15).trim());
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";

            String message = null;
            if ("POST".equals(method) && path.startsWith("/add")) {
                byte[] body = new byte[Math.min(contentLength, 64 * 1024)];
                int read = 0;
                while (read < body.length) {
                    int n = in.read(body, read, body.length - read);
                    if (n < 0) break;
                    read += n;
                }
                String form = new String(body, 0, read, StandardCharsets.UTF_8);
                String url = null;
                for (String kv : form.split("&")) {
                    if (kv.startsWith("url=")) url = URLDecoder.decode(kv.substring(4), "UTF-8").trim();
                }
                if (url != null && !url.isEmpty()) {
                    callback.onLink(url);
                    message = "✅ Sent to TV! Download started.";
                } else {
                    message = "⚠️ Please paste a link.";
                }
            }
            send(sock.getOutputStream(), page(message));
            sock.close();
        } catch (Exception e) {
            try { sock.close(); } catch (Exception ignored) { }
        }
    }

    private static String readLine(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static void send(OutputStream os, String html) throws java.io.IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        os.write(head.getBytes(StandardCharsets.UTF_8));
        os.write(body);
        os.flush();
    }

    private static String page(String message) {
        String msg = message == null ? "" :
                "<div class='msg'>" + message.replace("<", "&lt;") + "</div>";
        return "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>TV Downloader</title><style>"
                + "body{margin:0;font-family:system-ui,sans-serif;background:#0F1522;color:#F1F5F9;padding:24px}"
                + "h1{font-size:22px;margin:0 0 6px}p{color:#94A3B8;margin:0 0 18px}"
                + "textarea{width:100%;box-sizing:border-box;height:120px;border-radius:12px;border:1px solid #334155;"
                + "background:#1A2236;color:#F1F5F9;font-size:16px;padding:12px}"
                + "button{margin-top:14px;width:100%;padding:16px;border:0;border-radius:12px;background:#22C55E;"
                + "color:#fff;font-size:18px;font-weight:600}"
                + ".msg{background:#1A2236;border-radius:12px;padding:14px;margin-bottom:16px}"
                + "</style></head><body><h1>⬇ TV Downloader</h1>"
                + "<p>Paste a link (APK or any file). It will download on the TV.</p>" + msg
                + "<form method='post' action='/add'><textarea name='url' placeholder='https://…' required></textarea>"
                + "<button type='submit'>Send to TV</button></form></body></html>";
    }

    /** Best guess at the TV's LAN IPv4 address (Wi-Fi or Ethernet). */
    public static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
