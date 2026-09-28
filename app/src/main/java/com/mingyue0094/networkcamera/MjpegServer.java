package com.mingyue0094.networkcamera;

import android.util.Base64;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

public class MjpegServer {
    public static final int PORT=8080;
    public interface ConfigHandler {
        String getStatusJson();
        String applyConfig(String resolution,int fps,float zoom,int bitrate);
    }
    private volatile boolean running;
    private ServerSocket ss;
    private volatile ConfigHandler handler;
    private volatile String password="";
    private volatile byte[] init;
    private volatile String mime="video/mp4; codecs=\"avc1.42E01E\"";
    private final List<Client> clients=Collections.synchronizedList(new ArrayList<Client>());

    public void setAuthPassword(String p){password=p==null?"":p;}
    public void setConfigHandler(ConfigHandler h){handler=h;}
    public void setStreamMime(String m){if(m!=null)mime=m;}
    public void start() throws IOException{
        if(running)return;
        ss=new ServerSocket(PORT); running=true;
        Thread t=new Thread(new Runnable(){public void run(){
            while(running)try{
                final Socket s=ss.accept();
                Thread c=new Thread(new Client(s),"http-client"); c.setDaemon(true); c.start();
            }catch(IOException e){if(running)e.printStackTrace();}
        }},"http-accept"); t.setDaemon(true); t.start();
    }
    public void stop(){
        running=false; try{if(ss!=null)ss.close();}catch(Exception e){}
        synchronized(clients){for(Client c:new ArrayList<Client>(clients))c.close();clients.clear();}
    }
    public void setInitSegment(byte[] b,String m){init=b;if(m!=null)mime=m;broadcast(b);}
    public void publishFragment(byte[] b){if(b!=null)broadcast(b);}
    private void broadcast(byte[] b){
        if(b==null)return;
        synchronized(clients){for(Client c:new ArrayList<Client>(clients))if(!c.offer(b)){c.close();clients.remove(c);}}
    }
    private class Client implements Runnable{
        final Socket socket; OutputStream out; boolean websocket;
        Client(Socket s){socket=s;}
        public void run(){
            try{
                socket.setSoTimeout(15000);
                InputStream in=new BufferedInputStream(socket.getInputStream());
                out=new BufferedOutputStream(socket.getOutputStream());
                String req=read(in); if(req==null)return;
                String[] ls=req.split("\\r?\\n"); String[] a=ls[0].split(" ");
                String method=a.length>0?a[0]:"GET", path=a.length>1?a[1]:"/";
                if(!authorized(ls)){unauthorized();return;}
                if("OPTIONS".equals(method)){cors();return;}
                if("GET".equals(method)&&"/video".equals(path)&&isWs(ls)){upgrade(ls);return;}
                if("/api/status".equals(path)){json(handler==null?"{\"ok\":false}":handler.getStatusJson());return;}
                if("POST".equals(method)&&path.startsWith("/api/config")){
                    String body=req.substring(req.indexOf("\r\n\r\n")+4);
                    json(handler==null?"{\"ok\":false,\"error\":\"not ready\"}":apply(body));return;
                }
                html();
            }catch(Exception e){}finally{if(!websocket)close();}
        }
        private String apply(String b){
            try{
                String r=value(b,"resolution"); int f=Integer.parseInt(value(b,"fps"));
                float z=Float.parseFloat(value(b,"zoom")); int br=Integer.parseInt(value(b,"bitrate"));
                return handler.applyConfig(r,f,z,br);
            }catch(Exception e){return "{\"ok\":false,\"error\":\"invalid parameters\"}";}
        }
        private String value(String b,String k){
            int p=b.indexOf("\""+k+"\""); if(p<0)return "";
            p=b.indexOf(':',p)+1; while(p<b.length()&&Character.isWhitespace(b.charAt(p)))p++;
            if(p<b.length()&&b.charAt(p)=='"'){int e=b.indexOf('"',p+1);return e<0?"":b.substring(p+1,e);}
            int e=p;while(e<b.length()&&b.charAt(e)!=','&&b.charAt(e)!='}')e++;return b.substring(p,e).trim();
        }
        private boolean authorized(String[] ls){
            if(password.length()==0)return true; String token=null;
            for(String l:ls)if(l.regionMatches(true,0,"Authorization:",0,14)){
                String v=l.substring(14).trim();if(v.regionMatches(true,0,"Basic ",0,6))token=v.substring(6).trim();
            }
            if(token==null)return false;
            try{return ("admin:"+password).equals(new String(Base64.decode(token,Base64.DEFAULT),"UTF-8"));}catch(Exception e){return false;}
        }
        private boolean isWs(String[] ls){for(String l:ls)if(l.toLowerCase().startsWith("upgrade:")&&l.toLowerCase().contains("websocket"))return true;return false;}
        private void upgrade(String[] ls)throws Exception{
            String key=null;for(String l:ls)if(l.regionMatches(true,0,"Sec-WebSocket-Key:",0,18)){key=l.substring(18).trim();break;}
            if(key==null)throw new IOException();
            String ac=Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")),Base64.NO_WRAP);
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+ac+"\r\nAccess-Control-Allow-Origin: *\r\n\r\n").getBytes("ISO-8859-1"));out.flush();
            websocket=true; clients.add(this); startWriter(); byte[] i=init;if(i!=null)offer(i);
            while(running&&!socket.isClosed())Thread.sleep(1000);
        }
        private String read(InputStream in)throws IOException{
            ByteArrayOutputStream b=new ByteArrayOutputStream();int state=0;
            while(b.size()<16384){int v=in.read();if(v<0)return null;b.write(v);
                if(state==0&&v=='\r')state=1;else if(state==1&&v=='\n')state=2;else if(state==2&&v=='\r')state=3;else if(state==3&&v=='\n')break;else if(v!='\r')state=0;}
            return b.toString("UTF-8");
        }
        private void unauthorized()throws IOException{out.write(("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"Android Network Camera\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));out.flush();}
        private void cors()throws IOException{out.write(("HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type, Authorization\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));out.flush();}
        private void json(String s)throws IOException{byte[]b=s.getBytes("UTF-8");out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));out.write(b);out.flush();}
        private void html()throws IOException{byte[]b=HTML.getBytes("UTF-8");out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));out.write(b);out.flush();}
        private final List<byte[]> q=new ArrayList<byte[]>(); private volatile boolean open=true;
        boolean offer(byte[]b){synchronized(q){if(!open||q.size()>6)return false;q.add(b);q.notifyAll();return true;}}
        void startWriter(){Thread wt=new Thread(new Runnable(){public void run(){while(open){byte[]b=null;synchronized(q){while(open&&q.isEmpty())try{q.wait(1000);}catch(Exception e){}if(!open)break;b=q.remove(0);}try{frame(b);}catch(Exception e){close();}}}},"ws-writer");wt.setDaemon(true);wt.start();}
        private void frame(byte[]b)throws IOException{synchronized(out){int n=b.length;out.write(0x82);if(n<126)out.write(n);else if(n<=65535){out.write(126);out.write(n>>>8);out.write(n);}else{out.write(127);for(int i=7;i>=0;i--)out.write((int)(n>>>(i*8)));}out.write(b);out.flush();}}
        void close(){open=false;clients.remove(this);synchronized(q){q.clear();q.notifyAll();}try{socket.close();}catch(Exception e){}}
    }
    private final String HTML=
        "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>Android 网络摄像头</title>"+
        "<style>body{font-family:Arial;background:#111;color:#eee;margin:0;padding:16px}main{max-width:1100px;margin:auto}video{width:100%;background:#000;display:block;max-height:75vh}select,button{font-size:16px;padding:8px;margin:6px}#msg{margin:10px}</style><main>"+
        "<h2>Android 网络摄像头 · H.264 硬件编码</h2><video id=v autoplay muted playsinline></video><div id=msg>连接中...</div>"+
        "<div>分辨率<select id=r><option>640x480</option><option>800x600</option><option>1280x720</option><option>1280x960</option><option>1920x1080</option></select>"+
        " FPS<select id=f><option>5</option><option>10</option><option>15</option><option>20</option><option>24</option><option>25</option><option>30</option></select>"+
        " 码率<select id=b><option value=1000000>1 Mbps</option><option value=2000000>2 Mbps</option><option value=4000000>4 Mbps</option><option value=6000000>6 Mbps</option><option value=8000000>8 Mbps</option><option value=10000000>10 Mbps</option></select>"+
        " Zoom<select id=z><option>1</option><option>1.5</option><option>2</option><option>3</option><option>4</option><option>6</option><option>8</option></select><button onclick=apply()>应用</button></div></main>"+
        "<script>let v=document.getElementById('v'),m=document.getElementById('msg'),ms,sb,q=[],ws;function pump(){if(!sb||sb.updating||!q.length)return;try{sb.appendBuffer(q.shift())}catch(e){q=[];m.textContent='MSE错误：'+e.message}}"+
        "function start(){if(!window.MediaSource){m.textContent='浏览器不支持 MSE';return}ms=new MediaSource;v.src=URL.createObjectURL(ms);ms.addEventListener('sourceopen',function(){let x='video/mp4; codecs=\"avc1.42E01E\"';if(!MediaSource.isTypeSupported(x)){m.textContent='浏览器不支持 H.264/fMP4';return}sb=ms.addSourceBuffer(x);sb.addEventListener('updateend',pump);ws=new WebSocket('ws://'+location.host+'/video');ws.binaryType='arraybuffer';ws.onopen=()=>m.textContent='视频已连接';ws.onclose=()=>m.textContent='视频断开';ws.onmessage=e=>{q.push(e.data);pump()}})}"+
        "async function load(){try{let j=await(await fetch('/api/status')).json();r.value=j.resolution;f.value=j.fps;b.value=j.bitrate;z.value=j.zoom;m.textContent='当前 '+j.resolution+' / '+j.fps+' FPS / '+(j.bitrate/1000000)+' Mbps'}catch(e){}}async function apply(){m.textContent='正在应用...';try{let j=await(await fetch('/api/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({resolution:r.value,fps:+f.value,bitrate:+b.value,zoom:+z.value})})).json();m.textContent=j.ok?'已应用 '+j.resolution+' / '+j.fps+' FPS / '+(j.bitrate/1000000)+' Mbps':'失败：'+j.error}catch(e){m.textContent='请求失败：'+e.message}}start();load();</script>";
}