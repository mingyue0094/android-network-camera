package com.mingyue0094.networkcamera;

import android.app.Activity;
import android.content.SharedPreferences;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.*;
import android.graphics.ImageFormat;
import java.io.IOException;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView surfaceView;
    private TextView status;
    private SurfaceHolder holder;
    private Camera camera;
    private MjpegServer server;
    private SharedPreferences prefs;
    private int targetFps = 15;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("camera", MODE_PRIVATE);
        setContentView(createView());
        holder = surfaceView.getHolder();
        holder.addCallback(this);
        server = new MjpegServer();
        try { server.start(); status.setText("网络摄像头启动中..."); }
        catch (IOException e) { status.setText("HTTP 8080 启动失败: " + e.getMessage()); }
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
        Spinner resolution = new Spinner(this);
        final String[] resolutions = {"640x480", "800x600", "1280x720", "1280x960", "1920x1080"};
        ArrayAdapter<String> ra = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, resolutions);
        ra.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolution.setAdapter(ra);
        resolution.setSelection(prefs.getInt("resolution", 2));

        Spinner fps = new Spinner(this);
        final String[] fpsValues = {"5 FPS", "10 FPS", "15 FPS", "20 FPS", "24 FPS", "25 FPS", "30 FPS"};
        ArrayAdapter<String> fa = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, fpsValues);
        fa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fps.setAdapter(fa);
        fps.setSelection(savedFpsIndex(prefs.getInt("fps", 15)));

        Button apply = new Button(this);
        apply.setText("应用");
        row.addView(resolution);
        row.addView(fps);
        row.addView(apply);
        panel.addView(row);

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-2, -2);
        pp.leftMargin = 15; pp.topMargin = 15;
        root.addView(panel, pp);

        apply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int ri = resolution.getSelectedItemPosition();
                int[] widths = {640, 800, 1280, 1280, 1920};
                int[] heights = {480, 600, 720, 960, 1080};
                int[] fpsList = {5, 10, 15, 20, 24, 25, 30};
                int fi = fps.getSelectedItemPosition();
                prefs.edit().putInt("resolution", ri).putInt("fps", fpsList[fi]).apply();
                targetFps = fpsList[fi];
                if (camera != null) restartCamera(widths[ri], heights[ri], targetFps);
            }
        });
        return root;
    }

    private int savedFpsIndex(int fps) {
        int[] values = {5, 10, 15, 20, 24, 25, 30};
        for (int i = 0; i < values.length; i++) if (values[i] == fps) return i;
        return 2;
    }

    @Override public void surfaceCreated(SurfaceHolder h) { startCamera(); }

    private void startCamera() {
        int ri = prefs.getInt("resolution", 2);
        int[] widths = {640, 800, 1280, 1280, 1920};
        int[] heights = {480, 600, 720, 960, 1080};
        targetFps = prefs.getInt("fps", 15);
        restartCamera(widths[ri], heights[ri], targetFps);
    }

    private void restartCamera(int wantedW, int wantedH, int fps) {
        releaseCamera();
        try {
            camera = Camera.open();
            Camera.Parameters params = camera.getParameters();
            Camera.Size size = chooseClosestSize(params.getSupportedPreviewSizes(), wantedW, wantedH);
            if (size != null) params.setPreviewSize(size.width, size.height);
            params.setPreviewFormat(ImageFormat.NV21);
            params.setJpegQuality(75);
            setFps(params, fps);
            camera.setParameters(params);
            camera.setPreviewDisplay(holder);
            camera.setDisplayOrientation(0);

            camera.setPreviewCallback(new Camera.PreviewCallback() {
                @Override public void onPreviewFrame(byte[] data, Camera c) {
                    if (server == null || data == null) return;
                    Camera.Size s = c.getParameters().getPreviewSize();
                    try {
                        android.graphics.YuvImage yuv = new android.graphics.YuvImage(
                                data, ImageFormat.NV21, s.width, s.height, null);
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        yuv.compressToJpeg(new android.graphics.Rect(0, 0, s.width, s.height), 75, baos);
                        server.updateFrame(baos.toByteArray());
                    } catch (Exception ignored) {}
                }
            });
            camera.startPreview();
            Camera.Size actual = camera.getParameters().getPreviewSize();
            status.setText("http://" + NetworkUtil.getWifiIp(this) + ":8080/  "
                    + actual.width + "x" + actual.height + "  " + getFpsText(camera.getParameters()));
        } catch (Exception e) {
            status.setText("摄像头启动失败: " + e.getMessage());
            releaseCamera();
        }
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

    private String getFpsText(Camera.Parameters p) {
        try {
            int[] r = p.getPreviewFpsRange();
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

    @Override public void surfaceDestroyed(SurfaceHolder h) { releaseCamera(); }

    @Override protected void onDestroy() {
        releaseCamera();
        if (server != null) { server.stop(); server = null; }
        super.onDestroy();
    }

    private void releaseCamera() {
        if (camera != null) {
            try { camera.setPreviewCallback(null); } catch (Exception ignored) {}
            try { camera.stopPreview(); } catch (Exception ignored) {}
            try { camera.release(); } catch (Exception ignored) {}
            camera = null;
        }
    }
}
