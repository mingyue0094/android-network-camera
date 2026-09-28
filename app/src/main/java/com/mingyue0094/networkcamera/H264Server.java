package com.mingyue0094.networkcamera;

import android.util.Base64;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public final class H264Server {
    public static final int PORT=8080;
    public interface ConfigHandler {
        String getStatusJson();
        String applyConfig(String resolution,int fps,float zoom,int bitrate,int keyInterval);
    }

    private final List<Client> clients=Collections.synchronizedList(new ArrayList<Client>());
    private final AtomicReference<byte[]> init=new AtomicReference<byte[]>(null);
    private volatile boolean running;
    private ServerSocket socket;
    private ConfigHandler handler;
    private String password="";
    private H264Encoder encoder;
    private Fmp4Muxer muxer;
    private volatile String encoderName="",codecString="",encoderError="";

    public void setAuthPassword(String p){password=p==null?"":p;}
    public void setConfigHandler(ConfigHandler h){handler=h;}

    public void start()throws IOException{
        if(running)return;socket=new ServerSocket(PORT);running=true;
        Thread t=new Thread(new Runnable(){public void run(){
            while(running)try{final Socket s=socket.accept();Thread c=new Thread(new Client(s),"http-client");c.setDaemon(true);c.start();}
            catch(IOException e){if(running)e.printStackTrace();}
        }},"http-accept");t.setDaemon(true);t.start();
    }

    public synchronized void startEncoder(int w,int h,int fps,int bitrate,int key)throws Exception{
        stopEncoder();muxer=new Fmp4Muxer(w,h,fps);init.set(null);encoderError="";
        encoder=new H264Encoder(new H264Encoder.Listener(){
            public void onCodecReady(String n,int f){encoderName=n;}
            public void onConfig(byte[] sps,byte[] pps){
                if(muxer==null)return;muxer.setConfig(sps,pps);
                byte[] x=muxer.getFullInitSegment();
                if(x!=null){init.set(x);codecString=avcString(sps);broadcast(x);}
            }
            public void onFrame(byte[] sample,long pts,boolean key){
                if(muxer!=null)broadcast(muxer.makeFragment(sample,pts,key));
            }
            public void onError(String e){encoderError=e;}
        });
        try{encoder.start(w,h,fps,bitrate,key);}catch(Exception e){encoder=null;encoderError=e.getMessage();throw e;}
    }

    public void offerFrame(byte[] f){if(encoder!=null)encoder.offer(f);}
    public String getEncoderName(){return encoderName;}
    public String getCodecString(){return codecString;}
    public String getEncoderError(){return encoderError;}

    public synchronized void stopEncoder(){if(encoder!=null){try{encoder.stop();}catch(Exception ignored){}encoder=null;}muxer=null;init.set(null);}
    public void stop(){
        running=false;stopEncoder();
        synchronized(clients){for(Client c:new ArrayList<Client>(clients))c.close();clients.clear();}
        try{if(socket!=null)socket.close();}catch(Exception ignored){}
    }

    private void broadcast(byte[] data){
        if(data==null||data.length==0)return;
        synchronized(clients){for(Client c:new ArrayList<Client>(clients))if(!c.send(data)){c.close();clients.remove(c);}}
    }
    private String avcString(byte[] s){
        if(s!=null&&s.length>=4)return String.format(Locale.US,"avc1.%02X%02X%02X",s[1]&255,s[2]&255,s[3]&255);
        return "avc1.42E01E";
    }

    private final class Client implements Runnable{
        final Socket socket;BufferedInputStream in;BufferedOutputStream out;boolean ws;
        Client(Socket s){socket=s;}
        public void run(){
            try{
                socket.setTcpNoDelay(true);socket.setSoTimeout(15000);
                in=new BufferedInputStream(socket.getInputStream());out=new BufferedOutputStream(socket.getOutputStream());
                byte[] hb=header();if(hb==null)return;String req=new String(hb,"UTF-8");String[] lines=req.split("\r?\n");
                if(!authorized(lines)){unauthorized();return;}
                String[] p=lines[0].split(" ");String method=p[0],path=p.length>1?p[1]:"/";
                if("OPTIONS".equalsIgnoreCase(method)){preflight();return;}
                if(path.startsWith("/video")){
                    if(!upgrade(lines))return;ws=true;clients.add(this);
                    byte[] x=init.get();if(x!=null&&!send(x)){clients.remove(this);return;}
                    while(running&&!socket.isClosed()){
                        if(in.available()>0){int op=in.read();if(op<0||((op&15)==8))break;skipFrame(op);}
                        else try{Thread.sleep(1000);}catch(InterruptedException ignored){}
                    }
                }else if(path.startsWith("/api/status")){
                    json(handler==null?"{\"ok\":false}":handler.getStatusJson());
                }else if("POST".equalsIgnoreCase(method)&&path.startsWith("/api/config")){
                    String body=body(req);json(handler==null?"{\"ok\":false}":apply(body));
                }else html();
            }catch(Exception ignored){}finally{clients.remove(this);close();}
        }

        private byte[] header()throws IOException{
            ByteArrayOutputStream b=new ByteArrayOutputStream();int st=0;
            while(b.size()<32768){int v=in.read();if(v<0)return null;b.write(v);
                if(st==0&&v=='\r')st=1;else if(st==1&&v=='\n')st=2;else if(st==2&&v=='\r')st=3;else if(st==3&&v=='\n')break;else if(v!='\r')st=0;}
            return b.toByteArray();
        }
        private String body(String req)throws IOException{
            int n=0;for(String l:req.split("\r\n"))if(l.toLowerCase(Locale.US).startsWith("content-length:"))try{n=Integer.parseInt(l.substring(15).trim());}catch(Exception ignored){}
            byte[] b=new byte[n];int p=0;while(p<n){int x=in.read(b,p,n-p);if(x<0)break;p+=x;}return new String(b,0,p,"UTF-8");
        }
        private String apply(String b){
            try{return handler.applyConfig(val(b,"resolution"),Integer.parseInt(val(b,"fps")),Float.parseFloat(val(b,"zoom")),Integer.parseInt(val(b,"bitrate")),Integer.parseInt(val(b,"keyInterval")));}
            catch(Exception e){return "{\"ok\":false,\"error\":\"invalid parameters\"}";}
        }
        private String val(String b,String k){
            String n="\""+k+"\"";int p=b.indexOf(n);if(p<0)return"";p=b.indexOf(':',p+n.length());if(p<0)return"";p++;
            while(p<b.length()&&Character.isWhitespace(b.charAt(p)))p++;
            if(p<b.length()&&b.charAt(p)=='\"'){int e=b.indexOf('\"',p+1);return e<0?"":b.substring(p+1,e);}
            int e=p;while(e<b.length()&&b.charAt(e)!=','&&b.charAt(e)!='}')e++;return b.substring(p,e).trim();
        }
        private boolean authorized(String[] lines){
            if(password.length()==0)return true;String token=null;
            for(String l:lines)if(l.regionMatches(true,0,"Authorization:",0,14)){String v=l.substring(14).trim();if(v.regionMatches(true,0,"Basic ",0,6))token=v.substring(6).trim();}
            if(token==null)return false;try{return ("admin:"+password).equals(new String(Base64.decode(token,Base64.DEFAULT),"UTF-8"));}catch(Exception e){return false;}
        }
        private boolean upgrade(String[] lines)throws Exception{
            String key=null;for(String l:lines)if(l.regionMatches(true,0,"Sec-WebSocket-Key:",0,18))key=l.substring(18).trim();
            if(key==null)return false;String a=Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")),Base64.NO_WRAP);
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+a+"\r\n\r\n").getBytes("ISO-8859-1"));out.flush();return true;
        }
        private void skipFrame(int first)throws IOException{
            int s=in.read();if(s<0)return;long n=s&127;if(n==126)n=((in.read()&255)<<8)|(in.read()&255);else if(n==127){for(int i=0;i<8;i++)in.read();n=0;}
            if((s&128)!=0)for(int i=0;i<4;i++)in.read();for(long i=0;i<n&&i<65536;i++)in.read();
        }
        boolean send(byte[] b){
            if(!ws||b==null)return false;try{synchronized(out){out.write(130);writeLen(b.length);out.write(b);out.flush();}return true;}catch(Exception e){return false;}
        }
        private void writeLen(int n)throws IOException{
            if(n<126)out.write(n);else if(n<=65535){out.write(126);out.write(n>>8);out.write(n);}else{out.write(127);long x=n&0xffffffffL;for(int i=7;i>=0;i--)out.write((int)(x>>(i*8))&255);}
        }
        private void unauthorized()throws IOException{String h="HTTP/1.0 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"Android Network Camera\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";out.write(h.getBytes("ISO-8859-1"));out.flush();}
        private void preflight()throws IOException{String h="HTTP/1.0 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type, Authorization\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";out.write(h.getBytes("ISO-8859-1"));out.flush();}
        private void json(String x)throws IOException{byte[] b=x.getBytes("UTF-8");String h="HTTP/1.0 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n";out.write(h.getBytes("ISO-8859-1"));out.write(b);out.flush();}
        private void html()throws IOException{byte[] b=HTML.getBytes("UTF-8");String h="HTTP/1.0 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n";out.write(h.getBytes("ISO-8859-1"));out.write(b);out.flush();}
        void close(){try{socket.close();}catch(Exception ignored){}}
    }

