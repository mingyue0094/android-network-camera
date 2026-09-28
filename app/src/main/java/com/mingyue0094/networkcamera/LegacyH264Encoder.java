package com.mingyue0094.networkcamera;

import android.media.*;
import java.nio.ByteBuffer;

public final class LegacyH264Encoder {
    public interface Listener {void onCodecReady(String name);void onConfig(byte[] sps,byte[] pps);void onFrame(byte[] sample,long ptsUs,boolean key);void onError(String msg);}
    private final Listener listener;private MediaCodec codec;private Thread thread;private volatile boolean running;private int colorFormat,width,height;
    public LegacyH264Encoder(Listener l){listener=l;}
    public void start(int w,int h,int fps,int kbps,int iframe)throws Exception{
        stop();width=w;height=h;MediaCodecInfo info=find();
        if(info==null)throw new IllegalStateException("没有支持YUV输入的H.264编码器");
        MediaFormat f=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,w,h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT,colorFormat);f.setInteger(MediaFormat.KEY_BIT_RATE,kbps*1000);f.setInteger(MediaFormat.KEY_FRAME_RATE,fps);f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,iframe);
        codec=MediaCodec.createByCodecName(info.getName());codec.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();running=true;
        if(listener!=null)listener.onCodecReady(info.getName());
        thread=new Thread(this::drain,"legacy-h264-output");thread.setDaemon(true);thread.start();
    }
    public void encodeNv21(byte[] src,long pts){
        if(!running||codec==null)return;
        try{int i=codec.dequeueInputBuffer(5000);if(i<0)return;ByteBuffer b=codec.getInputBuffer(i);if(b==null)return;b.clear();
            if(colorFormat==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)nv21ToNv12(src,b);else nv21ToI420(src,b);
            codec.queueInputBuffer(i,0,width*height*3/2,pts,0);
        }catch(Exception e){if(listener!=null)listener.onError("旧设备编码输入异常: "+e.getMessage());}
    }
    private void drain(){MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();while(running)try{
        int i=codec.dequeueOutputBuffer(bi,10000);
        if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=codec.getOutputFormat();ByteBuffer a=f.getByteBuffer("csd-0"),b=f.getByteBuffer("csd-1");if(a!=null&&b!=null&&listener!=null)listener.onConfig(read(a),read(b));continue;}
        if(i<0)continue;ByteBuffer b=codec.getOutputBuffer(i);
        if(b!=null&&bi.size>0){b.position(bi.offset);b.limit(bi.offset+bi.size);byte[] d=new byte[bi.size];b.get(d);if((bi.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0)parseConfig(d);else if(listener!=null)listener.onFrame(toAvcc(d),bi.presentationTimeUs,(bi.flags&MediaCodec.BUFFER_FLAG_SYNC_FRAME)!=0);}
        codec.releaseOutputBuffer(i,false);
    }catch(Exception e){if(running&&listener!=null)listener.onError("旧设备编码输出异常: "+e.getMessage());break;}}
    private void parseConfig(byte[] d){byte[] s=null,p=null;int last=0;for(int i=0;i+3<d.length;i++){int n=0;if(d[i]==0&&d[i+1]==0&&d[i+2]==1)n=3;else if(d[i]==0&&d[i+1]==0&&d[i+2]==0&&d[i+3]==1)n=4;if(n>0){if(i>last){byte[] x=new byte[i-last];System.arraycopy(d,last,x,0,x.length);if(x.length>0){if((x[0]&31)==7)s=x;if((x[0]&31)==8)p=x;}}last=i+n;i+=n-1;}}if(last<d.length){byte[] x=new byte[d.length-last];System.arraycopy(d,last,x,0,x.length);if((x.length>0)&&((x[0]&31)==7))s=x;if((x.length>0)&&((x[0]&31)==8))p=x;}if(s!=null&&p!=null&&listener!=null)listener.onConfig(s,p);}
    private static MediaCodecInfo find(){for(MediaCodecInfo i:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos())if(i.isEncoder())try{MediaCodecInfo.CodecCapabilities c=i.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);for(int f:c.colorFormats)if(f==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar||f==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar){/* selected below */return select(i,c);}}catch(Exception ignored){}return null;}
    private static MediaCodecInfo select(MediaCodecInfo i,MediaCodecInfo.CodecCapabilities c){return i;}
    private void setColor(int f){colorFormat=f;}
    private static byte[] read(ByteBuffer b){ByteBuffer x=b.duplicate();byte[] d=new byte[x.remaining()];x.get(d);return d;}
    private static void nv21ToNv12(byte[] s,ByteBuffer d){int y=s.length*2/3;d.put(s,0,y);for(int i=y;i+1<s.length;i+=2){d.put(s[i+1]);d.put(s[i]);}}
    private static void nv21ToI420(byte[] s,ByteBuffer d){int y=s.length*2/3;d.put(s,0,y);int uv=y;int q=y+(s.length-y)/2;for(int i=0;i<s.length-y;i+=2)d.put(s[uv+i+1]);for(int i=0;i<s.length-y;i+=2)d.put(s[uv+i]);}
    private static byte[] toAvcc(byte[] d){if(d.length<4)return d;int p=(d[0]==0&&d[1]==0&&d[2]==1)?3:((d[0]==0&&d[1]==0&&d[2]==0&&d[3]==1)?4:0);if(p==0)return d;ByteBuffer o=ByteBuffer.allocate(d.length+16);while(p<d.length){int n=d.length;for(int i=p;i+3<d.length;i++)if((d[i]==0&&d[i+1]==0&&d[i+2]==1)||(d[i]==0&&d[i+1]==0&&d[i+2]==0&&d[i+3]==1)){n=i;break;}while(n>p&&d[n-1]==0)n--;o.putInt(n-p);o.put(d,p,n-p);if(n==d.length)break;p=(d[n+2]==1)?n+3:n+4;}byte[] r=new byte[o.position()];o.flip();o.get(r);return r;}
    public synchronized void stop(){running=false;if(thread!=null){thread.interrupt();thread=null;}if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}codec=null;}}
}
