package com.mingyue0094.networkcamera;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.provider.Settings;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.*;
import android.graphics.ImageFormat;
import java.io.IOException;
import android.graphics.ImageFormat;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView surfaceView;
    private TextView status;
    private SurfaceHolder holder;
    private Camera camera;
    private MjpegServer server;
    private SharedPreferences prefs;
    private int targetFps = 15;
    private float targetZoom = 1.0f;
    private BroadcastReceiver wifiReceiver;
    private String cameraError = "";
    private final Object frameLock = new Object();
    private byte[] latestNv21;
    private int latestFrameWidth;
    private int latestFrameHeight;
    private volatile boolean encoderRunning;
    private Thread encoderThread;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("camera", MODE_PRIVATE);
        setContentView(createView());
        holder = surfaceView.getHolder();
        holder.addCallback(this);
        server = new MjpegServer();
        server.setAuthPassword(prefs.getString("web_password", ""));
        server.setConfigHandler(new MjpegServer.ConfigHandler() {
            @Override public String getStatusJson() { return getWebStatusJson(); }
            @Override public String applyConfig(String resolution, int fps, float zoom) {
                return applyWebConfig(resolution, fps, zoom);
            }
            @Override public boolean openSettings() {
                return openAppSettings();
            }
        });
        try { server.start(); status.setText("网络摄像头启动中..."); }
        catch (IOException e) { status.setText("HTTP 8080 启动失败: " + e.getMessage()); }
        registerWifiReceiver();
        updateNetworkStatus();
    }

    private View createView() {
        FrameLayout root = new FrameLayout(this);
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(20, 20, 20, 20);
        panel.setBackgroundColor(0xaa000000);

        status = new TextView(this);
        status.setTextColor(0xffffffff);
        status.setTextSize(16);
        panel.addView(status);

        LinearLayout row = new LinearLayout(this);

        final Spinner resolution = new Spinner(this);
        final String[] resolutions = {"640x480", "800x600", "1280x720", "1280x960", "1920x1080"};
        ArrayAdapter<String> ra = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, resolutions);
        ra.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolution.setAdapter(ra);
        resolution.setSelection(prefs.getInt("resolution", 2));

        final Spinner fps = new Spinner(this);
        final String[] fpsValues = {"5 FPS", "10 FPS", "15 FPS", "20 FPS", "24 FPS", "25 FPS", "30 FPS"};
        ArrayAdapter<String> fa = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, fpsValues);
        fa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fps.setAdapter(fa);
        fps.setSelection(savedFpsIndex(prefs.getInt("fps", 15)));

        final Spinner zoom = new Spinner(this);
        final String[] zoomValues = {"1x", "1.5x", "2x", "3x", "4x", "6x", "8x"};
        ArrayAdapter<String> za = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, zoomValues);
        za.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        zoom.setAdapter(za);
        zoom.setSelection(savedZoomIndex(prefs.getFloat("zoom", 1.0f)));

        Button apply = new Button(this);
        apply.setText("应用");
        row.addView(resolution);
        row.addView(fps);
        row.addView(zoom);
        row.addView(apply);
        panel.addView(row);

        LinearLayout authRow = new LinearLayout(this);
        final EditText password = new EditText(this);
        password.setHint("网页访问密码（留空关闭密码）");
        password.setSingleLine(true);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(prefs.getString("web_password", ""));
        Button authApply = new Button(this);
        authApply.setText("保存密码");
        authRow.addView(password, new LinearLayout.LayoutParams(0, -2, 1));
        authRow.addView(authApply);
        panel.addView(authRow);

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-2, -2);
        pp.leftMargin = 15; pp.topMargin = 15;
        root.addView(panel, pp);

        authApply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String value = password.getText().toString();
                prefs.edit().putString("web_password", value).apply();
                if (server != null) server.setAuthPassword(value);
                Toast.makeText(MainActivity.this, value.length() == 0 ? "网页密码已关闭" : "网页密码已保存，用户名：admin", Toast.LENGTH_SHORT).show();
            }
        });

        apply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int ri = resolution.getSelectedItemPosition();
                int[] widths = {640, 800, 1280, 1280, 1920};
                int[] heights = {480, 600, 720, 960, 1080};
                int[] fpsList = {5, 10, 15, 20, 24, 25, 30};
                float[] zoomList = {1f, 1.5f, 2f, 3f, 4f, 6f, 8f};
                int fi = fps.getSelectedItemPosition();
                int zi = zoom.getSelectedItemPosition();
                prefs.edit().putInt("resolution", ri).putInt("fps", fpsList[fi])
                        .putFloat("zoom", zoomList[zi]).apply();
                targetFps = fpsList[fi];
                targetZoom = zoomList[zi];
                // “应用”必须负责启动/重启摄像头；即使之前 camera 启动失败或尚未启动，也要重试。
                if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                    restartCamera(widths[ri], heights[ri], targetFps, targetZoom);
                } else {
                    status.setText("摄像头预览界面尚未就绪，请稍后再点应用");
                }
            }
        });
        return root;
    }

    /**
     * 从网页端 /setings 打开本应用在系统设置中的“应用信息”页面。
     * 这里优先打开本应用详情页，用户可以直接点击“卸载”；如果 ROM 不支持，
     * 再回退到系统设置首页。
     */
    private boolean openAppSettings() {
        try {
            final Intent appDetails = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        startActivity(appDetails);
                    } catch (Exception e) {
                        try {
                            Intent fallback = new Intent(Settings.ACTION_SETTINGS);
                            startActivity(fallback);
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void setStatusText(final String text) {
        if (status == null) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (status != null) status.setText(text);
            }
        });
    }

    private String getWebStatusJson() {
        Camera.Parameters p = camera == null ? null : camera.getParameters();
        String resolution = "unknown";
        if (p != null) {
            Camera.Size s = p.getPreviewSize();
            if (s != null) resolution = s.width + "x" + s.height;
        }
        int fps = targetFps;
        if (p != null) {
            try { int[] r = new int[2]; p.getPreviewFpsRange(r); fps = (r[1] + 500) / 1000; } catch (Exception ignored) {}
        }
        float zoom = targetZoom;
        if (p != null) {
            try {
                if (p.isZoomSupported()) {
                    List<Integer> ratios = p.getZoomRatios();
                    int i = p.getZoom();
                    if (ratios != null && i >= 0 && i < ratios.size()) zoom = ratios.get(i) / 100.0f;
                }
            } catch (Exception ignored) {}
        }
        return "{\"ok\":true,\"resolution\":\"" + resolution + "\",\"fps\":" + fps + ",\"zoom\":" + zoom + "}";
    }

    private String applyWebConfig(String resolution, int fps, float zoom) {
        try {
            String[] parts = resolution.split("x");
            if (parts.length != 2) throw new IllegalArgumentException();
            int w = Integer.parseInt(parts[0]);
            int h = Integer.parseInt(parts[1]);
            if (fps < 1 || fps > 60 || zoom < 1f || zoom > 20f) throw new IllegalArgumentException();
            prefs.edit().putInt("resolution", resolutionIndex(w, h))
                    .putInt("fps", fps).putFloat("zoom", zoom).apply();
            targetFps = fps;
            targetZoom = zoom;
            if (camera == null) {
                restartCamera(w, h, fps, zoom);
            } else {
                restartCamera(w, h, fps, zoom);
            }
            if (camera == null) {
                String err = cameraError.length() == 0 ? "camera restart failed" : cameraError;
                return "{\"ok\":false,\"error\":\"" + jsonEscape(err) + "\"}";
            }
            return getWebStatusJson();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"invalid parameters\"}";
        }
    }

    private int resolutionIndex(int w, int h) {
        if (w == 640 && h == 480) return 0;
        if (w == 800 && h == 600) return 1;
        if (w == 1280 && h == 720) return 2;
        if (w == 1280 && h == 960) return 3;
        return 4;
    }

    private int savedFpsIndex(int fps) {
        int[] values = {5, 10, 15, 20, 24, 25, 30};
        for (int i = 0; i < values.length; i++) if (values[i] == fps) return i;
        return 2;
    }

    private int savedZoomIndex(float zoom) {
        float[] values = {1f, 1.5f, 2f, 3f, 4f, 6f, 8f};
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - zoom) < 0.01f) return i;
        return 0;
    }

    private void registerWifiReceiver() {
        wifiReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                updateNetworkStatus();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction("android.net.wifi.STATE_CHANGE");
        filter.addAction("android.net.wifi.WIFI_STATE_CHANGED");
        registerReceiver(wifiReceiver, filter);
    }

    private void updateNetworkStatus() {
        String ip = NetworkUtil.getWifiIp(this);
        if (ip == null || "0.0.0.0".equals(ip)) {
            status.setText("WiFi未连接\n网络摄像头端口: 8080");
        } else {
            status.setText("网络摄像头\nhttp://" + ip + ":8080/");
        }
    }

    @Override public void surfaceCreated(SurfaceHolder h) { startCamera(); }

    private void startCamera() {
        int ri = prefs.getInt("resolution", 2);
        int[] widths = {640, 800, 1280, 1280, 1920};
        int[] heights = {480, 600, 720, 960, 1080};
        targetFps = prefs.getInt("fps", 15);
        targetZoom = prefs.getFloat("zoom", 1.0f);
        restartCamera(widths[ri], heights[ri], targetFps, targetZoom);
    }

    private void restartCamera(int wantedW, int wantedH, int fps, float zoom) {
        releaseCamera();
        cameraError = "";

        // HTTP 服务和摄像头是两个独立服务，先明确显示 HTTP 状态。
        String ip = NetworkUtil.getWifiIp(this);
        String network = (ip == null || "0.0.0.0".equals(ip))
                ? "HTTP服务: 8080 OK\nWiFi未连接"
                : "HTTP服务: 8080 OK\nhttp://" + ip + ":8080/";
        setStatusText(network + "\n摄像头: 正在启动...");

        try {
            camera = Camera.open();
        } catch (Exception e) {
            cameraError = "Camera.open 失败: " + formatException(e);
            setStatusText(network + "\n摄像头: " + cameraError);
            releaseCamera();
            return;
        }

        Camera.Parameters params;
        try {
            params = camera.getParameters();
        } catch (Exception e) {
            cameraError = "getParameters 失败: " + formatException(e);
            setStatusText(network + "\n摄像头: " + cameraError);
            releaseCamera();
            return;
        }

        try {
            Camera.Size size = chooseClosestSize(params.getSupportedPreviewSizes(), wantedW, wantedH);
            if (size == null) throw new IOException("没有可用的预览分辨率");
            params.setPreviewSize(size.width, size.height);
        } catch (Exception e) {
            cameraError = "设置分辨率失败: " + formatException(e);
            setStatusText(network + "\n摄像头: " + cameraError);
            releaseCamera();
            return;
        }

        try {
            params.setPreviewFormat(ImageFormat.NV21);
            params.setJpegQuality(75);
            setFps(params, fps);
            setZoom(params, zoom);
        } catch (Exception e) {
            cameraError = "设置参数失败: " + formatException(e);
            setStatusText(network + "\n摄像头: " + cameraError);
            releaseCamera();
            return;
        }

        try {
            camera.setParameters(params);
        } catch (Exception e) {
            cameraError = "camera.setParameters 失败: " + formatException(e);
            setStatusText(network + "\n摄像头: " + cameraError);
            releaseCamera();
            return;
        }

        try {
            camera.setPreviewDisplay(holder);
            camera.setDisplayOrientation(0);
        } catch (Exception e) {
            cameraError = "绑定预览画面失败: " + formatException(e);
            setStatusText(network + "\n摄像头: " + cameraError);
            releaseCamera();
            return;
        }

        // JPEG 编码放到独立线程，避免阻塞 Camera 回调导致预览卡顿。
        final Camera.Size encodeSize = camera.getParameters().getPreviewSize();
        startEncoder(encodeSize.width, encodeSize.height);
        camera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
            @Override public void onPreviewFrame(byte[] data, Camera c) {
                if (data == null) return;
                synchronized (frameLock) {
                    latestNv21 = data.clone();
                    latestFrameWidth = encodeSize.width;
                    latestFrameHeight = encodeSize.height;
                    frameLock.notifyAll();
                }
                try { c.addCallbackBuffer(data); } catch (Exception ignored) {}
            }
        });
        int bufferSize = encodeSize.width * encodeSize.height * 3 / 2;
        try {
            camera.addCallbackBuffer(new byte[bufferSize]);
            camera.addCallbackBuffer(new byte[bufferSize]);
            camera.addCallbackBuffer(new byte[bufferSize]);
        } catch (Exception ignored) {}

        try {
            camera.startPreview();
        } catch (Exception e) {
            cameraError = "camera.startPreview 失败: " + formatException(e);
            setStatusText(network + "\n摄像头: " + cameraError);
            releaseCamera();
            return;
        }

        try {
            Camera.Size actual = camera.getParameters().getPreviewSize();
            cameraError = "";
            setStatusText(network + "\n摄像头: OK\n"
                    + actual.width + "x" + actual.height + "  "
                    + getFpsText(camera.getParameters()) + "  "
                    + getZoomText(camera.getParameters()));
        } catch (Exception e) {
            setStatusText(network + "\n摄像头: 已启动（读取实际参数失败）\n"
                    + formatException(e));
        }
    }

    private void startEncoder(final int width, final int height) {
        stopEncoder();
        encoderRunning = true;
        synchronized (frameLock) {
            latestNv21 = null;
            latestFrameWidth = width;
            latestFrameHeight = height;
        }
        encoderThread = new Thread(new Runnable() {
            @Override public void run() {
                while (encoderRunning) {
                    byte[] data;
                    int w;
                    int h;
                    synchronized (frameLock) {
                        while (encoderRunning && latestNv21 == null) {
                            try { frameLock.wait(200); } catch (InterruptedException ignored) {}
                        }
                        if (!encoderRunning) return;
                        data = latestNv21;
                        w = latestFrameWidth;
                        h = latestFrameHeight;
                        latestNv21 = null;
                    }
                    try {
                        android.graphics.YuvImage yuv = new android.graphics.YuvImage(data, ImageFormat.NV21, w, h, null);
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        yuv.compressToJpeg(new android.graphics.Rect(0, 0, w, h), 70, baos);
                        if (server != null) server.updateFrame(baos.toByteArray());
                    } catch (Exception ignored) {}
                }
            }
        }, "jpeg-encoder");
        encoderThread.setDaemon(true);
        encoderThread.start();
    }

    private void stopEncoder() {
        encoderRunning = false;
        synchronized (frameLock) {
            latestNv21 = null;
            frameLock.notifyAll();
        }
        if (encoderThread != null) {
            try { encoderThread.interrupt(); } catch (Exception ignored) {}
            encoderThread = null;
        }
    }
    private String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private String formatException(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.length() == 0) msg = e.toString();
        return e.getClass().getSimpleName() + ": " + msg;
    }

    private void setFps(Camera.Parameters p, int wanted) {
        try {
            List<int[]> ranges = p.getSupportedPreviewFpsRange();
            if (ranges == null || ranges.isEmpty()) return;
            int target = wanted * 1000;
            int[] best = ranges.get(0);
            int bestDiff = Integer.MAX_VALUE;
            for (int[] r : ranges) {
                int mid = (r[0] + r[1]) / 2;
                int diff = Math.abs(mid - target);
                if (diff < bestDiff) { best = r; bestDiff = diff; }
            }
            p.setPreviewFpsRange(best[0], best[1]);
        } catch (Exception ignored) {}
    }

    private void setZoom(Camera.Parameters p, float wanted) {
        try {
            if (!p.isZoomSupported()) return;
            List<Integer> ratios = p.getZoomRatios();
            int max = p.getMaxZoom();
            if (ratios == null || ratios.isEmpty() || max <= 0) return;
            int best = 0;
            int bestDiff = Integer.MAX_VALUE;
            int target = Math.round(wanted * 100);
            for (int i = 0; i < ratios.size(); i++) {
                int diff = Math.abs(ratios.get(i) - target);
                if (diff < bestDiff) { best = i; bestDiff = diff; }
            }
            if (best > max) best = max;
            p.setZoom(best);
        } catch (Exception ignored) {}
    }

    private String getZoomText(Camera.Parameters p) {
        try {
            if (!p.isZoomSupported()) return "Zoom不支持";
            List<Integer> ratios = p.getZoomRatios();
            int index = p.getZoom();
            if (ratios != null && index >= 0 && index < ratios.size())
                return String.format(java.util.Locale.US, "%.1fx", ratios.get(index) / 100.0f);
        } catch (Exception ignored) {}
        return targetZoom + "x";
    }

    private String getFpsText(Camera.Parameters p) {
        try {
            int[] r = new int[2];
            p.getPreviewFpsRange(r);
            return ((r[1] + 500) / 1000) + " FPS";
        } catch (Exception e) { return targetFps + " FPS"; }
    }

    private Camera.Size chooseClosestSize(List<Camera.Size> sizes, int w, int h) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = sizes.get(0);
        long bestScore = Long.MAX_VALUE;
        for (Camera.Size s : sizes) {
            long score = Math.abs(s.width - w) * 1000L + Math.abs(s.height - h);
            if (score < bestScore) { best = s; bestScore = score; }
        }
        return best;
    }

    @Override public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
        // Preview configuration is handled when the surface is created.
    }

    @Override public void surfaceDestroyed(SurfaceHolder h) { releaseCamera(); }

    @Override protected void onDestroy() {
        if (wifiReceiver != null) {
            try { unregisterReceiver(wifiReceiver); } catch (Exception ignored) {}
            wifiReceiver = null;
        }
        releaseCamera();
        if (server != null) { server.stop(); server = null; }
        super.onDestroy();
    }

    private void releaseCamera() {
        stopEncoder();
        if (camera != null) {
            try { camera.setPreviewCallback(null); } catch (Exception ignored) {}
            try { camera.stopPreview(); } catch (Exception ignored) {}
            try { camera.release(); } catch (Exception ignored) {}
            camera = null;
        }
    }
}
