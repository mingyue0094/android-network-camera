package com.mingyue0094.networkcamera;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RtspServer {
    public static final int PORT = 8554;
    private static final int RTP_MTU = 1400;
    private final Object lock = new Object();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private byte[] sps;
    private byte[] pps;
    private int width;
    private int height;
    private int fps = 15;
    private long latestPtsUs;
    private long rtpTimestampBase = -1;
    private int sequence = 0;
    private final List<Client> clients = new ArrayList<Client>();

    public void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(PORT);
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try {
                        Socket socket = serverSocket.accept();
                        Client client = new Client(socket);
                        synchronized (lock) { clients.add(client); }
                        Thread c = new Thread(client, "rtsp-client");
                        c.setDaemon(true);
                        c.start();
                    } catch (IOException e) {
                        if (running) e.printStackTrace();
                    }
                }
            }
        }, "rtsp-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        synchronized (lock) {
            for (Client c : clients) c.close();
            clients.clear();
        }
    }

    public void setVideoFormat(int w, int h, int f) {
        synchronized (lock) {
            width = w;
            height = h;
            fps = Math.max(1, f);
        }
    }

    public void onConfig(byte[] a, byte[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return;
        synchronized (lock) {
            sps = a.clone();
            pps = b.clone();
        }
    }

    public void onFrame(byte[] avcc, long ptsUs, boolean key) {
        if (!running || avcc == null || avcc.length == 0) return;
        List<byte[]> nals = splitAvcc(avcc);
        if (nals.isEmpty()) return;
        synchronized (lock) {
            latestPtsUs = ptsUs;
            if (rtpTimestampBase < 0) rtpTimestampBase = ptsUs * 90L / 1000L;
            for (Client c : new ArrayList<Client>(clients)) {
                if (c.playing) {
                    try {
                        c.sendAccessUnit(nals, ptsUs, key);
                    } catch (Exception e) {
                        c.close();
                    }
                }
            }
        }
    }

    private List<byte[]> splitAvcc(byte[] data) {
        ArrayList<byte[]> out = new ArrayList<byte[]>();
        int p = 0;
        while (p + 4 <= data.length) {
            int n = ((data[p] & 255) << 24) | ((data[p + 1] & 255) << 16)
                    | ((data[p + 2] & 255) << 8) | (data[p + 3] & 255);
            p += 4;
            if (n <= 0 || p + n > data.length) return new ArrayList<byte[]>();
            byte[] nal = new byte[n];
            System.arraycopy(data, p, nal, 0, n);
            out.add(nal);
            p += n;
        }
        return p == data.length ? out : new ArrayList<byte[]>();
    }

    private String sdp() {
        byte[] a;
        byte[] b;
        int w;
        int h;
        int f;
        synchronized (lock) {
            a = sps == null ? null : sps.clone();
            b = pps == null ? null : pps.clone();
            w = width;
            h = height;
            f = fps;
        }
        if (a == null || b == null) return null;
        String profile = a.length >= 4
                ? String.format(Locale.US, "%02X%02X%02X", a[1] & 255, a[2] & 255, a[3] & 255)
                : "42C01E";
        String sps64 = Base64.encodeToString(a, Base64.NO_WRAP);
        String pps64 = Base64.encodeToString(b, Base64.NO_WRAP);
        return "v=0\r\n"
                + "o=- 0 0 IN IP4 0.0.0.0\r\n"
                + "s=Android Network Camera\r\n"
                + "c=IN IP4 0.0.0.0\r\n"
                + "t=0 0\r\n"
                + "m=video 0 RTP/AVP 96\r\n"
                + "a=rtpmap:96 H264/90000\r\n"
                + "a=fmtp:96 packetization-mode=1;profile-level-id=" + profile
                + ";sprop-parameter-sets=" + sps64 + "," + pps64 + "\r\n"
                + "a=control:trackID=0\r\n"
                + "a=framerate:" + f + "\r\n"
                + "a=x-dimensions:" + w + "," + h + "\r\n";
    }

    private final class Client implements Runnable {
        private final Socket socket;
        private InetAddress address;
        private DatagramSocket rtpSocket;
        private int clientRtpPort;
        private int clientRtcpPort;
        private boolean setup;
        private volatile boolean playing;
        private int localRtpPort;
        private int rtpSeq;
        private int ssrc = (int) (System.nanoTime() ^ System.identityHashCode(this));

        Client(Socket s) {
            socket = s;
            address = s.getInetAddress();
        }

        @Override public void run() {
            try {
                socket.setSoTimeout(0);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                OutputStream out = socket.getOutputStream();
                while (running && !socket.isClosed()) {
                    String request = readRequest(in);
                    if (request == null) break;
                    handle(request, out);
                }
            } catch (Exception ignored) {
            } finally {
                close();
                synchronized (lock) { clients.remove(this); }
            }
        }

        private String readRequest(BufferedReader in) throws IOException {
            String line = in.readLine();
            if (line == null) return null;
            StringBuilder b = new StringBuilder(line).append("\r\n");
            while ((line = in.readLine()) != null) {
                b.append(line).append("\r\n");
                if (line.length() == 0) break;
            }
            return b.toString();
        }

        private void handle(String req, OutputStream out) throws Exception {
            String[] lines = req.split("\\r\\n");
            String[] first = lines[0].split(" ");
            if (first.length < 2) return;
            String method = first[0];
            String uri = first[1];
            int cseq = headerInt(lines, "CSeq", 1);

            if ("OPTIONS".equals(method)) {
                response(out, cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n", null);
            } else if ("DESCRIBE".equals(method)) {
                String body = sdp();
                if (body == null) {
                    response(out, cseq, null, "RTSP/1.0 503 Service Unavailable\r\n");
                    return;
                }
                byte[] bytes = body.getBytes("UTF-8");
                String h = "RTSP/1.0 200 OK\r\nCSeq: " + cseq
                        + "\r\nContent-Base: rtsp://" + socket.getLocalAddress().getHostAddress()
                        + ":" + PORT + "/camera/\r\nContent-Type: application/sdp\r\nContent-Length: "
                        + bytes.length + "\r\n\r\n";
                out.write(h.getBytes("UTF-8"));
                out.write(bytes);
                out.flush();
            } else if ("SETUP".equals(method)) {
                String transport = header(lines, "Transport");
                String cp = value(transport, "client_port");
                if (cp == null) {
                    response(out, cseq, null, "RTSP/1.0 461 Unsupported Transport\r\n");
                    return;
                }
                String[] ports = cp.split("-");
                clientRtpPort = Integer.parseInt(ports[0]);
                clientRtcpPort = ports.length > 1 ? Integer.parseInt(ports[1]) : clientRtpPort + 1;
                rtpSocket = new DatagramSocket();
                localRtpPort = rtpSocket.getLocalPort();
                setup = true;
                String transportReply = "Transport: RTP/AVP;unicast;client_port="
                        + clientRtpPort + "-" + clientRtcpPort
                        + ";server_port=" + localRtpPort + "-" + (localRtpPort + 1) + "\r\n";
                response(out, cseq, transportReply, null);
            } else if ("PLAY".equals(method)) {
                if (!setup) {
                    response(out, cseq, null, "RTSP/1.0 455 Method Not Valid In This State\r\n");
                    return;
                }
                playing = true;
                rtpSeq = 0;
                response(out, cseq, "Session: " + sessionId() + "\r\nRTP-Info: url=" + uri
                        + "/trackID=0;seq=" + rtpSeq + ";rtptime=0\r\n", null);
            } else if ("TEARDOWN".equals(method)) {
                response(out, cseq, "Session: " + sessionId() + "\r\n", null);
                close();
            } else {
                response(out, cseq, null, "RTSP/1.0 405 Method Not Allowed\r\n");
            }
        }

        private void response(OutputStream out, int cseq, String extra, String status) throws IOException {
            if (status != null) {
                out.write((status + "CSeq: " + cseq + "\r\n\r\n").getBytes("UTF-8"));
            } else {
                String s = "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n"
                        + (extra == null ? "" : extra) + "\r\n";
                out.write(s.getBytes("UTF-8"));
            }
            out.flush();
        }

        private void sendAccessUnit(List<byte[]> nals, long ptsUs, boolean key) throws IOException {
            if (rtpSocket == null || !setup || !playing) return;
            long ts = ptsUs * 90L / 1000L;
            if (key) sendParameterSets(ts);
            for (int i = 0; i < nals.size(); i++) {
                byte[] nal = nals.get(i);
                sendNal(nal, ts, i == nals.size() - 1);
            }
        }

        private void sendParameterSets(long ts) throws IOException {
            byte[] a;
            byte[] b;
            synchronized (lock) {
                a = sps == null ? null : sps.clone();
                b = pps == null ? null : pps.clone();
            }
            if (a == null || b == null) return;
            int total = 1 + 2 + a.length + 2 + b.length;
            if (total <= RTP_MTU - 12) {
                byte[] p = new byte[total];
                int x = 0;
                p[x++] = (byte) (24 | (a[0] & 0xE0));
                p[x++] = (byte) (a.length >> 8);
                p[x++] = (byte) a.length;
                System.arraycopy(a, 0, p, x, a.length); x += a.length;
                p[x++] = (byte) (b.length >> 8);
                p[x++] = (byte) b.length;
                System.arraycopy(b, 0, p, x, b.length);
                sendRtp(p, ts, false);
            } else {
                sendNal(a, ts, false);
                sendNal(b, ts, true);
            }
        }

        private void sendNal(byte[] nal, long ts, boolean marker) throws IOException {
            if (nal.length <= RTP_MTU - 12) {
                sendRtp(nal, ts, marker);
                return;
            }
            int type = nal[0] & 31;
            int nri = nal[0] & 0xE0;
            int max = RTP_MTU - 14;
            int offset = 1;
            while (offset < nal.length) {
                int n = Math.min(max, nal.length - offset);
                byte[] p = new byte[n + 2];
                p[0] = (byte) (nri | 28);
                p[1] = (byte) type;
                if (offset == 1) p[1] |= (byte) 0x80;
                if (offset + n >= nal.length) p[1] |= 0x40;
                System.arraycopy(nal, offset, p, 2, n);
                sendRtp(p, ts, offset + n >= nal.length && marker);
                offset += n;
            }
        }

        private void sendRtp(byte[] payload, long ts, boolean marker) throws IOException {
            byte[] packet = new byte[12 + payload.length];
            packet[0] = (byte) 0x80;
            packet[1] = (byte) (96 | (marker ? 0x80 : 0));
            packet[2] = (byte) (rtpSeq >> 8);
            packet[3] = (byte) rtpSeq++;
            packet[4] = (byte) (ts >> 24);
            packet[5] = (byte) (ts >> 16);
            packet[6] = (byte) (ts >> 8);
            packet[7] = (byte) ts;
            packet[8] = (byte) (ssrc >> 24);
            packet[9] = (byte) (ssrc >> 16);
            packet[10] = (byte) (ssrc >> 8);
            packet[11] = (byte) ssrc;
            DatagramPacket dp = new DatagramPacket(packet, packet.length, address, clientRtpPort);
            rtpSocket.send(dp);
        }

        private String sessionId() {
            return Integer.toHexString(ssrc);
        }

        void close() {
            playing = false;
            try { if (rtpSocket != null) rtpSocket.close(); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
        }

        private int headerInt(String[] lines, String name, int fallback) {
            try { return Integer.parseInt(header(lines, name)); } catch (Exception e) { return fallback; }
        }

        private String header(String[] lines, String name) {
            for (String line : lines) {
                if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1))
                    return line.substring(name.length() + 1).trim();
            }
            return "";
        }

        private String value(String text, String key) {
            if (text == null) return null;
            for (String p : text.split(";")) {
                String s = p.trim();
                if (s.startsWith(key + "=")) return s.substring(key.length() + 1);
            }
            return null;
        }
    }
}
