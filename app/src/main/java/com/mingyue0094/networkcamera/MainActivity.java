package com.mingyue0094.networkcamera;

import android.app.Activity;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;
import android.graphics.ImageFormat;

import java.io.IOException;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView surfaceView;
    private TextView status;
    private SurfaceHolder holder;
    private Camera camera;
    private MjpegServer server;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        setContentView(createView());
        holder = surfaceView.getHolder();
        holder.addCallback(this);

        server = new MjpegServer();
        try {
            server.start();
            status.setText("网络摄像头启动中...");
        } catch (IOException e) {
            status.setText("HTTP 8080 启动失败: " + e.getMessage());
        }
    }

    private View createView() {
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new android.widget.FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextColor(0xffffffff);
        status.setTextSize(18);
        status.setPadding(20, 20, 20, 20);
        android.widget.FrameLayout.LayoutParams p =
                new android.widget.FrameLayout.LayoutParams(-2, -2);
        p.leftMargin = 20;
        p.topMargin = 20;
        root.addView(status, p);
        return root;
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        try {
            camera = Camera.open();
            Camera.Parameters params = camera.getParameters();
            Camera.Size size = chooseSize(params.getSupportedPreviewSizes(), 1280, 720);
            if (size != null) {
                params.setPreviewSize(size.width, size.height);
            }
            params.setPreviewFormat(ImageFormat.NV21);
            params.setJpegQuality(75);
            camera.setParameters(params);

            camera.setPreviewDisplay(h);
            camera.setDisplayOrientation(0);

            final int width = camera.getParameters().getPreviewSize().width;
            final int height = camera.getParameters().getPreviewSize().height;

            camera.setPreviewCallback(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera c) {
                    if (server == null || data == null) return;
                    Camera.Parameters p = c.getParameters();
                    int w = p.getPreviewSize().width;
                    int ht = p.getPreviewSize().height;
                    try {
                        android.graphics.YuvImage yuv =
                                new android.graphics.YuvImage(
                                        data, ImageFormat.NV21, w, ht, null);
                        java.io.ByteArrayOutputStream baos =
                                new java.io.ByteArrayOutputStream();
                        yuv.compressToJpeg(
                                new android.graphics.Rect(0, 0, w, ht),
                                75, baos);
                        server.updateFrame(baos.toByteArray());
                    } catch (Exception ignored) {
                    }
                }
            });

            camera.startPreview();
            status.setText("http://" + NetworkUtil.getWifiIp(this) + ":8080/");
        } catch (Exception e) {
            status.setText("摄像头启动失败: " + e.getMessage());
            releaseCamera();
        }
    }

    private Camera.Size chooseSize(List<Camera.Size> sizes, int maxW, int maxH) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = sizes.get(0);
        long bestArea = 0;
        for (Camera.Size s : sizes) {
            if (s.width <= maxW && s.height <= maxH) {
                long area = (long) s.width * s.height;
                if (area > bestArea) {
                    best = s;
                    bestArea = area;
                }
            }
        }
        return best;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        releaseCamera();
    }

    @Override
    protected void onDestroy() {
        releaseCamera();
        if (server != null) {
            server.stop();
            server = null;
        }
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
