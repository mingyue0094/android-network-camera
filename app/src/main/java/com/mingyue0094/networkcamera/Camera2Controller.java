package com.mingyue0094.networkcamera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.camera2.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.util.Arrays;

public final class Camera2Controller {
    public interface Listener{void onCameraReady(String id);void onError(String msg);}
    private final Context context;private final Listener listener;private final CameraManager manager;
    private HandlerThread thread;private Handler handler;private CameraDevice camera;private CameraCaptureSession session;
    private CameraCharacteristics characteristics;private Surface preview,encoder;private float zoom=1f;private int ev;
    private boolean manualFocus;private float focusDistance;

    public Camera2Controller(Context c,Listener l){context=c.getApplicationContext();listener=l;manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);}
    public void start(Surface p,Surface e){
        preview=p;encoder=e;thread=new HandlerThread("camera2");thread.start();handler=new Handler(thread.getLooper());
        try{String id=backCamera();characteristics=manager.getCameraCharacteristics(id);open(id);}catch(Exception ex){error("Camera2启动失败: "+ex.getMessage());}
    }
    private String backCamera()throws CameraAccessException{
        for(String id:manager.getCameraIdList()){Integer f=manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);if(f!=null&&f==CameraCharacteristics.LENS_FACING_BACK)return id;}
        throw new IllegalStateException("没有后置摄像头");
    }
    @SuppressLint("MissingPermission") private void open(String id)throws CameraAccessException{
        if(android.os.Build.VERSION.SDK_INT>=23&&context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){error("没有CAMERA权限");return;}
        manager.openCamera(id,new CameraDevice.StateCallback(){
            public void onOpened(CameraDevice d){camera=d;session();}
            public void onDisconnected(CameraDevice d){d.close();camera=null;}
            public void onError(CameraDevice d,int e){d.close();camera=null;error("Camera2错误: "+e);}
        },handler);
    }
    private void session(){
        try{camera.createCaptureSession(Arrays.asList(preview,encoder),new CameraCaptureSession.StateCallback(){
            public void onConfigured(CameraCaptureSession s){session=s;try{apply();if(listener!=null)listener.onCameraReady(camera.getId());}catch(Exception ex){error(ex.getMessage());}}
            public void onConfigureFailed(CameraCaptureSession s){error("Camera2 Session配置失败");}
        },handler);}catch(Exception ex){error("创建Session失败: "+ex.getMessage());}
    }
    private void apply()throws CameraAccessException{
        if(camera==null||session==null)return;
        CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        b.addTarget(preview);b.addTarget(encoder);
        b.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,ev);
        if(manualFocus){b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);b.set(CaptureRequest.LENS_FOCUS_DISTANCE,focusDistance);}
        else b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        Rect r=crop();if(r!=null)b.set(CaptureRequest.SCALER_CROP_REGION,r);
        session.setRepeatingRequest(b.build(),null,handler);
    }
    private Rect crop(){
        if(characteristics==null)return null;Rect s=characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);if(s==null)return null;
        Float m=characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);float z=Math.max(1,zoom);if(m!=null)z=Math.min(z,m);
        int w=(int)(s.width()/z),h=(int)(s.height()/z),l=s.centerX()-w/2,t=s.centerY()-h/2;return new Rect(l,t,l+w,t+h);
    }
    public float getMaxZoom(){if(characteristics==null)return 1f;Float z=characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);return z==null?1f:z;}
    public void setZoom(float z){zoom=Math.max(1,Math.min(z,getMaxZoom()));applyAsync();}
    public void setExposure(int x){android.util.Range<Integer> r=characteristics==null?null:characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);ev=r==null?x:Math.max(r.getLower(),Math.min(x,r.getUpper()));applyAsync();}
    public void setAutoFocus(){manualFocus=false;applyAsync();}
    public void setManualFocus(float d){focusDistance=Math.max(0,d);manualFocus=true;applyAsync();}
    private void applyAsync(){if(handler!=null)handler.post(()->{try{apply();}catch(Exception ex){error("更新Camera2失败: "+ex.getMessage());}});}
    private void error(String s){if(listener!=null)listener.onError(s==null?"unknown":s);}
    public void stop(){try{if(session!=null)session.close();}catch(Exception ignored){}session=null;try{if(camera!=null)camera.close();}catch(Exception ignored){}camera=null;if(thread!=null){thread.quitSafely();thread=null;handler=null;}}
}