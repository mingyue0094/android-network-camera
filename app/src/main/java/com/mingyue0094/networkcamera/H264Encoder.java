package com.mingyue0094.networkcamera;

import android.media.*;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class H264Encoder {
    public interface Listener{void onCodecReady(String name);void onConfig(byte[] sps,byte[] pps);void onFrame(byte[] sample,long ptsUs,boolean key);void onError(String msg);}
    private final Listener listener;private MediaCodec codec;private Surface surface;private Thread thread;private volatile boolean running;private byte[] sps,pps;
    public H264Encoder(Listener l){listener=l;}
    public synchronized Surface start(int w,int h,int fps,int kbps,int iframe)throws Exception{
        stop();MediaCodecInfo info=find();
        if(info==null)throw new IllegalStateException("没有支持Surface输入的硬件H.264编码器");
        MediaFormat f=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,w,h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE,kbps*1000);f.setInteger(MediaFormat.KEY_FRAME_RATE,fps);f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,iframe);
        codec=MediaCodec.createByCodecName(info.getName());codec.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);surface=codec.createInputSurface();codec.start();running=true;
        if(listener!=null)listener.onCodecReady(info.getName());thread=new Thread(this::drain,"h264-output");thread.setDaemon(true);thread.start();return surface;
    }
    private void drain(){MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();while(running)try{
        int i=codec.dequeueOutputBuffer(bi,10000);
        if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){format(codec.getOutputFormat());continue;}
        if(i<0)continue;ByteBuffer b=codec.getOutputBuffer(i);
        if(b!=null&&bi.size>0){b.position(bi.offset);b.limit(bi.offset+bi.size);byte[] d=new byte[bi.size];b.get(d);
            if((bi.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0){config(d);}else{boolean key=(bi.flags&MediaCodec.BUFFER_FLAG_SYNC_FRAME)!=0;if(listener!=null)listener.onFrame(toAvcc(d),bi.presentationTimeUs,key);}}
        codec.releaseOutputBuffer(i,false);
    }catch(Exception e){if(running&&listener!=null)listener.onError("H.264输出异常: "+e.getMessage());break;}}
    private void format(MediaFormat f){try{ByteBuffer a=f.getByteBuffer("csd-0"),b=f.getByteBuffer("csd-1");if(a!=null)config(read(a));if(b!=null)config(read(b));notifyConfig();}catch(Exception e){if(listener!=null)listener.onError("SPS/PPS读取失败: "+e.getMessage());}}
    private void config(byte[] d){for(byte[] n:split(d)){if(n.length==0)continue;int t=n[0]&31;if(t==7)sps=n;if(t==8)pps=n;}notifyConfig();}
    private void notifyConfig(){if(sps!=null&&pps!=null&&listener!=null)listener.onConfig(sps.clone(),pps.clone());}
    private static byte[] read(ByteBuffer b){ByteBuffer x=b.duplicate();byte[] d=new byte[x.remaining()];x.get(d);return d;}
    private static byte[] toAvcc(byte[] d){List<byte[]> n=split(d);if(n.isEmpty())return d;int z=0;for(byte[] x:n)z+=4+x.length;ByteBuffer b=ByteBuffer.allocate(z);for(byte[] x:n){b.putInt(x.length);b.put(x);}return b.array();}
    private static List<byte[]> split(byte[] d){List<byte[]> r=new ArrayList<>();int s=-1;for(int i=0;i<d.length-3;i++){int p=0;if(d[i]==0&&d[i+1]==0&&d[i+2]==1)p=3;else if(d[i]==0&&d[i+1]==0&&d[i+2]==0&&d[i+3]==1)p=4;if(p==0)continue;if(s>=0){int e=i;while(e>s&&d[e-1]==0)e--;if(e>s){byte[] n=new byte[e-s];System.arraycopy(d,s,n,0,n.length);r.add(n);}}s=i+p;i+=p-1;}if(s>=0&&s<d.length){int e=d.length;while(e>s&&d[e-1]==0)e--;if(e>s){byte[] n=new byte[e-s];System.arraycopy(d,s,n,0,n.length);r.add(n);}}return r;}
    private static MediaCodecInfo find(){for(MediaCodecInfo i:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()){if(!i.isEncoder())continue;try{MediaCodecInfo.CodecCapabilities c=i.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);for(int x:c.colorFormats)if(x==MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)return i;}catch(Exception ignored){}}return null;}
    public String getCodecName(){return codec==null?"":codec.getName();}
    public synchronized void stop(){running=false;if(thread!=null){thread.interrupt();thread=null;}if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}codec=null;}if(surface!=null){try{surface.release();}catch(Exception ignored){}surface=null;}sps=pps=null;}
}