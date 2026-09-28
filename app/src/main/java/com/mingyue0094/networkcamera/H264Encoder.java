package com.mingyue0094.networkcamera;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class H264Encoder {
    public interface Listener {
        void onCodecReady(String name, int colorFormat);
        void onConfig(byte[] sps, byte[] pps);
        void onFrame(byte[] sample, long ptsUs, boolean key);
        void onError(String message);
    }

    private final Object lock=new Object();
    private final Listener listener;
    private MediaCodec codec;
    private ByteBuffer[] inputs, outputs;
    private Thread thread;
    private volatile boolean running;
    private byte[] latest;
    private int width,height,fps,colorFormat;
    private String codecName="";
    private long frameIndex;
    private byte[] sps,pps;

    public H264Encoder(Listener l){listener=l;}

    public void start(int w,int h,int f,int bitrateKbps,int iframeSeconds)throws Exception{
        stop();
        width=w;height=h;fps=Math.max(1,f);
        MediaCodecInfo info=findEncoder();
        if(info==null)throw new Exception("没有找到 H.264 硬件编码器");
        codecName=info.getName();
        MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType("video/avc");
        colorFormat=chooseFormat(caps.colorFormats);
        if(colorFormat==0)throw new Exception("H.264编码器没有YUV420输入格式");
        MediaFormat mf=MediaFormat.createVideoFormat("video/avc",w,h);
        mf.setInteger(MediaFormat.KEY_COLOR_FORMAT,colorFormat);
        mf.setInteger(MediaFormat.KEY_BIT_RATE,Math.max(128000,bitrateKbps*1000));
        mf.setInteger(MediaFormat.KEY_FRAME_RATE,fps);
        mf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,Math.max(1,iframeSeconds));
        codec=MediaCodec.createByCodecName(codecName);
        codec.configure(mf,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        inputs=codec.getInputBuffers();
        outputs=codec.getOutputBuffers();
        frameIndex=0;sps=null;pps=null;running=true;
        if(listener!=null)listener.onCodecReady(codecName,colorFormat);
        thread=new Thread(new Runnable(){public void run(){loop();}},"h264-encoder");
        thread.setDaemon(true);thread.start();
    }

    public void offer(byte[] nv21){
        if(!running||nv21==null)return;
        synchronized(lock){latest=nv21.clone();lock.notifyAll();}
    }

    private void loop(){
        final long interval=1000000L/Math.max(1,fps);
        while(running){
            byte[] frame;
            synchronized(lock){
                while(running&&latest==null)try{lock.wait(200);}catch(InterruptedException ignored){}
                if(!running)return;
                frame=latest;latest=null;
            }
            try{
                int i=codec.dequeueInputBuffer(10000);
                if(i>=0){
                    ByteBuffer b=inputs[i];b.clear();
                    int need=width*height*3/2;
                    if(b.capacity()<need)throw new Exception("编码器输入缓冲区不足");
                    putYuv420(b,frame);
                    codec.queueInputBuffer(i,0,need,frameIndex*interval,0);
                    frameIndex++;
                }
                drain();
            }catch(Exception e){
                if(listener!=null)listener.onError("H.264编码失败: "+e.getClass().getSimpleName()+": "+e.getMessage());
                running=false;
            }
        }
    }

    private void drain()throws Exception{
        MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();
        while(running){
            int i=codec.dequeueOutputBuffer(bi,0);
            if(i==MediaCodec.INFO_TRY_AGAIN_LATER)return;
            if(i==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED){outputs=codec.getOutputBuffers();continue;}
            if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                MediaFormat f=codec.getOutputFormat();
                byte[] a=csd(f,"csd-0"),b=csd(f,"csd-1");
                if(a!=null&&b!=null)setConfig(a,b);
                continue;
            }
            if(i<0)continue;
            ByteBuffer out=outputs[i];
            if(bi.size>0){
                byte[] raw=new byte[bi.size];
                int pos=out.position(),lim=out.limit();
                out.position(bi.offset);out.limit(bi.offset+bi.size);out.get(raw);
                out.position(pos);out.limit(lim);
                if((bi.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0)extractConfig(raw);
                else if(listener!=null)listener.onFrame(toAvcc(raw),bi.presentationTimeUs,
                        (bi.flags&MediaCodec.BUFFER_FLAG_SYNC_FRAME)!=0);
            }
            codec.releaseOutputBuffer(i,false);
        }
    }

    private byte[] csd(MediaFormat f,String key){
        try{
            ByteBuffer b=f.getByteBuffer(key);if(b==null)return null;
            byte[] v=new byte[b.remaining()];b.duplicate().get(v);
            List<byte[]> nals=split(v);
            if(nals.size()==1)return nals.get(0);
            for(byte[] n:nals)if(n.length>0&&((n[0]&31)==("csd-0".equals(key)?7:8)))return n;
        }catch(Exception ignored){}
        return null;
    }

    private void setConfig(byte[] a,byte[] b){
        a=strip(a);b=strip(b);
        if(a!=null&&(a[0]&31)==7)sps=a;
        if(b!=null&&(b[0]&31)==8)pps=b;
        if(sps!=null&&pps!=null&&listener!=null)listener.onConfig(sps.clone(),pps.clone());
    }

    private void extractConfig(byte[] raw){
        for(byte[] n:split(raw)){
            if(n.length==0)continue;
            int t=n[0]&31;if(t==7)sps=n;else if(t==8)pps=n;
        }
        if(sps!=null&&pps!=null&&listener!=null)listener.onConfig(sps.clone(),pps.clone());
    }

    private byte[] toAvcc(byte[] raw){
        List<byte[]> ns=split(raw);
        if(ns.isEmpty())return raw;
        int n=0;for(byte[] x:ns)n+=4+x.length;
        ByteBuffer b=ByteBuffer.allocate(n);
        for(byte[] x:ns){b.putInt(x.length);b.put(x);}
        return b.array();
    }

    private List<byte[]> split(byte[] d){
        ArrayList<byte[]> r=new ArrayList<byte[]>();int start=-1;
        for(int i=0;i+3<d.length;i++){
            int sc=0;
            if(d[i]==0&&d[i+1]==0&&d[i+2]==1)sc=3;
            else if(i+4<=d.length&&d[i]==0&&d[i+1]==0&&d[i+2]==0&&d[i+3]==1)sc=4;
            if(sc>0){
                if(start>=0){int e=i;while(e>start&&d[e-1]==0)e--;r.add(copy(d,start,e-start));}
                start=i+sc;i+=sc-1;
            }
        }
        if(start>=0&&start<d.length){int e=d.length;while(e>start&&d[e-1]==0)e--;r.add(copy(d,start,e-start));}
        return r;
    }

    private byte[] copy(byte[] d,int p,int n){byte[] x=new byte[n];System.arraycopy(d,p,x,0,n);return x;}
    private byte[] strip(byte[] x){
        if(x==null)return null;int p=0;
        if(x.length>=4&&x[0]==0&&x[1]==0&&x[2]==0&&x[3]==1)p=4;
        else if(x.length>=3&&x[0]==0&&x[1]==0&&x[2]==1)p=3;
        if(p==0)return x;return copy(x,p,x.length-p);
    }

    private void putYuv420(ByteBuffer dst,byte[] nv21){
        int y=width*height;dst.put(nv21,0,y);
        if(colorFormat==21||colorFormat==39||colorFormat==2141391872){
            for(int i=y;i<y+y/2;i+=2){dst.put(nv21[i+1]);dst.put(nv21[i]);}
        }else{
            int c=y/4;
            for(int i=0;i<c;i++)dst.put(nv21[y+i*2+1]);
            for(int i=0;i<c;i++)dst.put(nv21[y+i*2]);
        }
    }

    private int chooseFormat(int[] fs){
        int[] p={21,39,19,20,2141391872};
        for(int x:p)for(int f:fs)if(f==x)return x;return 0;
    }

    private MediaCodecInfo findEncoder(){
        for(int i=0;i<MediaCodecList.getCodecCount();i++){
            MediaCodecInfo x=MediaCodecList.getCodecInfoAt(i);
            if(!x.isEncoder())continue;
            boolean avc=false;
            for(String t:x.getSupportedTypes())if("video/avc".equalsIgnoreCase(t)){avc=true;break;}
            if(!avc)continue;
            String n=x.getName();
            if(!n.startsWith("OMX.google.") && !n.startsWith("c2.android."))return x;
        }
        return null;
    }

    public String getCodecName(){return codecName;}

    public void stop(){
        running=false;synchronized(lock){latest=null;lock.notifyAll();}
        if(thread!=null){try{thread.interrupt();}catch(Exception ignored){}thread=null;}
        if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}codec=null;}
        inputs=null;outputs=null;
    }
}
