package com.mingyue0094.networkcamera;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public final class Fmp4Server {
    private final Object lock = new Object();
    private final ArrayList<Client> clients = new ArrayList<>();
    private volatile boolean running;
    private volatile byte[] init;
    private ServerSocket server;

    public void start() throws Exception {
        if (running) return;
        server = new ServerSocket(8080);
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    Client c = new Client(s);
                    synchronized (lock) {
                        clients.add(c);
                    }
                    c.start();
                } catch (Exception e) {
                    if (!running) break;
                }
            }
        }, "fmp4-http");
        t.setDaemon(true);
        t.start();
    }

    public void setInitSegment(byte[] b) {
        if (b != null) init = b.clone();
    }

    public void publish(byte[] b) {
        if (b == null || b.length == 0) return;
        synchronized (lock) {
            for (Client c : new ArrayList<>(clients)) {
                if (!c.offer(b)) c.close();
            }
        }
    }

    private void remove(Client c) {
        synchronized (lock) {
            clients.remove(c);
        }
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        synchronized (lock) {
            for (Client c : new ArrayList<>(clients)) c.close();
            clients.clear();
        }
    }

    private final class Client implements Runnable {
        final Socket socket;
        final BlockingQueue<byte[]> q = new ArrayBlockingQueue<>(12);
        volatile boolean alive = true;
        Thread thread;

        Client(Socket s) {
            socket = s;
        }

        void start() {
            thread = new Thread(this, "fmp4-client");
            thread.setDaemon(true);
            thread.start();
        }

        boolean offer(byte[] b) {
            if (!alive) return false;
            byte[] x = b.clone();
            if (q.offer(x)) return true;
            q.poll();
            return q.offer(x);
        }

        public void run() {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), "US-ASCII"));
                String request = reader.readLine();
                if (request == null) {
                    close();
                    return;
                }

                String path = "/";
                String[] parts = request.split(" ");
                if (parts.length >= 2) path = parts[1];

                while (true) {
                    String line = reader.readLine();
                    if (line == null || line.length() == 0) break;
                }

                if (path.equals("/help") || path.equals("/help/")) {
                    sendHelp();
                    return;
                }

                if (!path.equals("/video.mp4")) {
                    send404();
                    return;
                }

                BufferedOutputStream out =
                        new BufferedOutputStream(socket.getOutputStream(), 65536);

                out.write((
                        "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: video/mp4\r\n"
                        + "Cache-Control: no-cache, no-store\r\n"
                        + "Connection: keep-alive\r\n"
                        + "Access-Control-Allow-Origin: *\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n"
                ).getBytes("US-ASCII"));
                out.flush();

                byte[] x = init;
                if (x != null) chunk(out, x);

                while (alive && running) {
                    chunk(out, q.take());
                }
            } catch (Exception ignored) {
            } finally {
                close();
                remove(this);
            }
        }

        private void sendHelp() throws Exception {
            byte[] body = helpPage().getBytes("UTF-8");
            BufferedOutputStream out =
                    new BufferedOutputStream(socket.getOutputStream(), 8192);
            out.write((
                    "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Access-Control-Allow-Origin: *\r\n\r\n"
            ).getBytes("US-ASCII"));
            out.write(body);
            out.flush();
        }

        private void send404() throws Exception {
            byte[] body = "404 Not Found\n".getBytes("UTF-8");
            BufferedOutputStream out =
                    new BufferedOutputStream(socket.getOutputStream(), 1024);
            out.write((
                    "HTTP/1.1 404 Not Found\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n\r\n"
            ).getBytes("US-ASCII"));
            out.write(body);
            out.flush();
        }

        void chunk(BufferedOutputStream out, byte[] b) throws Exception {
            out.write(Integer.toHexString(b.length).getBytes("US-ASCII"));
            out.write('\r');
            out.write('\n');
            out.write(b);
            out.write('\r');
            out.write('\n');
            out.flush();
        }

        void close() {
            if (!alive) return;
            alive = false;
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            q.clear();
        }
    }

    private static String helpPage() {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Android Network Camera API</title>"
                + "<style>body{font-family:Arial,sans-serif;line-height:1.6;"
                + "max-width:900px;margin:40px auto;padding:0 20px}"
                + "code,pre{background:#f4f4f4;padding:3px 6px;border-radius:4px}"
                + "pre{padding:14px;overflow:auto}h1{margin-bottom:4px}"
                + "table{border-collapse:collapse;width:100%}"
                + "td,th{border:1px solid #ccc;padding:8px;text-align:left}</style>"
                + "</head><body>"
                + "<h1>Android Network Camera</h1>"
                + "<p>Camera2 + MediaCodec 硬件 H.264 + fMP4 HTTP / RTSP 摄像头服务。</p>"

                + "<h2>API 接口</h2>"
                + "<table><tr><th>方法</th><th>路径</th><th>说明</th></tr>"
                + "<tr><td>GET</td><td><code>/help</code></td>"
                + "<td>显示本 API 使用说明</td></tr>"
                + "<tr><td>RTSP</td><td><code>rtsp://手机IP:8554/camera</code></td><td>H.264 RTP 实时视频流，适合 VLC / FFmpeg / YOLO</td></tr>
                <tr><td>GET</td><td><code>/video.mp4</code></td>"
                + "<td>持续输出 fragmented MP4 视频流</td></tr>"
                + "</table>"

                + "<h2>1. 查看帮助</h2>"
                + "<pre>GET http://手机IP:8080/help</pre>"
                + "<p>例如：</p>"
                + "<pre>http://192.168.1.123:8080/help</pre>"

                + "<h2>2. 获取 RTSP 视频</h2><pre>rtsp://手机IP:8554/camera</pre><p>可直接用于 VLC、FFmpeg、OpenCV/YOLO 等支持 RTSP 的客户端。</p>

                <h2>3. 获取 HTTP fMP4 视频</h2>"
                + "<pre>GET http://手机IP:8080/video.mp4</pre>"
                + "<p>例如：</p>"
                + "<pre>http://192.168.1.123:8080/video.mp4</pre>"
                + "<p>该接口不是普通一次性 MP4 文件，而是持续输出的 fMP4 视频流。</p>"

                + "<h2>4. 视频参数</h2>"
                + "<ul>"
                + "<li>视频编码：H.264 / AVC</li>"
                + "<li>输入：Camera2 Surface</li>"
                + "<li>编码器：Android MediaCodec Surface 输入</li>"
                + "<li>默认分辨率：1920 × 1080</li>"
                + "<li>默认帧率：30 FPS</li>"
                + "<li>默认码率：12 Mbps</li>"
                + "<li>关键帧间隔：1 秒</li>"
                + "</ul>"

                + "<h2>5. YOLO / PC 取流</h2>"
                + "<p>PC 端可使用 FFmpeg 读取：</p>"
                + "<pre>ffmpeg -i http://手机IP:8080/video.mp4 "
                + "-f rawvideo -pix_fmt bgr24 pipe:1</pre>"
                + "<p>然后将原始帧送入 OpenCV / YOLO。</p>"

                + "<h2>6. 数据流</h2>"
                + "<pre>Camera2 Surface"
                + " -> MediaCodec H.264"
                + " -> fMP4 Muxer"
                + " -> HTTP :8080"
                + " -> PC / YOLO</pre>"

                + "<h2>7. 网络要求</h2>"
                + "<ul>"
                + "<li>手机和 PC 必须能够互相访问。</li>"
                + "<li>手机端 HTTP 服务端口为 8080。</li>"
                + "<li>PC 访问手机的局域网 IP。</li>"
                + "</ul>"

                + "<h2>8. 当前接口示例</h2>"
                + "<pre>"
                + "浏览器：\n"
                + "http://手机IP:8080/help\n\n"
                + "视频：\n"
                + "http://手机IP:8080/video.mp4"
                + "</pre>"

                + "</body></html>";
    }
}
