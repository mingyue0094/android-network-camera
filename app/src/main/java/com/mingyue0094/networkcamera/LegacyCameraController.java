package com.mingyue0094.networkcamera;

import android.graphics.ImageFormat;
import android.hardware.Camera;
import java.util.List;

public final class LegacyCameraController {
    public interface Listener {
        void onCameraReady(String id, int width, int height, int fps);
        void onFrame(byte[] nv21, long ptsUs);
        void onError(String msg);
    }
    private final Listener listener;
    private Camera camera;
    private int width, height, fps = 30;
    public LegacyCameraController(Listener l) { listener=l; }
    public void start() {
        try {
            camera=Camera.open();
            Camera.Parameters p=camera.getParameters();
            Camera.Size size=chooseSize(p.getSupportedPreviewSizes(),1920,1080);
            width=size.width; height=size.height;
            p.setPreviewSize(width,height);
            p.setPreviewFormat(ImageFormat.NV21);
            int[] rate=chooseFps(p.getSupportedPreviewFpsRange(),30);
            if(rate!=null){p.setPreviewFpsRange(rate[0],rate[1]);fps=Math.max(1,Math.round(rate[1]/1000f));}
            try{p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);}catch(Exception ignored){}
            camera.setParameters(p);
            int n=width*height*ImageFormat.getBitsPerPixel(ImageFormat.NV21)/8;
            for(int i=0;i<3;i++)camera.addCallbackBuffer(new byte[n]);
            camera.setPreviewCallbackWithBuffer((data,c)->{if(data!=null&&listener!=null){listener.onFrame(data,System.nanoTime()/1000L);c.addCallbackBuffer(data);}});
            camera.startPreview();
            if(listener!=null)listener.onCameraReady("legacy Camera API",width,height,fps);
        }catch(Exception e){stop();if(listener!=null)listener.onError("旧 Camera 启动失败: "+e.getMessage());}
    }
    private static Camera.Size chooseSize(List<Camera.Size> sizes,int mw,int mh){
        Camera.Size best=null;long score=Long.MAX_VALUE;
        for(Camera.Size s:sizes){if(s.width>mw||s.height>mh)continue;long d=Math.abs((long)s.width*s.height-(long)mw*mh);if(best==null||d<score){best=s;score=d;}}
        return best!=null?best:sizes.get(0);
    }
    private static int[] chooseFps(List<int[]> rs,int wanted){
        int[] best=null;int d0=Integer.MAX_VALUE;
        for(int[] r:rs){int d=Math.abs((r[0]+r[1])/2-wanted*1000);if(d<d0){d0=d;best=r;}}
        return best;
    }
    public void setZoom(float z){if(camera==null)return;try{Camera.Parameters p=camera.getParameters();if(!p.isZoomSupported())return;int max=p.getMaxZoom();int v=Math.max(0,Math.min(max,Math.round((z-1f)*max/4f)));p.setZoom(v);camera.setParameters(p);}catch(Exception ignored){}}
    public void setAutoFocus(){if(camera!=null)try{camera.autoFocus((ok,c)->{});}catch(Exception ignored){}}
    public void setExposure(int ev){if(camera==null)return;try{Camera.Parameters p=camera.getParameters();int lo=p.getMinExposureCompensation(),hi=p.getMaxExposureCompensation();p.setExposureCompensation(Math.max(lo,Math.min(hi,ev)));camera.setParameters(p);}catch(Exception ignored){}}
    public void stop(){if(camera!=null){try{camera.setPreviewCallbackWithBuffer(null);}catch(Exception ignored){}try{camera.stopPreview();}catch(Exception ignored){}try{camera.release();}catch(Exception ignored){}camera=null;}}
}
