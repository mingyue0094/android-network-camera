package com.mingyue0094.networkcamera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView preview; private TextView status; private SurfaceHolder holder;
    private H264Encoder encoder; private LegacyH264Encoder legacyEncoder;
    private Camera2Controller camera; private LegacyCameraController legacyCamera;
    private Fmp4Server server; private Fmp4Muxer muxer; private RtspServer rtsp;
    private float zoom=1f; private int ev=0;
    private int width=1920,height=1080,fps=30,bitrate=12000;
    private static final int PERM=100;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b); getWindow().setFlags(1024,1024);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        preview=new SurfaceView(this); root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout p=new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setPadding(10,4,10,4); p.setBackgroundColor(0xcc000000);
        status=new TextView(this); status.setTextColor(Color.WHITE); p.addView(status);
        LinearLayout r=new LinearLayout(this);
        Button a=new Button(this);a.setText("变焦-"); Button z=new Button(this);z.setText("变焦+");
        Button f=new Button(this);f.setText("自动对焦"); Button e1=new Button(this);e1.setText("亮度-"); Button e2=new Button(this);e2.setText("亮度+");
        r.addView(a);r.addView(z);r.addView(f);r.addView(e1);r.addView(e2);p.addView(r);root.addView(p);setContentView(root);
        holder=preview.getHolder(); holder.addCallback(this);
        a.setOnClickListener(v->{zoom=Math.max(1,zoom-.25f);applyZoom();show(null);});
        z.setOnClickListener(v->{zoom+=.25f;applyZoom();show(null);});
        f.setOnClickListener(v->{if(camera!=null)camera.setAutoFocus();if(legacyCamera!=null)legacyCamera.setAutoFocus();});
        e1.setOnClickListener(v->{ev--;applyExposure();show(null);}); e2.setOnClickListener(v->{ev++;applyExposure();show(null);});
        if(android.os.Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA},PERM);
    }
    @Override public void surfaceCreated(SurfaceHolder h){start();}
    private void start(){
        stop();
        try{server=new Fmp4Server();server.start();rtsp=new RtspServer();rtsp.start();if(android.os.Build.VERSION.SDK_INT>=21)startCamera2();else startLegacy();}
        catch(Exception e){show("启动失败: "+e.getMessage());}
    }
    private void startCamera2()throws Exception{
        encoder=new H264Encoder(new H264Encoder.Listener(){
            public void onCodecReady(String n){show("硬件H.264: "+n);}
            public void onConfig(byte[] s,byte[] p){muxer=new Fmp4Muxer(width,height,fps);muxer.setConfig(s,p);server.setCodec(codecString(s));server.setInitSegment(muxer.getFullInitSegment());if(rtsp!=null)rtsp.onConfig(s,p);}
            public void onFrame(byte[] d,long t,boolean k){if(muxer!=null&&server!=null)server.publish(muxer.makeFragment(d,t,k));if(rtsp!=null)rtsp.onFrame(d,t,k);}
            public void onError(String m){show(m);}
        });
        Surface es=encoder.start(width,height,fps,bitrate,1);
        camera=new Camera2Controller(this,new Camera2Controller.Listener(){
            public void onCameraReady(String id){if(rtsp!=null)rtsp.setVideoFormat(width,height,fps);show("Camera2: "+id);}
            public void onError(String m){show(m);}
        });
        camera.start(holder.getSurface(),es);
    }
    private void startLegacy()throws Exception{
        legacyCamera=new LegacyCameraController(new LegacyCameraController.Listener(){
            public void onCameraReady(String id,int w,int h,int f){width=w;height=h;fps=f;if(rtsp!=null)rtsp.setVideoFormat(w,h,f);show(id+" "+w+"x"+h+" @"+f+"fps");}
            public void onFrame(byte[] d,long t){if(legacyEncoder!=null)legacyEncoder.encodeNv21(d,t);}
            public void onError(String m){show(m);}
        });
        legacyEncoder=new LegacyH264Encoder(new LegacyH264Encoder.Listener(){
            public void onCodecReady(String n){show("旧设备H.264: "+n);}
            public void onConfig(byte[] s,byte[] p){muxer=new Fmp4Muxer(width,height,fps);muxer.setConfig(s,p);server.setInitSegment(muxer.getFullInitSegment());if(rtsp!=null)rtsp.onConfig(s,p);}
            public void onFrame(byte[] d,long t,boolean k){if(muxer!=null&&server!=null)server.publish(muxer.makeFragment(d,t,k));if(rtsp!=null)rtsp.onFrame(d,t,k);}
            public void onError(String m){show(m);}
        });
        legacyEncoder.start(width,height,fps,bitrate,1);
        legacyCamera.start();
    }
    private String codecString(byte[] s){\n        if(s!=null&&s.length>=4)return String.format(java.util.Locale.US,"avc1.%02x%02x%02x",s[1]&255,s[2]&255,s[3]&255);\n        return "avc1.42001e";\n    }\n    private void applyZoom(){if(camera!=null)camera.setZoom(zoom);if(legacyCamera!=null)legacyCamera.setZoom(zoom);}
    private void applyExposure(){if(camera!=null)camera.setExposure(ev);if(legacyCamera!=null)legacyCamera.setExposure(ev);}
    private void show(String x){
        runOnUiThread(()->{String ip=NetworkUtil.getWifiIp(this);
            String mode=android.os.Build.VERSION.SDK_INT>=21?"Camera2 → Surface":"Legacy Camera → YUV";
            String s=mode+" → H.264 → fMP4 + RTSP\nPC/YOLO: http://"+ip+":8080/video.mp4\nRTSP: rtsp://"+ip+":8554/camera\n"+width+"x"+height+"  "+fps+" FPS  "+bitrate+" Kbps\nZoom "+String.format(java.util.Locale.US,"%.2fx",zoom)+"  EV "+ev;
            if(encoder!=null)s+="\nEncoder: "+encoder.getCodecName(); if(legacyEncoder!=null)s+="\nEncoder: legacy MediaCodec";
            if(x!=null)s+="\n"+x; status.setText(s);});
    }
    private void stop(){
        if(camera!=null){camera.stop();camera=null;} if(legacyCamera!=null){legacyCamera.stop();legacyCamera=null;}
        if(encoder!=null){encoder.stop();encoder=null;} if(legacyEncoder!=null){legacyEncoder.stop();legacyEncoder=null;}
        if(server!=null){server.stop();server=null;} if(rtsp!=null){rtsp.stop();rtsp=null;} muxer=null;
    }
    @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int hh){}
    @Override public void surfaceDestroyed(SurfaceHolder h){stop();}
    @Override protected void onDestroy(){stop();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==PERM&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED&&holder.getSurface().isValid())start();}
}