private static final String HTML="<!doctype html><html><head><meta charset=\'utf-8\'><meta name=\'viewport\' content=\'width=device-width,initial-scale=1\'><title>Android H.264 网络摄像头</title><style>body{font-family:Arial;background:#111;color:#eee;margin:0;padding:16px}main{max-width:1100px;margin:auto}video{width:100%;background:#000;display:block}label{display:inline-block;margin:10px 12px 10px 0}select,button{font-size:16px;padding:8px}#msg{margin:12px 0;white-space:pre-wrap}</style></head><body><main><h2>Android H.264 网络摄像头</h2><video id=\'video\' autoplay muted playsinline controls></video><div id=\'msg\'>连接中...</div><label>分辨率 <select id=\'resolution\'><option>640x480</option><option>800x600</option><option>1280x720</option><option>1280x960</option><option>1920x1080</option></select></label><label>FPS <select id=\'fps\'><option>5</option><option>10</option><option>15</option><option>20</option><option>24</option><option>25</option><option>30</option></select></label><label>Zoom <select id=\'zoom\'><option>1</option><option>1.5</option><option>2</option><option>3</option><option>4</option><option>6</option><option>8</option></select></label><label>码率 <select id=\'bitrate\'><option>1000</option><option>2000</option><option>4000</option><option>6000</option><option>8000</option><option>10000</option></select> kbps</label><label>关键帧 <select id=\'keyInterval\'><option>1</option><option>2</option><option>3</option></select> 秒</label><button onclick=\'applyConfig()\'>应用设置</button></main><script>let video=document.getElementById(\'video\'),ms,sb,q=[],codec=\'avc1.42E01E\';function drain(){if(!sb||sb.updating||!q.length)return;try{sb.appendBuffer(q.shift())}catch(e){q=[];document.getElementById(\'msg\').textContent=\'MSE错误：\'+e}}function add(x){if(!sb||sb.updating){q.push(x);return}try{sb.appendBuffer(x)}catch(e){q=[];document.getElementById(\'msg\').textContent=\'MSE错误：\'+e}}async function load(){try{let j=await(await fetch(\'/api/status\')).json();codec=j.codecString||codec;for(let k of [\'resolution\',\'fps\',\'zoom\',\'bitrate\',\'keyInterval\'])if(j[k]!=null)document.getElementById(k).value=j[k];document.getElementById(\'msg\').textContent=\'编码器：\'+(j.encoder||\'等待\')+\' | \'+j.resolution+\' / \'+j.fps+\' FPS / \'+j.bitrate+\' kbps\'}catch(e){document.getElementById(\'msg\').textContent=\'状态读取失败：\'+e}}function start(){ms=new MediaSource();video.src=URL.createObjectURL(ms);ms.addEventListener(\'sourceopen\',function(){let ws=new WebSocket((location.protocol===\'https:\'?\'wss://\':\'ws://\')+location.host+\'/video\');ws.binaryType=\'arraybuffer\';ws.onopen=()=>document.getElementById(\'msg\').textContent=\'WebSocket已连接\';ws.onclose=()=>setTimeout(start,1000);ws.onmessage=e=>{if(!sb){try{let mime=\'video/mp4; codecs=\\"\'+codec+\'\\"\';if(!MediaSource.isTypeSupported(mime))throw new Error(\'浏览器不支持 \'+mime);sb=ms.addSourceBuffer(mime);sb.addEventListener(\'updateend\',drain)}catch(x){document.getElementById(\'msg\').textContent=\'MSE错误：\'+x;return}}add(new Uint8Array(e.data))}})}async function applyConfig(){let b={resolution:resolution.value,fps:+fps.value,zoom:+zoom.value,bitrate:+bitrate.value,keyInterval:+keyInterval.value};document.getElementById(\'msg\').textContent=\'正在应用...\';try{let j=await(await fetch(\'/api/config\',{method:\'POST\',headers:{\'Content-Type\':\'application/json\'},body:JSON.stringify(b)})).json();if(j.ok)location.reload();else document.getElementById(\'msg\').textContent=\'失败：\'+j.error}catch(e){document.getElementById(\'msg\').textContent=\'请求失败：\'+e}}load().then(start);</script></body></html>";
}
