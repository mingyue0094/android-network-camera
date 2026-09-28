package com.mingyue0094.networkcamera;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class H264Encoder {
    public interface Listener {
        void onCodecReady(String name);
        void onConfig(byte[] sps, byte[] pps);
        void onFrame(byte[] sample, long ptsUs, boolean key);
        void onError(String message);
    }

    private final Listener listener;
    private MediaCodec codec;
    private Surface inputSurface;
    private Thread outputThread;
    private volatile boolean running;

    public H264Encoder(Listener listener) {
        this.listener = listener;
    }

    public synchronized Surface start(int width, int height, int fps,
                                      int bitrateKbps, int iFrameInterval)
            throws Exception {
        stop();

        MediaCodecInfo info = findSurfaceAvcEncoder();
        if (info == null) {
            throw new IllegalStateException("没有找到支持 Surface 输入的硬件 H.264 编码器");
        }

        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);

        codec = MediaCodec.createByCodecName(info.getName());
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = codec.createInputSurface();
        codec.start();

        running = true;
        if (listener != null) {
            listener.onCodecReady(info.getName());
        }

        outputThread = new Thread(this::drainLoop, "h264-output");
        outputThread.setDaemon(true);
        outputThread.start();
        return inputSurface;
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (running) {
            try {
                int index = codec.dequeueOutputBuffer(info, 10000);

                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    readFormat(codec.getOutputFormat());
                    continue;
                }

                if (index < 0) {
                    continue;
                }

                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    byte[] data = new byte[info.size];
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    buffer.get(data);

                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        readConfig(data);
                    } else {
                        boolean key = (info.flags &
                                MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
                        byte[] avcc = annexBToAvcc(data);
                        if (avcc.length > 0 && listener != null) {
                            listener.onFrame(avcc, info.presentationTimeUs, key);
                        }
                    }
                }

                codec.releaseOutputBuffer(index, false);
            } catch (Exception e) {
                if (running && listener != null) {
                    listener.onError("H.264 输出异常: " + e.getMessage());
                }
                break;
            }
        }
    }

    private byte[] currentSps;
    private byte[] currentPps;

    private void readFormat(MediaFormat format) {
        try {
            ByteBuffer a = format.getByteBuffer("csd-0");
            ByteBuffer b = format.getByteBuffer("csd-1");
            if (a != null) {
                byte[] x = new byte[a.remaining()];
                a.duplicate().get(x);
                extractConfig(x);
            }
            if (b != null) {
                byte[] x = new byte[b.remaining()];
                b.duplicate().get(x);
                extractConfig(x);
            }
            notifyConfig();
        } catch (Exception e) {
            if (listener != null) {
                listener.onError("读取 H.264 SPS/PPS 失败: " + e.getMessage());
            }
        }
    }

    private void readConfig(byte[] data) {
        extractConfig(data);
        notifyConfig();
    }

    private void extractConfig(byte[] data) {
        List<byte[]> nals = splitAnnexB(data);
        if (!nals.isEmpty()) {
            for (byte[] nal : nals) {
                if (nal.length == 0) continue;
                int type = nal[0] & 0x1f;
                if (type == 7) currentSps = nal;
                if (type == 8) currentPps = nal;
            }
            return;
        }

        if (data.length > 4) {
            int len = ((data[0] & 255) << 24) |
                    ((data[1] & 255) << 16) |
                    ((data[2] & 255) << 8) |
                    (data[3] & 255);
            if (len > 0 && len <= data.length - 4) {
                byte[] nal = new byte[len];
                System.arraycopy(data, 4, nal, 0, len);
                int type = nal[0] & 0x1f;
                if (type == 7) currentSps = nal;
                if (type == 8) currentPps = nal;
            }
        }
    }

    private void notifyConfig() {
        if (currentSps != null && currentPps != null && listener != null) {
            listener.onConfig(currentSps.clone(), currentPps.clone());
        }
    }

    private byte[] annexBToAvcc(byte[] data) {
        List<byte[]> nals = splitAnnexB(data);
        if (nals.isEmpty()) return data;

        int size = 0;
        for (byte[] nal : nals) size += 4 + nal.length;

        ByteBuffer out = ByteBuffer.allocate(size);
        for (byte[] nal : nals) {
            out.putInt(nal.length);
            out.put(nal);
        }
        return out.array();
    }

    private static List<byte[]> splitAnnexB(byte[] data) {
        List<byte[]> result = new ArrayList<>();
        int start = -1;

        for (int i = 0; i < data.length - 3; i++) {
            int prefix = 0;
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                prefix = 3;
            } else if (data[i] == 0 && data[i + 1] == 0 &&
                    data[i + 2] == 0 && data[i + 3] == 1) {
                prefix = 4;
            }

            if (prefix == 0) continue;

            if (start >= 0) {
                int end = i;
                while (end > start && data[end - 1] == 0) end--;
                if (end > start) {
                    byte[] nal = new byte[end - start];
                    System.arraycopy(data, start, nal, 0, nal.length);
                    result.add(nal);
                }
            }

            start = i + prefix;
            i += prefix - 1;
        }

        if (start >= 0 && start < data.length) {
            int end = data.length;
            while (end > start && data[end - 1] == 0) end--;
            if (end > start) {
                byte[] nal = new byte[end - start];
                System.arraycopy(data, start, nal, 0, nal.length);
                result.add(nal);
            }
        }
        return result;
    }

    private static MediaCodecInfo findSurfaceAvcEncoder() {
        MediaCodecInfo[] infos =
                new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();

        for (MediaCodecInfo info : infos) {
            if (!info.isEncoder()) continue;

            boolean avc = false;
            for (String type : info.getSupportedTypes()) {
                if (MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) {
                    avc = true;
                    break;
                }
            }
            if (!avc) continue;

            try {
                MediaCodecInfo.CodecCapabilities caps =
                        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);

                for (int color : caps.colorFormats) {
                    if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                        return info;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public String getCodecName() {
        return codec == null ? "" : codec.getName();
    }

    public synchronized void stop() {
        running = false;

        if (outputThread != null) {
            outputThread.interrupt();
            outputThread = null;
        }

        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            codec = null;
        }

        if (inputSurface != null) {
            try { inputSurface.release(); } catch (Exception ignored) {}
            inputSurface = null;
        }

        currentSps = null;
        currentPps = null;
    }
}
