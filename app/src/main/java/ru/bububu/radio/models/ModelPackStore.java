package ru.bububu.radio.models;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
/** Immutable generations and a tiny atomic ready pointer. Never removes the previous ready generation. */
public final class ModelPackStore {
 public interface DirectorySync {void sync(File directory)throws IOException;}
 public static final class Token {private final AtomicInteger state=new AtomicInteger();private final Token parent;public Token(){this(null);}Token(Token parent){this.parent=parent;}public boolean cancel(){return state.compareAndSet(0,1);}public boolean cancelled(){if(state.get()==0&&parent!=null&&parent.cancelled())cancel();return state.get()==1;}void beginCommit()throws IOException{if(cancelled()||!state.compareAndSet(0,2))throw new IOException("Импорт отменён");}void done(){state.set(3);}}
 private final File root;private final DirectorySync sync;
 public ModelPackStore(File directory,DirectorySync sync)throws IOException {root=directory.getCanonicalFile();if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Нет каталога моделей");privateMode(root,true);this.sync=sync;}
 public File ready(PackCatalog c){try{File pointer=new File(root,c.kind+".ready");if(!pointer.isFile()||Files.isSymbolicLink(pointer.toPath())||pointer.length()>96)return null;String name=new String(Files.readAllBytes(pointer.toPath()),StandardCharsets.US_ASCII);if(!name.matches(c.kind+"-g-[a-f0-9-]{36}"))return null;File dir=new File(root,name);File manifest=new File(dir,"manifest.txt");if(!dir.isDirectory()||Files.isSymbolicLink(dir.toPath())||!manifest.isFile()||manifest.length()>2048||Files.isSymbolicLink(manifest.toPath())||!Arrays.equals(Files.readAllBytes(manifest.toPath()),c.manifest()))return null;for(PackCatalog.Entry e:c.files){File f=new File(dir,e.name);if(!f.isFile()||Files.isSymbolicLink(f.toPath())||f.length()!=e.bytes)return null;}return dir;}catch(IOException e){return null;}}
 public synchronized File importPack(PackCatalog c,InputStream source,Token token)throws IOException {
  String id=UUID.randomUUID().toString();File stage=new File(root,c.kind+"-stage-"+id),zip=new File(stage,"incoming.zip");if(!stage.mkdir())throw new IOException("Не удалось начать импорт");privateMode(stage,true);boolean installed=false,commitStarted=false;File generation=null;
  try {
   try(InputStream in=source;FileOutputStream out=new FileOutputStream(zip)){copy(in,out,c.bytes+65536,token,null,null);out.getFD().sync();}privateMode(zip,false);check(token);PackArchive.inspect(zip,c);
   try(ZipFile archive=new ZipFile(zip)){
    Enumeration<? extends ZipEntry> entries=archive.entries();Set<String> seen=new HashSet<>();
    while(entries.hasMoreElements()) {check(token);ZipEntry entry=entries.nextElement();String n=entry.getName();if(!seen.add(n)||entry.isDirectory())throw new IOException("Повтор или каталог в пакете");File target=new File(stage,n);
     if(n.equals("manifest.txt")){byte[] data;CRC32 crc=new CRC32();try(InputStream in=archive.getInputStream(entry)){ByteArrayOutputStream out=new ByteArrayOutputStream();copy(in,out,2048,token,null,crc);data=out.toByteArray();}if(crc.getValue()!=entry.getCrc())throw new IOException("Повреждён файл пакета");if(!Arrays.equals(data,c.manifest()))throw new IOException("Не та версия пакета");writeNew(target,data);}
     else {PackCatalog.Entry e=c.entry(n);if(e==null)throw new IOException("Лишний файл пакета");MessageDigest md=digest();CRC32 crc=new CRC32();long count;try(InputStream in=archive.getInputStream(entry);FileOutputStream out=new FileOutputStream(target)){count=copy(in,out,e.bytes,token,md,crc);out.getFD().sync();}if(count!=e.bytes||!hex(md.digest()).equals(e.sha)||crc.getValue()!=entry.getCrc())throw new IOException("Повреждён файл пакета");privateMode(target,false);}
    }
    if(seen.size()!=c.files.size()+1)throw new IOException("Неполный пакет");
   }
   check(token);File previous=ready(c);if(previous!=null){verify(c,previous,token);token.beginCommit();token.done();return previous;}
   if(!zip.delete())throw new IOException("Не удалось завершить staging");sync.sync(stage);generation=new File(root,c.kind+"-g-"+id);Files.move(stage.toPath(),generation.toPath(),StandardCopyOption.ATOMIC_MOVE);installed=true;sync.sync(root);check(token);token.beginCommit();commitStarted=true;publish(c,generation.getName());token.done();return generation;
  } finally {if(!installed)removeTree(stage);else if(!commitStarted)removeTree(generation);}
 }
 private void publish(PackCatalog c,String generation)throws IOException {
  File pointer=new File(root,c.kind+".ready"),temporary=new File(root,c.kind+"-pointer-"+UUID.randomUUID());byte[] previous=pointer.isFile()&&pointer.length()<=96&&!Files.isSymbolicLink(pointer.toPath())?Files.readAllBytes(pointer.toPath()):null;writeNew(temporary,generation.getBytes(StandardCharsets.US_ASCII));
  boolean moved=false;try {Files.move(temporary.toPath(),pointer.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);moved=true;sync.sync(root);}
  catch(IOException failure){if(moved){try{if(previous==null)Files.deleteIfExists(pointer.toPath());else{File rollback=new File(root,c.kind+"-rollback-"+UUID.randomUUID());writeNew(rollback,previous);Files.move(rollback.toPath(),pointer.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}sync.sync(root);}catch(IOException uncertain){throw new IOException("Импорт не завершён; предыдущие файлы сохранены, состояние требует повторной проверки");}}throw failure;}finally{Files.deleteIfExists(temporary.toPath());}
 }
 public void verify(PackCatalog c,File dir,Token t)throws IOException {for(PackCatalog.Entry e:c.files){check(t);File f=new File(dir,e.name);if(Files.isSymbolicLink(f.toPath())||!f.isFile()||f.length()!=e.bytes)throw new IOException("Пакет изменён");MessageDigest md=digest();try(InputStream in=new FileInputStream(f)){copy(in,new OutputStream(){public void write(int b){}public void write(byte[]b,int o,int n){}},e.bytes,t,md,null);}if(!hex(md.digest()).equals(e.sha))throw new IOException("Хэш пакета не совпал");}}
 private static long copy(InputStream in,OutputStream out,long cap,Token t,MessageDigest md,CRC32 crc)throws IOException{byte[] buffer=new byte[65536];long total=0;int n;while((n=in.read(buffer))!=-1){check(t);total+=n;if(total>cap)throw new IOException("Пакет превышает размер");out.write(buffer,0,n);if(md!=null)md.update(buffer,0,n);if(crc!=null)crc.update(buffer,0,n);}check(t);return total;}
 public static String hash(byte[] data){MessageDigest md=digest();return hex(md.digest(data));}
 private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
 private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
 private static void check(Token t)throws IOException {if(t.cancelled())throw new IOException("Импорт или подготовка отменены");}
 private static void writeNew(File f,byte[] bytes)throws IOException {if(!f.createNewFile())throw new IOException("Файл уже существует");try(FileOutputStream out=new FileOutputStream(f)){out.write(bytes);out.getFD().sync();}privateMode(f,false);}
 private static void privateMode(File f,boolean dir)throws IOException {if(!f.setReadable(false,false)||!f.setWritable(false,false)||!f.setExecutable(false,false)||!f.setReadable(true,true)||!(dir?f.setWritable(true,true)&&f.setExecutable(true,true):true))throw new IOException("Не удалось защитить файлы пакета");}
 private static void removeTree(File f){if(!f.exists())return;if(f.isDirectory()&&!Files.isSymbolicLink(f.toPath())){File[] children=f.listFiles();if(children!=null)for(File p:children)removeTree(p);}f.delete();}
}
