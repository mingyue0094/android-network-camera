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

final class Fmp4Muxer {
    private int width,height,fps=30,timescale=90000,sequence=1;
    private long decodeTime;
    private byte[] sps,pps;
    private String codec="avc1.42E01E";
    private byte[] init;
    void setFormat(int w,int h,int f,byte[] a,byte[] b){
        width=w;height=h;fps=Math.max(1,f);sps=strip(a);pps=strip(b);
        if(sps!=null&&sps.length>=4)codec=String.format(java.util.Locale.US,"avc1.%02X%02X%02X",sps[1]&255,sps[2]&255,sps[3]&255);
        init=init();decodeTime=0;sequence=1;
    }
    byte[] getInit(){return init;}
    byte[] fragment(byte[] annex,boolean key){
        if(init==null)return null;byte[] sample=sample(annex);if(sample==null)return null;
        int dur=Math.max(1,timescale/Math.max(1,fps));byte[] moof=moof(sequence++,decodeTime,dur,sample.length,key);
        decodeTime+=dur;return cat(moof,box("mdat",sample));
    }
    private byte[] init(){
        byte[] stbl=box("stbl",stsd(),box("stts",u32(0)),box("stsc",u32(0)),
                box("stsz",u32(0),u32(0)),box("stco",u32(0)));
        byte[] minf=box("minf",vmhd(),box("dinf",
                box("dref",cat(u32(1),box("url ",u32(1))))),stbl);
        byte[] mdia=box("mdia",mdhd(),hdlr(),minf);
        byte[] trak=box("trak",tkhd(),mdia);
        byte[] moov=box("moov",mvhd(),trak,
                box("mvex",box("trex",cat(u32(0),u32(1),u32(1),u32(0),u32(0)))));
        return box("ftyp",str("isom"),u32(0x200),str("isom"),str("iso6"),
                str("avc1"),str("mp41"),moov);
    }
    private byte[] mvhd(){return cat(u32(0),u32(0),u32(0),u32(timescale),u32(0),u32(0x00010000),u16(0x0100),u16(0),u32(0),u32(0),matrix(),u32(0),u32(0),u32(0),u32(0),u32(0),u32(0),u32(2));}
    private byte[] tkhd(){return cat(u32(7),u32(0),u32(0),u32(1),u32(0),u32(0),u32(0),u16(0),u16(0),u16(0),u16(0),matrix(),u32(width<<16),u32(height<<16));}
    private byte[] mdhd(){return cat(u32(0),u32(0),u32(0),u32(timescale),u32(0),u16(0x55c4),u16(0));}
    private byte[] hdlr(){return cat(u32(0),u32(0),str("vide"),u32(0),u32(0),u32(0),str("VideoHandler"),new byte[]{0});}
    private byte[] vmhd(){return cat(u32(1),u16(0),u16(0),u16(0),u16(0));}
    private byte[] stsd(){
        byte[] avc1=cat(new byte[6],u16(1),u16(0),u16(0),u32(0),u32(0),u32(0),u16(width),u16(height),
            u32(0x00480000),u32(0x00480000),u32(0),u16(1),new byte[32],u16(0x18),u16(0xffff),
            box("avcC",avcC()),box("btrt",cat(u32(0),u32(4000000),u32(4000000))));
        return cat(u32(0),u32(1),box("avc1",avc1));
    }
    private byte[] avcC(){
        byte[]s=sps==null?new byte[0]:sps,p=pps==null?new byte[0]:pps;ByteArrayOutputStream o=new ByteArrayOutputStream();
        o.write(1);o.write(s.length>3?s[1]&255:0x42);o.write(s.length>3?s[2]&255:0);o.write(s.length>3?s[3]&255:0x1e);o.write(0xff);o.write(0xe1);put(o,u16(s.length));put(o,s);o.write(1);put(o,u16(p.length));put(o,p);return o.toByteArray();
    }
    private byte[] moof(int seq,long time,int dur,int size,boolean key){
        byte[]tr=trun(dur,size,key,0);int moofSize=8+box("mfhd",cat(u32(0),u32(seq))).length+box("traf",box("tfhd",cat(u32(0x020000),u32(1))),box("tfdt",cat(u32(0x01000000),u64(time))),box("trun",tr)).length;
        tr=trun(dur,size,key,moofSize+8);
        return box("moof",box("mfhd",cat(u32(0),u32(seq))),box("traf",box("tfhd",cat(u32(0x020000),u32(1))),box("tfdt",cat(u32(0x01000000),u64(time))),box("trun",tr)));
    }
    private byte[] trun(int d,int s,boolean k,int off){return cat(u32(0x000001|0x000100|0x000200|0x000400),u32(1),u32(off),u32(d),u32(s),u32(k?0x02000000:0x01010000));}
    private byte[] sample(byte[] b){
        if(b==null)return null;ByteArrayOutputStream o=new ByteArrayOutputStream(b.length+64);int p=0,n=0;
        while(true){int st=start(b,p);if(st<0)break;int sc=st+(b[st+2]==1?3:4);int nx=start(b,sc);int end=nx<0?b.length:nx;if(end>sc&&(b[sc]&31)!=7&&(b[sc]&31)!=8){int len=end-sc;put(o,u32(len));o.write(b,sc,len);n++;}
            if(nx<0)break;p=nx;}
        return n==0?b:o.toByteArray();
    }
    private int start(byte[]b,int p){for(int i=Math.max(0,p);i+3<b.length;i++){if(b[i]==0&&b[i+1]==0&&b[i+2]==1)return i;if(i+4<=b.length&&b[i]==0&&b[i+1]==0&&b[i+2]==0&&b[i+3]==1)return i;}return -1;}
    private static byte[] strip(byte[]b){if(b==null)return null;int n=b.length>=4&&b[0]==0&&b[1]==0&&b[2]==0&&b[3]==1?4:(b.length>=3&&b[0]==0&&b[1]==0&&b[2]==1?3:0);byte[]r=new byte[b.length-n];System.arraycopy(b,n,r,0,r.length);return r;}
    private static byte[] box(String t,byte[]...ps){int n=8;for(byte[]p:ps)if(p!=null)n+=p.length;ByteArrayOutputStream o=new ByteArrayOutputStream(n);put(o,u32(n));put(o,str(t));for(byte[]p:ps)put(o,p);return o.toByteArray();}
    private static byte[]cat(byte[]...aa){int n=0;for(byte[]a:aa)if(a!=null)n+=a.length;byte[]r=new byte[n];int p=0;for(byte[]a:aa)if(a!=null){System.arraycopy(a,0,r,p,a.length);p+=a.length;}return r;}
    private static byte[]str(String s){try{return s.getBytes("ISO-8859-1");}catch(Exception e){return s.getBytes();}}
    private static byte[]u16(int v){return new byte[]{(byte)(v>>>8),(byte)v};}
    private static byte[]u32(long v){return new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v};}
    private static byte[]u64(long v){return new byte[]{(byte)(v>>>56),(byte)(v>>>48),(byte)(v>>>40),(byte)(v>>>32),(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v};}
    private static byte[]matrix(){return cat(u32(0x00010000),u32(0),u32(0),u32(0),u32(0x00010000),u32(0),u32(0),u32(0),u32(0x40000000));}
    private static void put(ByteArrayOutputStream o,byte[]b){if(b!=null)o.write(b,0,b.length);}
}