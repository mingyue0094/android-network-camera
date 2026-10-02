package com.mingyue0094.networkcamera;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;
import android.util.Base64;

public class MjpegServer {
    public static final int PORT = 8080;
    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<byte[]>(null);
    private volatile boolean running;
    private ServerSocket serverSocket;
    private volatile ConfigHandler configHandler;
    private volatile String authPassword = "";

    public interface ConfigHandler {
        String getStatusJson();
        String applyConfig(String resolution, int fps, float zoom, String focus);
        boolean openSettings();
        String setCameraEnabled(boolean enabled);
    }

    public void setAuthPassword(String password) {
        authPassword = password == null ? "" : password;
    }

    public void setConfigHandler(ConfigHandler handler) {
        configHandler = handler;
    }

    public void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(PORT);
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try {
                        final Socket socket = serverSocket.accept();
                        Thread client = new Thread(new Client(socket), "mjpeg-client");
                        client.setDaemon(true);
                        client.start();
                    } catch (IOException e) {
                        if (running) e.printStackTrace();
                    }
                }
            }
        }, "mjpeg-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    public void clearFrame() { latestJpeg.set(null); }

    public void updateFrame(byte[] jpeg) {
        if (jpeg != null && jpeg.length > 0) latestJpeg.set(jpeg);
    }

    private final class Client implements Runnable {
        private final Socket socket;
        Client(Socket socket) { this.socket = socket; }

        @Override public void run() {
            try {
                socket.setSoTimeout(15000);
                InputStream in = socket.getInputStream();
                BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
                String request = readRequest(in);
                if (request == null) return;
                String[] lines = request.split("\\r?\\n");
                String first = lines.length > 0 ? lines[0] : "";
                String[] p = first.split(" ");
                if (!authorized(lines)) {
                    sendUnauthorized(out);
                    return;
                }
                String method = p.length > 0 ? p[0] : "GET";
                String path = p.length > 1 ? p[1] : "/";
                String queryString = "";
                int query = path.indexOf('?');
                if (query >= 0) { queryString = path.substring(query + 1); path = path.substring(0, query); }

                if ("OPTIONS".equalsIgnoreCase(method)) {
                    sendCorsPreflight(out);
                } else if ("/setings".equals(path) || "/settings".equals(path)) {
                    ConfigHandler h = configHandler;
                    boolean ok = h != null && h.openSettings();
                    sendJson(out, ok
                            ? "{\"ok\":true,\"action\":\"settings\"}"
                            : "{\"ok\":false,\"error\":\"settings unavailable\"}");
                } else if ("/stream".equals(path)) {
                    sendMjpeg(out);
                } else if ("/api/status".equals(path)) {
                    ConfigHandler h = configHandler;
                    sendJson(out, h == null ? "{\"error\":\"not ready\"}" : h.getStatusJson());
                } else if ("GET".equalsIgnoreCase(method) && "/api/camera".equals(path)) {
                    ConfigHandler h = configHandler;
                    boolean enabled = "1".equals(queryValue(queryString, "enabled"))
                            || "true".equalsIgnoreCase(queryValue(queryString, "enabled"));
                    sendJson(out, h == null
                            ? "{\"ok\":false,\"error\":\"not ready\"}"
                            : h.setCameraEnabled(enabled));
                } else if ("POST".equals(method) && "/api/config".equals(path)) {
                    String body = request.substring(request.indexOf("\r\n\r\n") + 4);
                    ConfigHandler h = configHandler;
                    sendJson(out, h == null ? "{\"ok\":false,\"error\":\"not ready\"}" : applyBody(h, body));
                } else {
                    sendHtml(out);
                }
            } catch (Exception ignored) {
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        private boolean authorized(String[] lines) {
            if (authPassword.length() == 0) return true;
            String expected = "admin:" + authPassword;
            String token = null;
            for (String line : lines) {
                if (line.regionMatches(true, 0, "Authorization:", 0, 14)) {
                    String v = line.substring(14).trim();
                    if (v.regionMatches(true, 0, "Basic ", 0, 6)) token = v.substring(6).trim();
                }
            }
            if (token == null) return false;
            try {
                String decoded = new String(Base64.decode(token, Base64.DEFAULT), "UTF-8");
                return expected.equals(decoded);
            } catch (Exception e) {
                return false;
            }
        }

        private void sendUnauthorized(OutputStream out) throws IOException {
            String h = "HTTP/1.0 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"Android Network Camera\"\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type, Authorization\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            out.write(h.getBytes("ISO-8859-1"));
            out.flush();
        }

        private String readRequest(InputStream in) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int state = 0;
            while (b.size() < 16384) {
                int v = in.read();
                if (v < 0) return null;
                b.write(v);
                if (state == 0 && v == '\r') state = 1;
                else if (state == 1 && v == '\n') state = 2;
                else if (state == 2 && v == '\r') state = 3;
                else if (state == 3 && v == '\n') break;
                else if (v != '\r') state = 0;
            }
            String head = b.toString("UTF-8");
            int length = 0;
            for (String line : head.split("\r\n")) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    try { length = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
                }
            }
            for (int i = 0; i < length && i < 8192; i++) {
                int v = in.read();
                if (v < 0) break;
                b.write(v);
            }
            return b.toString("UTF-8");
        }

        private String queryValue(String query, String key) {
            if (query == null) return "";
            String[] items = query.split("&");
            for (String item : items) {
                int p = item.indexOf('=');
                if (p > 0 && key.equals(item.substring(0, p))) return item.substring(p + 1);
            }
            return "";
        }

        private String applyBody(ConfigHandler h, String body) {
            try {
                String resolution = jsonValue(body, "resolution");
                int fps = Integer.parseInt(jsonValue(body, "fps"));
                float zoom = Float.parseFloat(jsonValue(body, "zoom"));
                String focus = jsonValue(body, "focus");
                return h.applyConfig(resolution, fps, zoom, focus);
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"invalid parameters\"}";
            }
        }

        private String jsonValue(String body, String key) {
            String needle = "\"" + key + "\"";
            int p = body.indexOf(needle);
            if (p < 0) return "";
            p = body.indexOf(':', p + needle.length());
            if (p < 0) return "";
            p++;
            while (p < body.length() && Character.isWhitespace(body.charAt(p))) p++;
            if (p < body.length() && body.charAt(p) == '"') {
                int e = body.indexOf('"', p + 1);
                return e < 0 ? "" : body.substring(p + 1, e);
            }
            int e = p;
            while (e < body.length() && body.charAt(e) != ',' && body.charAt(e) != '}') e++;
            return body.substring(p, e).trim();
        }

        private void sendJson(OutputStream out, String json) throws IOException {
            byte[] data = json.getBytes("UTF-8");
            String h = "HTTP/1.0 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type, Authorization\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n";
            out.write(h.getBytes("ISO-8859-1"));
            out.write(data);
            out.flush();
        }

        private void sendHtml(OutputStream out) throws IOException {
            byte[] data = HTML.getBytes("UTF-8");
            String h = "HTTP/1.0 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type, Authorization\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n";
            out.write(h.getBytes("ISO-8859-1"));
            out.write(data);
            out.flush();
        }

        private void sendCorsPreflight(OutputStream out) throws IOException {
            String h = "HTTP/1.0 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type, Authorization\r\nAccess-Control-Max-Age: 600\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            out.write(h.getBytes("ISO-8859-1"));
            out.flush();
        }

        private void sendMjpeg(OutputStream out) throws Exception {
            String h = "HTTP/1.0 200 OK\r\nCache-Control: no-cache, no-store, must-revalidate\r\nPragma: no-cache\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type, Authorization\r\nConnection: close\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n";
            out.write(h.getBytes("ISO-8859-1"));
            out.flush();
            while (running && !socket.isClosed()) {
                byte[] frame = latestJpeg.get();
                if (frame == null) { Thread.sleep(100); continue; }
                String part = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n";
                out.write(part.getBytes("ISO-8859-1"));
                out.write(frame);
                out.write("\r\n".getBytes("ISO-8859-1"));
                out.flush();
                Thread.sleep(20);
            }
        }

        private final String HTML =
            "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>Android 网络摄像头</title>" +
            "<style>body{font-family:Arial;background:#111;color:#eee;margin:0;padding:16px}" +
            "main{max-width:960px;margin:auto}img{width:100%;display:block;background:#000}" +
            "label{display:inline-block;margin:10px 12px 10px 0}select,button{font-size:16px;padding:8px}" +
            "#msg{margin:12px 0}</style></head><body><main>" +
            "<h2>Android 网络摄像头</h2><img src=\"/stream\">" +
            "<div id=\"msg\">正在读取状态...</div>" +
            "<label>分辨率 <select id=\"resolution\"><option>640x480</option><option>800x600</option><option>1280x720</option><option>1280x960</option><option>1920x1080</option></select></label>" +
            "<label>帧率 <select id=\"fps\"><option>5</option><option>10</option><option>15</option><option>20</option><option>24</option><option>25</option><option>30</option></select></label>" +
            "<label>缩放 <select id=\"zoom\"><option>1</option><option>1.5</option><option>2</option><option>3</option><option>4</option><option>6</option><option>8</option></select></label>" +
            "<label>对焦 <select id=\"focus\"><option value=\"continuous\">连续自动</option><option value=\"single\">单次自动</option><option value=\"lock\">锁定</option></select></label>" +
            "<button onclick=\"applyConfig()\">应用设置</button>" +
            "<button id=\"cameraBtn\" onclick=\"toggleCamera()\">关闭摄像头</button>" +
            "<p><a href=\"/setings\" style=\"display:inline-block;margin-top:12px;padding:10px 14px;background:#333;color:#fff;text-decoration:none;border-radius:6px\">打开手机设置（卸载本程序）</a></p>" +
            "</main><script>" +
            "async function loadStatus(){try{let r=await fetch('/api/status');let j=await r.json();" +
            "if(j.resolution)document.getElementById('resolution').value=j.resolution;" +
            "if(j.fps)document.getElementById('fps').value=j.fps;" +
            "if(j.zoom)document.getElementById('zoom').value=j.zoom;" +
            "if(j.focus)document.getElementById('focus').value=j.focus;" +
            "document.getElementById('msg').textContent='当前：'+j.resolution+' / '+j.fps+' FPS / '+j.zoom+'x / 电量 '+j.battery+'%';" +
            "document.getElementById('cameraBtn').textContent=j.camera===false?'打开摄像头':'关闭摄像头';" +
            "}catch(e){document.getElementById('msg').textContent='状态读取失败';}}" +
            "async function toggleCamera(){let enabled=document.getElementById('cameraBtn').textContent==='打开摄像头';try{let r=await fetch('/api/camera?enabled='+(enabled?'1':'0'));let j=await r.json();document.getElementById('cameraBtn').textContent=j.camera===false?'打开摄像头':'关闭摄像头';document.getElementById('msg').textContent=j.camera===false?'摄像头已关闭（省电）':'摄像头已打开';}catch(e){document.getElementById('msg').textContent='摄像头控制失败';}}" +            "async function applyConfig(){let b={resolution:document.getElementById('resolution').value," +
            "fps:+document.getElementById('fps').value,zoom:+document.getElementById('zoom').value,focus:document.getElementById('focus').value};" +
            "document.getElementById('msg').textContent='正在应用...';try{" +
            "let r=await fetch('/api/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)});" +
            "let j=await r.json();document.getElementById('msg').textContent=j.ok?'已应用：'+j.resolution+' / '+j.fps+' FPS / '+j.zoom+'x':'失败：'+j.error;" +
            "}catch(e){document.getElementById('msg').textContent='请求失败';}}loadStatus();" +
            "</script></body></html>";
    }
}
