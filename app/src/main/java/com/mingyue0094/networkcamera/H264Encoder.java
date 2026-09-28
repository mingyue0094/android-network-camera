package com.mingyue0094.networkcamera;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import java.nio.ByteBuffer;

final class H264Encoder {
    interface Listener {
        void onFormat(int width, int height, int fps, byte[] csd0, byte[] csd1);
        void onFrame(byte[] data, long ptsUs, boolean keyFrame);
        void onError(String message);
    }

    private MediaCodec codec;
    private ByteBuffer[] inputBuffers;
    private ByteBuffer[] outputBuffers;
    private Thread thread;
    private volatile boolean running;
    private Listener listener;
    private int width, height, fps, bitrate, colorFormat;
    private long lastQueuedUs;

    void start(int width, int height, int fps, int bitrate, Listener listener) throws Exception {
        stop();
        this.width = width; this.height = height; this.fps = Math.max(1, fps);
        this.bitrate = bitrate; this.listener = listener;
        MediaCodecInfo info = findEncoder();
        if (info == null) throw new IllegalStateException("没有找到 H.264 硬件编码器");
        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType("video/avc");
        colorFormat = chooseColorFormat(caps);
        if (colorFormat == 0) throw new IllegalStateException("H.264 编码器没有可用的 YUV420 输入格式");
        codec = MediaCodec.createByCodecName(info.getName());
        MediaFormat f = MediaFormat.createVideoFormat("video/avc", width, height);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, this.fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        inputBuffers = codec.getInputBuffers();
        outputBuffers = codec.getOutputBuffers();
        running = true;
        thread = new Thread(new Runnable() { @Override public void run() { encodeLoop(); } }, "h264-encoder");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) { try { thread.interrupt(); } catch (Exception ignored) {} thread = null; }
        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            codec = null;
        }
        inputBuffers = null; outputBuffers = null; lastQueuedUs = 0;
    }

    void queueNv21(byte[] nv21, int w, int h, long ptsUs) {
        if (!running || codec == null || nv21 == null || w != width || h != height) return;
        long interval = 1000000L / Math.max(1, fps);
        if (lastQueuedUs != 0 && ptsUs - lastQueuedUs < interval * 8 / 10) return;
        try {
            int index = codec.dequeueInputBuffer(0);
            if (index < 0) return;
            ByteBuffer in = inputBuffers[index];
            in.clear();
            int needed = width * height * 3 / 2;
            if (in.capacity() < needed) return;
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    || colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar) {
                nv21ToI420(nv21, in, width, height);
            } else {
                nv21ToNV12(nv21, in, width, height);
            }
            codec.queueInputBuffer(index, 0, needed, ptsUs, 0);
            lastQueuedUs = ptsUs;
        } catch (Exception ex) {
            if (listener != null) listener.onError("编码输入失败: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void encodeLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running && codec != null) {
            try {
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    byte[] csd0 = readBuffer(f.getByteBuffer("csd-0"));
                    byte[] csd1 = readBuffer(f.getByteBuffer("csd-1"));
                    if (listener != null) listener.onFormat(width, height, fps, csd0, csd1);
                } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                } else if (index >= 0) {
                    ByteBuffer out = outputBuffers[index];
                    if (out != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        byte[] data = new byte[info.size];
                        out.get(data);
                        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
                        if (listener != null) listener.onFrame(data, info.presentationTimeUs, key);
                    }
                    codec.releaseOutputBuffer(index, false);
                }
            } catch (Exception ex) {
                if (running && listener != null)
                    listener.onError("编码输出失败: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
    }

    private static MediaCodecInfo findEncoder() {
        int count = MediaCodecList.getCodecCount();
        for (int i = 0; i < count; i++) {
            try {
                MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes())
                    if ("video/avc".equalsIgnoreCase(type)) return info;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static int chooseColorFormat(MediaCodecInfo.CodecCapabilities caps) {
        int fallback = 0;
        for (int f : caps.colorFormats) {
            if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                    || f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar
                    || f == MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar) return f;
            if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    || f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar) fallback = f;
        }
        return fallback;
    }

    private static byte[] readBuffer(ByteBuffer b) {
        if (b == null) return null;
        ByteBuffer x = b.duplicate();
        byte[] r = new byte[x.remaining()]; x.get(r); return r;
    }

    private static void nv21ToNV12(byte[] src, ByteBuffer dst, int w, int h) {
        int y = w * h;
        dst.put(src, 0, y);
        for (int i = y; i < y + y / 2; i += 2) { dst.put(src[i + 1]); dst.put(src[i]); }
    }

    private static void nv21ToI420(byte[] src, ByteBuffer dst, int w, int h) {
        int y = w * h;
        dst.put(src, 0, y);
        for (int i = y; i < y + y / 2; i += 2) dst.put(src[i + 1]);
        for (int i = y; i < y + y / 2; i += 2) dst.put(src[i]);
    }
}
