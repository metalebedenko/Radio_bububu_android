package ru.bububu.radio.models;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Strict central/local metadata for the two prepared STORED ZIP layouts, not an arbitrary archive extractor. */
final class PackArchive {
 static void need(boolean b)throws IOException {if(!b)throw new IOException("Неверный или небезопасный архив пакета");}
 static int u16(RandomAccessFile f)throws IOException {return f.readUnsignedByte()|(f.readUnsignedByte()<<8);}
 static long u32(RandomAccessFile f)throws IOException {return u16(f)|((long)u16(f)<<16);}
 static void inspect(File file,PackCatalog c)throws IOException {
  try(RandomAccessFile f=new RandomAccessFile(file,"r")) {
   long length=f.length();need(length>=22&&length<=c.bytes+65536);f.seek(length-22);need(u32(f)==0x06054b50L&&u16(f)==0&&u16(f)==0);int disk=u16(f),count=u16(f);long centralBytes=u32(f),central=u32(f);need(u16(f)==0&&disk==count&&count==c.files.size()+1&&central+centralBytes==length-22);
   long at=central;Set<String> names=new HashSet<>();List<long[]> ranges=new ArrayList<>();
   for(int i=0;i<count;i++) {
    f.seek(at);need(u32(f)==0x02014b50L);u16(f);need(u16(f)<=20);int flags=u16(f),method=u16(f);need((flags&~2048)==0&&method==0);u16(f);u16(f);long crc=u32(f),compressed=u32(f),size=u32(f);int nl=u16(f),el=u16(f),cl=u16(f);need(u16(f)==0);u16(f);long attrs=u32(f),local=u32(f);need(nl>0&&nl<=64&&el<=1024&&cl==0);byte[] nb=new byte[nl];f.readFully(nb);String name=new String(nb,StandardCharsets.US_ASCII);need(names.add(name)&&(name.equals("manifest.txt")||c.entry(name)!=null));long expected=name.equals("manifest.txt")?c.manifest().length:c.entry(name).bytes;need(size==expected&&compressed==size);int mode=(int)(attrs>>>16);need((mode&0170000)==0||(mode&0170000)==0100000);long extraEnd=f.getFilePointer()+el;while(f.getFilePointer()<extraEnd){int id=u16(f),n=u16(f);need(id!=1&&f.getFilePointer()+n<=extraEnd);f.seek(f.getFilePointer()+n);}need(f.getFilePointer()==extraEnd);at+=46L+nl+el+cl;need(at<=central+centralBytes);
    f.seek(local);need(u32(f)==0x04034b50L);need(u16(f)<=20&&u16(f)==flags&&u16(f)==method);u16(f);u16(f);need(u32(f)==crc&&u32(f)==size&&u32(f)==size);int lnl=u16(f),lel=u16(f);need(lnl==nl&&lel<=1024);byte[] localName=new byte[lnl];f.readFully(localName);need(Arrays.equals(nb,localName));long end=local+30L+lnl+lel+size;need(local>=0&&end<=central);ranges.add(new long[]{local,end});
   }
   need(at==central+centralBytes);ranges.sort(Comparator.comparingLong(a->a[0]));long end=0;for(long[] range:ranges){need(range[0]==end);end=range[1];}need(end==central);
  }
 }
}
