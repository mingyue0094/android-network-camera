package com.mingyue0094.networkcamera;

import java.io.BufferedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public final class Fmp4Server {
    private final Object lock=new Object();
    private final ArrayList<Client> clients=new ArrayList<>();
    private volatile boolean running;
    private volatile byte[] init;
    private ServerSocket server;

    public void start() throws Exception {
        if(running)return;
        server=new ServerSocket(8080);
        running=true;
        Thread t=new Thread(()->{
            while(running)try{
                Socket s=server.accept();
                s.setTcpNoDelay(true);
                Client c=new Client(s);
                synchronized(lock){clients.add(c);}
                c.start();
            }catch(Exception e){if(!running)break;}
        },"fmp4-http");
        t.setDaemon(true);t.start();
    }
    public void setInitSegment(byte[] b){if(b!=null)init=b.clone();}
    public void publish(byte[] b){
        if(b==null||b.length==0)return;
        synchronized(lock){
            for(Client c:new ArrayList<>(clients))if(!c.offer(b))c.close();
        }
    }
    private void remove(Client c){synchronized(lock){clients.remove(c);}}
    public void stop(){
        running=false;
        try{server.close();}catch(Exception ignored){}
        synchronized(lock){for(Client c:new ArrayList<>(clients))c.close();clients.clear();}
    }
    private final class Client implements Runnable{
        final Socket socket;
        final BlockingQueue<byte[]> q=new ArrayBlockingQueue<>(12);
        volatile boolean alive=true;
        Thread thread;
        Client(Socket s){socket=s;}
        void start(){thread=new Thread(this,"fmp4-client");thread.setDaemon(true);thread.start();}
        boolean offer(byte[] b){
            if(!alive)return false;
            byte[] x=b.clone();
            if(q.offer(x))return true;
            q.poll();
            return q.offer(x);
        }
        public void run(){
            try{
                BufferedOutputStream out=new BufferedOutputStream(socket.getOutputStream(),65536);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nCache-Control: no-cache, no-store\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\nTransfer-Encoding: chunked\r\n\r\n").getBytes("US-ASCII"));
                out.flush();
                byte[] x=init;if(x!=null)chunk(out,x);
                while(alive&&running)chunk(out,q.take());
            }catch(Exception ignored){}finally{close();remove(this);}
        }
        void chunk(BufferedOutputStream out,byte[] b)throws Exception{
            out.write(Integer.toHexString(b.length).getBytes("US-ASCII"));out.write('\r');out.write('\n');
            out.write(b);out.write('\r');out.write('\n');out.flush();
        }
        void close(){if(!alive)return;alive=false;try{socket.close();}catch(Exception ignored){}q.clear();}
    }
}