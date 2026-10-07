package com.mingyue0094.networkcamera;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
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
import android.os.BatteryManager;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.graphics.SurfaceTexture;
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
    private SurfaceTexture backgroundTexture;
    private Camera camera;
    private MjpegServer server;
    private SharedPreferences prefs;
    private int targetFps = 15;
    private float targetZoom = 1.0f;
    private volatile boolean cameraEnabled = true;
    private BroadcastReceiver wifiReceiver;
    private String cameraError = "";
    private final Object frameLock = new Object();
    private byte[] latestNv21;
    private int latestFrameWidth;
    private int latestFrameHeight;
    private volatile boolean encoderRunning;
    private Thread encoderThread;
    private Camera.PreviewCallback cameraPreviewCallback;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName deviceAdminComponent;
    private boolean pendingLockScreen;
    private static final int REQUEST_ENABLE_DEVICE_ADMIN = 9001;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("camera", MODE_PRIVATE);
        cameraEnabled = prefs.getBoolean("camera_enabled", true);
        setContentView(createView());
        holder = surfaceView.getHolder();
        holder.addCallback(this);
        devicePolicyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        deviceAdminComponent = new ComponentName(this, AdminReceiver.class);
        server = new MjpegServer();
        server.setAuthPassword(prefs.getString("web_password", ""));
        server.setConfigHandler(new MjpegServer.ConfigHandler() {
            @Override public String getStatusJson() { return getWebStatusJson(); }
            @Override public String applyConfig(String resolution, int fps, float zoom, String focus) {
                return applyWebConfig(resolution, fps, zoom, focus);
            }
            @Override public boolean openSettings() {
                return openAppSettings();
            }
            @Override public String setCameraEnabled(boolean enabled) {
                return setCameraEnabledFromWeb(enabled);
            }
            @Override public String setCameraBrightness(int compensation) {
                return setCameraBrightnessFromWeb(compensation);
            }
            @Override public String focusAt(float x, float y) {
                return focusAtFromWeb(x, y);
            }
            @Override public String setWebPassword(String password) {
                return setWebPasswordFromWeb(password);
            }
            @Override public String lockScreen() {
                return lockScreenFromWeb();
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

        status = new TextView(this);
        status.setTextColor(0xffffffff);
        status.setTextSize(16);
        status.setPadding(15, 15, 15, 15);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2);
        sp.leftMargin = 15;
        sp.topMargin = 15;
        root.addView(status, sp);

        // 启动页面不再显示原来的参数选择、摄像头按钮、密码按钮和“应用”按钮。
        // 这些功能仍然保留在代码和网页控制端。
        return root;
    }

    /**
     * 执行原来“应用”按钮的完整逻辑。
     * 启动时由 surfaceCreated 自动调用，相当于自动点击一次“应用”。
     */
    private void applyCurrentSettings() {
        int ri = prefs.getInt("resolution", 2);
        if (ri < 0 || ri > 4) ri = 2;

        int[] widths = {640, 800, 1280, 1280, 1920};
        int[] heights = {480, 600, 720, 960, 1080};

        int[] fpsList = {5, 10, 15, 20, 24, 25, 30};
        int savedFps = prefs.getInt("fps", 15);
        int fps = savedFps;
        boolean validFps = false;
        for (int value : fpsList) {
            if (value == savedFps) {
                validFps = true;
                break;
            }
        }
        if (!validFps) fps = 15;

        float zoom = prefs.getFloat("zoom", 1.0f);
        if (zoom < 1.0f) zoom = 1.0f;

        targetFps = fps;
        targetZoom = zoom;

        if (cameraEnabled && holder != null
                && holder.getSurface() != null
                && holder.getSurface().isValid()) {
            restartCamera(widths[ri], heights[ri], targetFps, targetZoom,
                    prefs.getString("focus_mode", "continuous"));
        } else if (!cameraEnabled) {
            setStatusText("摄像头: 已关闭（省电）");
        } else {
            setStatusText("摄像头预览界面尚未就绪");
        }
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

    private String setWebPasswordFromWeb(String password) {
        if (password == null) password = "";
        password = password.trim();
        prefs.edit().putString("web_password", password).apply();
        if (server != null) server.setAuthPassword(password);
        return "{\"ok\":true,\"passwordSet\":" + (!password.isEmpty()) + "}";
    }

    private String lockScreenFromWeb() {
        try {
            if (devicePolicyManager == null) return "{\"ok\":false,\"error\":\"设备锁屏功能不可用\"}";
            if (!devicePolicyManager.isAdminActive(deviceAdminComponent)) {
                pendingLockScreen = true;
                final Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminComponent);
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "允许网络摄像头通过网页按钮手动锁定手机屏幕。");
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try { startActivityForResult(intent, REQUEST_ENABLE_DEVICE_ADMIN); } catch (Exception ignored) {}
                    }
                });
                return "{\"ok\":false,\"needAdmin\":true,\"error\":\"请先在手机上授权设备管理\"}";
            }
            devicePolicyManager.lockNow();
            return "{\"ok\":true,\"locked\":true}";
        } catch (SecurityException e) {
            return "{\"ok\":false,\"error\":\"没有设备管理锁屏权限\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"锁屏失败\"}";
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_DEVICE_ADMIN && pendingLockScreen) {
            pendingLockScreen = false;
            if (resultCode == RESULT_OK && devicePolicyManager != null && devicePolicyManager.isAdminActive(deviceAdminComponent)) {
                try { devicePolicyManager.lockNow(); } catch (Exception ignored) {}
            }
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

    private int getBatteryPercent() {
        try {
            Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return -1;
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) return -1;
            return (level * 100) / scale;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private String setCameraEnabledFromWeb(boolean enabled) {
        cameraEnabled = enabled;
        prefs.edit().putBoolean("camera_enabled", enabled).apply();
        if (enabled) {
            if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                startCamera();
            }
        } else {
            releaseCamera();
            setStatusText("摄像头: 已关闭（省电）");
        }
        return getWebStatusJson();
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
        int battery = getBatteryPercent();
        int exposure = 0;
        int exposureMin = 0;
        int exposureMax = 0;
        if (p != null) {
            try {
                exposure = p.getExposureCompensation();
                exposureMin = p.getMinExposureCompensation();
                exposureMax = p.getMaxExposureCompensation();
            } catch (Exception ignored) {}
        }
        String focus = prefs.getString("focus_mode", "continuous");
        return "{\"ok\":true,\"camera\":" + cameraEnabled
                + ",\"resolution\":\"" + resolution + "\",\"fps\":" + fps
                + ",\"zoom\":" + zoom + ",\"focus\":\"" + focus
                + "\",\"battery\":" + battery
                + ",\"brightness\":" + exposure
                + ",\"brightnessMin\":" + exposureMin
                + ",\"brightnessMax\":" + exposureMax + "}";
    }

    /** 网页点击画面后，在点击位置创建 Camera1 focus/metering area 并执行一次自动对焦。 */
    private String focusAtFromWeb(float x, float y) {
        try {
            if (camera == null) return getWebStatusJsonWithFocus(false, "camera unavailable");
            x = Math.max(0f, Math.min(1f, x));
            y = Math.max(0f, Math.min(1f, y));
            Camera.Parameters p = camera.getParameters();
            List<String> modes = p.getSupportedFocusModes();
            if (modes == null || !modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                try {
                    camera.autoFocus(new Camera.AutoFocusCallback() {
                        @Override public void onAutoFocus(boolean success, Camera c) {}
                    });
                    return getWebStatusJsonWithFocus(true, "autofocus triggered");
                } catch (Exception e) {
                    return getWebStatusJsonWithFocus(false, "autofocus unavailable");
                }
            }
            final String oldMode = p.getFocusMode();
            int cx = Math.round(x * 2000f - 1000f);
            int cy = Math.round(y * 2000f - 1000f);
            int half = 120;
            Camera.Area area = new Camera.Area(
                    new android.graphics.Rect(
                            Math.max(-1000, cx - half), Math.max(-1000, cy - half),
                            Math.min(1000, cx + half), Math.min(1000, cy + half)), 1000);
            if (p.getMaxNumFocusAreas() > 0) {
                java.util.ArrayList<Camera.Area> areas = new java.util.ArrayList<Camera.Area>();
                areas.add(area);
                p.setFocusAreas(areas);
            }
            if (p.getMaxNumMeteringAreas() > 0) {
                java.util.ArrayList<Camera.Area> areas = new java.util.ArrayList<Camera.Area>();
                areas.add(area);
                p.setMeteringAreas(areas);
            }
            p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            camera.setParameters(p);
            camera.autoFocus(new Camera.AutoFocusCallback() {
                @Override public void onAutoFocus(boolean success, Camera c) {
                    if (c == null) return;
                    try {
                        if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO.equals(oldMode)) {
                            Camera.Parameters restore = c.getParameters();
                            List<String> supported = restore.getSupportedFocusModes();
                            if (supported != null && supported.contains(oldMode)) {
                                restore.setFocusMode(oldMode);
                                c.setParameters(restore);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            });
            return getWebStatusJsonWithFocus(true, "focus requested");
        } catch (Exception e) {
            return getWebStatusJsonWithFocus(false, "focus failed");
        }
    }

    private String getWebStatusJsonWithFocus(boolean ok, String message) {
        String base = getWebStatusJson();
        if (base.endsWith("}")) base = base.substring(0, base.length() - 1);
        return base + ",\"focusOk\":" + ok + ",\"focusMessage\":\"" + jsonEscape(message) + "\"}";
    }

    private String setCameraBrightnessFromWeb(int compensation) {
        try {
            if (camera == null) return getWebStatusJson();
            Camera.Parameters p = camera.getParameters();
            int min = p.getMinExposureCompensation();
            int max = p.getMaxExposureCompensation();
            if (max <= min) return getWebStatusJson();
            if (compensation < min) compensation = min;
            if (compensation > max) compensation = max;
            p.setExposureCompensation(compensation);
            camera.setParameters(p);
            prefs.edit().putInt("brightness_compensation", compensation).apply();
            return getWebStatusJson();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"camera brightness unavailable\"}";
        }
    }

    private void setSavedExposure(Camera.Parameters p) {
        try {
            int min = p.getMinExposureCompensation();
            int max = p.getMaxExposureCompensation();
            if (max <= min) return;
            int value = prefs.getInt("brightness_compensation", 0);
            if (value < min) value = min;
            if (value > max) value = max;
            p.setExposureCompensation(value);
        } catch (Exception ignored) {}
    }

    private String applyWebConfig(String resolution, int fps, float zoom, String focus) {
        try {
            String[] parts = resolution.split("x");
            if (parts.length != 2) throw new IllegalArgumentException();
            int w = Integer.parseInt(parts[0]);
            int h = Integer.parseInt(parts[1]);
            if (fps < 1 || fps > 60 || zoom < 1f || zoom > 20f) throw new IllegalArgumentException();
            if (!"continuous".equals(focus) && !"single".equals(focus) && !"lock".equals(focus)) throw new IllegalArgumentException();
            prefs.edit().putInt("resolution", resolutionIndex(w, h))
                    .putInt("fps", fps).putFloat("zoom", zoom).putString("focus_mode", focus).apply();
            targetFps = fps;
            targetZoom = zoom;
            if (!cameraEnabled) {
                return getWebStatusJson();
            }
            restartCamera(w, h, fps, zoom, focus);
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

    @Override public void surfaceCreated(SurfaceHolder h) {
        // 解锁/亮屏后重新把 Camera1 预览绑定到可见 Surface。
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.setPreviewCallbackWithBuffer(cameraPreviewCallback);
                camera.setPreviewDisplay(h);
                camera.startPreview();
                triggerAutoFocusIfNeeded(prefs.getString("focus_mode", "continuous"));
                return;
            } catch (Exception ignored) {
                // 绑定失败时再走完整启动流程。
                releaseCamera();
            }
        }
        // 首次启动时自动执行一次原“应用”按钮逻辑。
        applyCurrentSettings();
    }

    private void startCamera() {
        int ri = prefs.getInt("resolution", 2);
        int[] widths = {640, 800, 1280, 1280, 1920};
        int[] heights = {480, 600, 720, 960, 1080};
        targetFps = prefs.getInt("fps", 15);
        targetZoom = prefs.getFloat("zoom", 1.0f);
        restartCamera(widths[ri], heights[ri], targetFps, targetZoom, prefs.getString("focus_mode", "continuous"));
    }

    private void restartCamera(int wantedW, int wantedH, int fps, float zoom, String focusMode) {
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
            setFocusMode(params, focusMode);
            setSavedExposure(params);
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
        cameraPreviewCallback = new Camera.PreviewCallback() {
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
        };
        camera.setPreviewCallbackWithBuffer(cameraPreviewCallback);
        int bufferSize = encodeSize.width * encodeSize.height * 3 / 2;
        try {
            camera.addCallbackBuffer(new byte[bufferSize]);
            camera.addCallbackBuffer(new byte[bufferSize]);
            camera.addCallbackBuffer(new byte[bufferSize]);
        } catch (Exception ignored) {}

        try {
            camera.startPreview();
            triggerAutoFocusIfNeeded(focusMode);
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
                    + getZoomText(camera.getParameters()) + "  Focus:" + getFocusModeText(camera.getParameters()));
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

    private void setFocusMode(Camera.Parameters p, String wanted) {
        try {
            List<String> modes = p.getSupportedFocusModes();
            if (modes == null || modes.isEmpty()) return;
            if ("continuous".equals(wanted) && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if ("single".equals(wanted) && modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            } else if ("lock".equals(wanted)) {
                if (modes.contains(Camera.Parameters.FOCUS_MODE_FIXED)) p.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
                else if (modes.contains(Camera.Parameters.FOCUS_MODE_INFINITY)) p.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
                else if (modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            } else if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
        } catch (Exception ignored) {}
    }

    private void triggerAutoFocusIfNeeded(String wanted) {
        if (!"single".equals(wanted) || camera == null) return;
        try {
            if (Camera.Parameters.FOCUS_MODE_AUTO.equals(camera.getParameters().getFocusMode())) {
                camera.autoFocus(new Camera.AutoFocusCallback() {
                    @Override public void onAutoFocus(boolean success, Camera c) {}
                });
            }
        } catch (Exception ignored) {}
    }

    private String getFocusModeText(Camera.Parameters p) {
        try {
            String mode = p.getFocusMode();
            if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO.equals(mode)) return "连续自动";
            if (Camera.Parameters.FOCUS_MODE_AUTO.equals(mode)) return "单次自动/锁定";
            if (Camera.Parameters.FOCUS_MODE_FIXED.equals(mode) ||
                    Camera.Parameters.FOCUS_MODE_INFINITY.equals(mode)) return "锁定";
            return mode == null ? "未知" : mode;
        } catch (Exception e) {
            return "未知";
        }
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

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        // 锁屏会导致 SurfaceView 的 Surface 被销毁。
        // 不释放 Camera，否则网页 MJPEG 画面会随锁屏停止。
        // 改用后台 SurfaceTexture 接收 Camera1 预览，编码线程继续工作。
        if (camera == null) return;
        try {
            camera.stopPreview();
            if (backgroundTexture == null) {
                backgroundTexture = new SurfaceTexture(0);
            }
            camera.setPreviewTexture(backgroundTexture);
            camera.startPreview();
        } catch (Exception e) {
            // 后台纹理绑定失败时仍保持 Camera/编码线程，不主动释放摄像头。
        }
    }

    @Override protected void onDestroy() {
        if (wifiReceiver != null) {
            try { unregisterReceiver(wifiReceiver); } catch (Exception ignored) {}
            wifiReceiver = null;
        }
        releaseCamera();
        if (server != null) { server.stop(); server = null; }
        if (backgroundTexture != null) {
            try { backgroundTexture.release(); } catch (Exception ignored) {}
            backgroundTexture = null;
        }
        super.onDestroy();
    }

    private void releaseCamera() {
        stopEncoder();
        if (server != null) server.clearFrame();
        if (camera != null) {
            try { camera.setPreviewCallback(null); } catch (Exception ignored) {}
            try { camera.stopPreview(); } catch (Exception ignored) {}
            try { camera.release(); } catch (Exception ignored) {}
            camera = null;
            cameraPreviewCallback = null;
        }
    }
}
