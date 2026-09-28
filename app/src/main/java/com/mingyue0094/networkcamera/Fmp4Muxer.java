package com.mingyue0094.networkcamera;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class Fmp4Muxer {
    private byte[] sps,pps;
    private final int width,height,fps;
    private long sequence=1;

    public Fmp4Muxer(int w,int h,int f){width=w;height=h;fps=Math.max(1,f);}
    public synchronized void setConfig(byte[] a,byte[] b){sps=a.clone();pps=b.clone();}
    public synchronized byte[] getFullInitSegment(){
        if(sps==null||pps==null)return null;
        try{return concat(box("ftyp",ftyp()),box("moov",moov()));}catch(Exception e){return null;}
    }
    public synchronized byte[] makeFragment(byte[] sample,long ptsUs,boolean key){
        if(sample==null||sample.length==0)return null;
        try{
            long time=ptsUs*90000L/1000000L;
            long dur=Math.max(1,90000L/fps);
            byte[] moof=makeMoof(sequence++,time,dur,sample.length,key);
            return concat(moof,box("mdat",sample));
        }catch(Exception e){return null;}
    }

    private byte[] ftyp()throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
        d.writeBytes("isom");d.writeInt(0x200);d.writeBytes("isom");d.writeBytes("iso6");d.writeBytes("avc1");d.writeBytes("mp41");
        return b.toByteArray();
    }
    private byte[] moov()throws IOException{return concatBox("moov",mvhd(),trak(),mvex());}
    private byte[] mvhd()throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
        d.writeInt(0);d.writeInt(0);d.writeInt(0);d.writeInt(90000);d.writeInt(0);d.writeInt(0x10000);
        d.writeShort(0x100);d.writeShort(0);d.writeLong(0);d.writeLong(0);
        d.writeInt(0x10000);d.writeInt(0);d.writeInt(0);d.writeInt(0);d.writeInt(0x10000);d.writeInt(0);
        d.writeInt(0);d.writeInt(0);d.writeInt(0x40000000);for(int i=0;i<6;i++)d.writeInt(0);d.writeInt(2);
        return box("mvhd",b.toByteArray());
    }
    private byte[] trak()throws IOException{return concatBox("trak",tkhd(),mdia());}
    private byte[] tkhd()throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
        d.writeInt(7);d.writeInt(0);d.writeInt(0);d.writeInt(1);d.writeInt(0);d.writeInt(0);d.writeLong(0);
        d.writeInt(0);d.writeInt(0);d.writeShort(0);d.writeShort(0);d.writeShort(0);d.writeShort(0);
        d.writeInt(0x10000);d.writeInt(0);d.writeInt(0);d.writeInt(0);d.writeInt(0x10000);d.writeInt(0);
        d.writeInt(0);d.writeInt(0);d.writeInt(0x40000000);d.writeInt(width<<16);d.writeInt(height<<16);
        return box("tkhd",b.toByteArray());
    }
    private byte[] mdia()throws IOException{return concatBox("mdia",mdhd(),hdlr(),minf());}
    private byte[] mdhd()throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
        d.writeInt(0);d.writeInt(0);d.writeInt(0);d.writeInt(90000);d.writeInt(0);d.writeShort(0x55c4);d.writeShort(0);
        return box("mdhd",b.toByteArray());
    }
    private byte[] hdlr()throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
        d.writeInt(0);d.writeInt(0);d.writeBytes("vide");d.writeInt(0);d.writeInt(0);d.writeInt(0);d.writeBytes("VideoHandler");d.writeByte(0);
        return box("hdlr",b.toByteArray());
    }
    private byte[] minf()throws IOException{return concatBox("minf",vmhd(),dinf(),stbl());}
    private byte[] vmhd()throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
        d.writeInt(1);d.writeShort(0);d.writeShort(0);d.writeShort(0);d.writeShort(0);return box("vmhd",b.toByteArray());
    }
    private byte[] dinf()throws IOException{
        byte[] url=box("url ",new byte[]{0,0,0,1});
        return concatBox("dinf",box("dref",concat(new byte[]{0,0,0,0,0,0,0,1},url)));
    }
    private byte[] stbl()throws IOException{return concatBox("stbl",stsd(),empty("stts"),empty("stsc"),empty("stsz"),empty("stco"));}
    private byte[] stsd()throws IOException{return box("stsd",concat(new byte[]{0,0,0,0,0,0,0,1},avc1()));}
    private byte[] avc1()throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
        d.write(new byte[6]);d.writeShort(1);d.writeShort(0);d.writeShort(0);d.writeInt(0);d.writeInt(0);d.writeInt(0);
        d.writeShort(width);d.writeShort(height);d.writeInt(0x480000);d.writeInt(0x480000);d.writeInt(0);d.writeShort(1);
        d.write(new byte[32]);d.writeShort(0x18);d.writeShort(0xffff);d.write(box("avcC",avcC()));d.write(box("btrt",new byte[12]));
        return box("avc1",b.toByteArray());
    }
    private byte[] avcC(){
        byte[] r=new byte[11+sps.length+pps.length];int p=0;r[p++]=1;
        r[p++]=sps.length>1?sps[1]:0x42;r[p++]=sps.length>2?sps[2]:0;r[p++]=sps.length>3?sps[3]:0x1e;
        r[p++]=(byte)0xff;r[p++]=(byte)0xe1;r[p++]=(byte)(sps.length>>8);r[p++]=(byte)sps.length;
        System.arraycopy(sps,0,r,p,sps.length);p+=sps.length;r[p++]=1;r[p++]=(byte)(pps.length>>8);r[p++]=(byte)pps.length;
        System.arraycopy(pps,0,r,p,pps.length);return r;
    }
    private byte[] mvex()throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
        ByteArrayOutputStream t=new ByteArrayOutputStream();DataOutputStream x=new DataOutputStream(t);
        x.writeInt(0);x.writeInt(1);x.writeInt(1);x.writeInt(90000/fps);x.writeInt(0);x.writeInt(0);
        d.write(box("trex",t.toByteArray()));return box("mvex",b.toByteArray());
    }
    private byte[] makeMoof(long seq,long time,long dur,int size,boolean key)throws IOException{
        byte[] mfhd=box("mfhd",ints(0,(int)seq));
        int flags=key?0x02000000:0x01010000;
        byte[] first=traf(time,dur,size,flags,0);
        int dataOffset=8+mfhd.length+first.length+8;
        byte[] real=traf(time,dur,size,flags,dataOffset);
        return concatBox("moof",mfhd,real);
    }
    private byte[] traf(long time,long dur,int size,int sampleFlags,int dataOffset)throws IOException{
        byte[] tfhd=box("tfhd",ints(0x020000,1));
        byte[] tfdt=box("tfdt",longs(0x01000000,time));
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
        d.writeInt(0x000f01);d.writeInt(dataOffset);d.writeInt((int)dur);d.writeInt(size);d.writeInt(sampleFlags);d.writeInt(0);
        return box("traf",concat(tfhd,tfdt,box("trun",b.toByteArray())));
    }
    private byte[] empty(String type)throws IOException{return box(type,new byte[]{0,0,0,0});}
    private byte[] ints(int a,int b)throws IOException{ByteArrayOutputStream b0=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b0);d.writeInt(a);d.writeInt(b);return b0.toByteArray();}
    private byte[] longs(int a,long b)throws IOException{ByteArrayOutputStream b0=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b0);d.writeInt(a);d.writeLong(b);return b0.toByteArray();}
    private byte[] concatBox(String type,byte[]...parts)throws IOException{return box(type,concat(parts));}
    private byte[] box(String type,byte[] payload)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.writeInt(payload.length+8);d.writeBytes(type);d.write(payload);return b.toByteArray();
    }
    private byte[] concat(byte[]...a){int n=0;for(byte[] x:a)n+=x.length;byte[] r=new byte[n];int p=0;for(byte[] x:a){System.arraycopy(x,0,r,p,x.length);p+=x.length;}return r;}
}
