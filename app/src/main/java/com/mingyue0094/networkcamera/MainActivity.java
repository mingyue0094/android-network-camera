package com.mingyue0094.networkcamera;

import android.app.Activity;
import android.content.*;
import android.graphics.ImageFormat;
import android.net.ConnectivityManager;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView surfaceView;
    private TextView status;
    private SurfaceHolder holder;
    private Camera camera;
    private MjpegServer server;
    private H264Encoder encoder;
    private Fmp4Muxer muxer;
    private SharedPreferences prefs;
    private int targetFps=15,targetBitrate=4000000;
    private float targetZoom=1f;
    private BroadcastReceiver wifiReceiver;
    private String cameraError="";
    private final Object frameLock=new Object();
    private byte[] latestNv21;
    private int latestW,latestH;
    private volatile boolean feedRunning;
    private Thread feedThread;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs=getSharedPreferences("camera",MODE_PRIVATE);
        setContentView(createView()); holder=surfaceView.getHolder();holder.addCallback(this);
        server=new MjpegServer();
        server.setAuthPassword(prefs.getString("web_password",""));
        server.setConfigHandler(new MjpegServer.ConfigHandler(){
            public String getStatusJson(){return getWebStatusJson();}
            public String applyConfig(String r,int f,float z,int br){return applyWebConfig(r,f,z,br);}
        });
        try{server.start();status.setText("H.264 网络摄像头启动中...");}
        catch(IOException e){status.setText("HTTP 8080 启动失败: "+e.getMessage());}
        registerWifiReceiver();updateNetworkStatus();
    }

    private View createView(){
        FrameLayout root=new FrameLayout(this);
        surfaceView=new SurfaceView(this);root.addView(surfaceView,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(20,20,20,20);panel.setBackgroundColor(0xaa000000);
        status=new TextView(this);status.setTextColor(0xffffffff);status.setTextSize(16);panel.addView(status);
        LinearLayout row=new LinearLayout(this);
        final Spinner resolution=new Spinner(this);
        String[] rs={"640x480","800x600","1280x720","1280x960","1920x1080"};
        ArrayAdapter<String> ra=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,rs);ra.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);resolution.setAdapter(ra);resolution.setSelection(prefs.getInt("resolution",2));
        final Spinner fps=new Spinner(this);String[] fs={"5 FPS","10 FPS","15 FPS","20 FPS","24 FPS","25 FPS","30 FPS"};
        ArrayAdapter<String> fa=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,fs);fa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);fps.setAdapter(fa);fps.setSelection(savedFpsIndex(prefs.getInt("fps",15)));
        final Spinner bitrate=new Spinner(this);String[] bs={"1 Mbps","2 Mbps","4 Mbps","6 Mbps","8 Mbps","10 Mbps"};
        ArrayAdapter<String> ba=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,bs);ba.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);bitrate.setAdapter(ba);bitrate.setSelection(savedBitrateIndex(prefs.getInt("bitrate",4000000)));
        final Spinner zoom=new Spinner(this);String[] zs={"1x","1.5x","2x","3x","4x","6x","8x"};
        ArrayAdapter<String> za=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,zs);za.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);zoom.setAdapter(za);zoom.setSelection(savedZoomIndex(prefs.getFloat("zoom",1f)));
        Button apply=new Button(this);apply.setText("应用");row.addView(resolution);row.addView(fps);row.addView(bitrate);row.addView(zoom);row.addView(apply);panel.addView(row);
        LinearLayout authRow=new LinearLayout(this);final EditText password=new EditText(this);password.setHint("网页访问密码（留空关闭密码）");password.setSingleLine(true);password.setInputType(0x81);password.setText(prefs.getString("web_password",""));Button authApply=new Button(this);authApply.setText("保存密码");authRow.addView(password,new LinearLayout.LayoutParams(0,-2,1));authRow.addView(authApply);panel.addView(authRow);
        FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(-2,-2);pp.leftMargin=15;pp.topMargin=15;root.addView(panel,pp);
        authApply.setOnClickListener(new View.OnClickListener(){public void onClick(View v){String p=password.getText().toString();prefs.edit().putString("web_password",p).apply();if(server!=null)server.setAuthPassword(p);Toast.makeText(MainActivity.this,p.length()==0?"网页密码已关闭":"网页密码已保存，用户名：admin",Toast.LENGTH_SHORT).show();}});
        apply.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            int ri=resolution.getSelectedItemPosition();int[] w={640,800,1280,1280,1920},h={480,600,720,960,1080};int[] f={5,10,15,20,24,25,30};int[] br={1000000,2000000,4000000,6000000,8000000,10000000};float[] z={1,1.5f,2,3,4,6,8};
            targetFps=f[fps.getSelectedItemPosition()];targetBitrate=br[bitrate.getSelectedItemPosition()];targetZoom=z[zoom.getSelectedItemPosition()];
            prefs.edit().putInt("resolution",ri).putInt("fps",targetFps).putInt("bitrate",targetBitrate).putFloat("zoom",targetZoom).apply();
            if(holder!=null&&holder.getSurface()!=null&&holder.getSurface().isValid())restartCamera(w[ri],h[ri],targetFps,targetZoom);else status.setText("摄像头预览界面尚未就绪，请稍后再点应用");
        }});
        return root;
    }

    private int savedFpsIndex(int x){int[]v={5,10,15,20,24,25,30};for(int i=0;i<v.length;i++)if(v[i]==x)return i;return 2;}
    private int savedBitrateIndex(int x){int[]v={1000000,2000000,4000000,6000000,8000000,10000000};for(int i=0;i<v.length;i++)if(v[i]==x)return i;return 2;}
    private int savedZoomIndex(float x){float[]v={1,1.5f,2,3,4,6,8};for(int i=0;i<v.length;i++)if(Math.abs(v[i]-x)<.01f)return i;return 0;}

    private String getWebStatusJson(){
        Camera.Parameters p=camera==null?null:camera.getParameters();String res="unknown";int fps=targetFps;float zoom=targetZoom;
        if(p!=null){Camera.Size s=p.getPreviewSize();if(s!=null)res=s.width+"x"+s.height;try{int[]r=new int[2];p.getPreviewFpsRange(r);fps=(r[1]+500)/1000;}catch(Exception e){}try{if(p.isZoomSupported()){List<Integer>r=p.getZoomRatios();int i=p.getZoom();if(r!=null&&i>=0&&i<r.size())zoom=r.get(i)/100f;}}catch(Exception e){}}
        return "{\"ok\":"+ (camera!=null)+",\"resolution\":\""+res+"\",\"fps\":"+fps+",\"zoom\":"+zoom+",\"bitrate\":"+targetBitrate+",\"encoder\":\"H.264 hardware\"}";
    }
    private String applyWebConfig(String r,int f,float z,int br){
        try{String[]p=r.split("x");if(p.length!=2||f<1||f>60||z<1||z>20||br<100000||br>50000000)throw new IllegalArgumentException();
            int w=Integer.parseInt(p[0]),h=Integer.parseInt(p[1]);targetFps=f;targetZoom=z;targetBitrate=br;
            prefs.edit().putInt("resolution",resolutionIndex(w,h)).putInt("fps",f).putFloat("zoom",z).putInt("bitrate",br).apply();
            restartCamera(w,h,f,z);if(camera==null)return "{\"ok\":false,\"error\":\""+jsonEscape(cameraError)+"\"}";return getWebStatusJson();
        }catch(Exception e){return "{\"ok\":false,\"error\":\"invalid parameters\"}";}
    }
    private int resolutionIndex(int w,int h){if(w==640&&h==480)return 0;if(w==800&&h==600)return 1;if(w==1280&&h==720)return 2;if(w==1280&&h==960)return 3;return 4;}

    private void registerWifiReceiver(){
        wifiReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){updateNetworkStatus();}};
        IntentFilter f=new IntentFilter();f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);f.addAction("android.net.wifi.STATE_CHANGE");f.addAction("android.net.wifi.WIFI_STATE_CHANGED");registerReceiver(wifiReceiver,f);
    }
    private void updateNetworkStatus(){
        String ip=NetworkUtil.getWifiIp(this);
        String s=(ip==null||"0.0.0.0".equals(ip))?"HTTP服务: 8080 OK\nWiFi未连接":"HTTP服务: 8080 OK\nhttp://"+ip+":8080/";
        if(camera==null)status.setText(s+"\n摄像头: "+(cameraError.length()==0?"等待启动":cameraError));
    }

    @Override public void surfaceCreated(SurfaceHolder h){startCamera();}
    private void startCamera(){
        int ri=prefs.getInt("resolution",2);int[]w={640,800,1280,1280,1920},hh={480,600,720,960,1080};
        targetFps=prefs.getInt("fps",15);targetBitrate=prefs.getInt("bitrate",4000000);targetZoom=prefs.getFloat("zoom",1);
        restartCamera(w[ri],hh[ri],targetFps,targetZoom);
    }

    private void restartCamera(int wantedW,int wantedH,int fps,float zoom){
        releaseCamera();cameraError="";String ip=NetworkUtil.getWifiIp(this);String net=(ip==null||"0.0.0.0".equals(ip))?"HTTP服务: 8080 OK\nWiFi未连接":"HTTP服务: 8080 OK\nhttp://"+ip+":8080/";status.setText(net+"\n摄像头: 正在启动...");
        try{camera=Camera.open();}catch(Exception e){cameraError="Camera.open 失败: "+formatException(e);status.setText(net+"\n摄像头: "+cameraError);return;}
        try{
            Camera.Parameters p=camera.getParameters();Camera.Size s=chooseClosestSize(p.getSupportedPreviewSizes(),wantedW,wantedH);if(s==null)throw new IOException("没有可用的预览分辨率");
            p.setPreviewSize(s.width,s.height);p.setPreviewFormat(ImageFormat.NV21);setFps(p,fps);setZoom(p,zoom);camera.setParameters(p);camera.setPreviewDisplay(holder);camera.setDisplayOrientation(0);
            final Camera.Size encSize=camera.getParameters().getPreviewSize();
            startH264(encSize.width,encSize.height,fps,targetBitrate);
            camera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback(){public void onPreviewFrame(byte[]d,Camera c){
                if(d==null)return;synchronized(frameLock){latestNv21=d.clone();latestW=encSize.width;latestH=encSize.height;frameLock.notifyAll();}try{c.addCallbackBuffer(d);}catch(Exception e){}
            }});
            int bs=encSize.width*encSize.height*3/2;camera.addCallbackBuffer(new byte[bs]);camera.addCallbackBuffer(new byte[bs]);camera.addCallbackBuffer(new byte[bs]);camera.startPreview();
            Camera.Size actual=camera.getParameters().getPreviewSize();status.setText(net+"\n摄像头: OK\n"+actual.width+"x"+actual.height+"  "+getFpsText(camera.getParameters())+"  "+getZoomText(camera.getParameters())+"\nH.264: "+(targetBitrate/1000000)+" Mbps");
        }catch(Exception e){cameraError="摄像头启动失败: "+formatException(e);status.setText(net+"\n摄像头: "+cameraError);releaseCamera();}
    }

    private void startH264(final int w,final int h,final int fps,final int bitrate)throws Exception{
        stopH264();muxer=new Fmp4Muxer();encoder=new H264Encoder();
        encoder.start(w,h,fps,bitrate,new H264Encoder.Listener(){
            public void onFormat(int ww,int hh,int ff,byte[]a,byte[]b){muxer.setFormat(ww,hh,ff,a,b);server.setInitSegment(muxer.getInit(),"video/mp4; codecs=\"avc1.42E01E\"");}
            public void onFrame(byte[]d,long pts,boolean key){byte[]f=muxer.fragment(d,key);if(f!=null)server.publishFragment(f);}
            public void onError(String m){cameraError=m;runOnUiThread(new Runnable(){public void run(){status.setText(status.getText()+"\n"+cameraError);}});}
        });
        feedRunning=true;feedThread=new Thread(new Runnable(){public void run(){while(feedRunning){byte[]d=null;int ww,hh;synchronized(frameLock){while(feedRunning&&latestNv21==null)try{frameLock.wait(200);}catch(Exception e){}if(!feedRunning)break;d=latestNv21;ww=latestW;hh=latestH;latestNv21=null;}if(d!=null)encoder.queueNv21(d,ww,hh,System.nanoTime()/1000);}}},"h264-feed");feedThread.setDaemon(true);feedThread.start();
    }
    private void stopH264(){feedRunning=false;synchronized(frameLock){latestNv21=null;frameLock.notifyAll();}if(feedThread!=null){try{feedThread.interrupt();}catch(Exception e){}feedThread=null;}if(encoder!=null){encoder.stop();encoder=null;}muxer=null;}
    private void releaseCamera(){stopH264();if(camera!=null){try{camera.setPreviewCallback(null);}catch(Exception e){}try{camera.stopPreview();}catch(Exception e){}try{camera.release();}catch(Exception e){}camera=null;}}

    private void setFps(Camera.Parameters p,int wanted){try{List<int[]>rs=p.getSupportedPreviewFpsRange();if(rs==null||rs.isEmpty())return;int t=wanted*1000;int[]best=rs.get(0);int bd=Integer.MAX_VALUE;for(int[]r:rs){int d=Math.abs((r[0]+r[1])/2-t);if(d<bd){bd=d;best=r;}}p.setPreviewFpsRange(best[0],best[1]);}catch(Exception e){}}
    private void setZoom(Camera.Parameters p,float wanted){try{if(!p.isZoomSupported())return;List<Integer>r=p.getZoomRatios();int max=p.getMaxZoom();if(r==null||r.isEmpty())return;int bi=0,bd=Integer.MAX_VALUE,t=Math.round(wanted*100);for(int i=0;i<r.size();i++){int d=Math.abs(r.get(i)-t);if(d<bd){bd=d;bi=i;}}if(bi>max)bi=max;p.setZoom(bi);}catch(Exception e){}}
    private String getFpsText(Camera.Parameters p){try{int[]r=new int[2];p.getPreviewFpsRange(r);return ((r[1]+500)/1000)+" FPS";}catch(Exception e){return targetFps+" FPS";}}
    private String getZoomText(Camera.Parameters p){try{if(!p.isZoomSupported())return "Zoom不支持";List<Integer>r=p.getZoomRatios();int i=p.getZoom();if(r!=null&&i>=0&&i<r.size())return String.format(java.util.Locale.US,"%.1fx",r.get(i)/100f);}catch(Exception e){}return targetZoom+"x";}
    private Camera.Size chooseClosestSize(List<Camera.Size>s,int w,int h){if(s==null||s.isEmpty())return null;Camera.Size best=s.get(0);long bs=Long.MAX_VALUE;for(Camera.Size x:s){long sc=Math.abs(x.width-w)*1000L+Math.abs(x.height-h);if(sc<bs){bs=sc;best=x;}}return best;}
    private String formatException(Exception e){String m=e.getMessage();return e.getClass().getSimpleName()+": "+(m==null?e.toString():m);}
    private String jsonEscape(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}

    @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int hh){}
    @Override public void surfaceDestroyed(SurfaceHolder h){releaseCamera();}
    @Override protected void onDestroy(){if(wifiReceiver!=null){try{unregisterReceiver(wifiReceiver);}catch(Exception e){}wifiReceiver=null;}releaseCamera();if(server!=null){server.stop();server=null;}super.onDestroy();}
}
