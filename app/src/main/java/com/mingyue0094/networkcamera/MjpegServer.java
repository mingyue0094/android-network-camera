package com.mingyue0094.networkcamera;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

public class MjpegServer {
    public static final int PORT = 8080;

    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<byte[]>(null);
    private volatile boolean running;
    private ServerSocket serverSocket;

    public void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(PORT);
        running = true;

        Thread acceptThread = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try {
                        final Socket socket = serverSocket.accept();
                        Thread t = new Thread(new Client(socket));
                        t.setDaemon(true);
                        t.start();
                    } catch (IOException e) {
                        if (running) e.printStackTrace();
                    }
                }
            }
        }, "mjpeg-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
    }

    public void updateFrame(byte[] jpeg) {
        if (jpeg != null && jpeg.length > 0) {
            latestJpeg.set(jpeg);
        }
    }

    private final class Client implements Runnable {
        private final Socket socket;

        Client(Socket socket) {
            this.socket = socket;
        }

        @Override public void run() {
            try {
                socket.setSoTimeout(15000);
                OutputStream raw = socket.getOutputStream();
                BufferedOutputStream out = new BufferedOutputStream(raw);

                String headers =
                        "HTTP/1.0 200 OK\r\n" +
                        "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                        "Pragma: no-cache\r\n" +
                        "Connection: close\r\n" +
                        "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n";
                out.write(headers.getBytes("ISO-8859-1"));
                out.flush();

                while (running && !socket.isClosed()) {
                    byte[] frame = latestJpeg.get();
                    if (frame == null) {
                        Thread.sleep(100);
                        continue;
                    }

                    String part =
                            "--frame\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: " + frame.length + "\r\n\r\n";
                    out.write(part.getBytes("ISO-8859-1"));
                    out.write(frame);
                    out.write("\r\n".getBytes("ISO-8859-1"));
                    out.flush();
                    Thread.sleep(80);
                }
            } catch (Exception ignored) {
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}
