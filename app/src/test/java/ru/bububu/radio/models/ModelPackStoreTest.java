package ru.bububu.radio.models;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
public class ModelPackStoreTest {
 private static byte[] payload={1,2,3,4};
 private PackCatalog catalog(){return new PackCatalog("silero",1,new PackCatalog.Entry("core.pt",4,ModelPackStore.hash(payload)));}
 private byte[] zip(PackCatalog c,String name,byte[] bytes,boolean manifest)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(b)){put(z,name,bytes);if(manifest)put(z,"manifest.txt",c.manifest());}return b.toByteArray();}
 private void put(ZipOutputStream z,String name,byte[] b)throws Exception{ZipEntry e=new ZipEntry(name);e.setMethod(ZipEntry.STORED);e.setSize(b.length);CRC32 crc=new CRC32();crc.update(b);e.setCrc(crc.getValue());z.putNextEntry(e);z.write(b);z.closeEntry();}
 private ModelPackStore store(File root)throws Exception{return new ModelPackStore(root,d->{});}
 private void fails(ModelPackStore s,PackCatalog c,byte[] bytes,ModelPackStore.Token token)throws Exception{try{s.importPack(c,new ByteArrayInputStream(bytes),token);fail();}catch(IOException expected){}}
 @Test public void successRepeatBadAndCancelledPreserveReady()throws Exception{File root=Files.createTempDirectory("model-test").toFile();PackCatalog c=catalog();ModelPackStore s=store(root);File first=s.importPack(c,new ByteArrayInputStream(zip(c,"core.pt",payload,true)),new ModelPackStore.Token());assertEquals(first,s.ready(c));assertArrayEquals(payload,Files.readAllBytes(new File(first,"core.pt").toPath()));assertEquals(first,s.importPack(c,new ByteArrayInputStream(zip(c,"core.pt",payload,true)),new ModelPackStore.Token()));fails(s,c,zip(c,"core.pt",new byte[]{9,8,7,6},true),new ModelPackStore.Token());assertEquals(first,s.ready(c));ModelPackStore.Token token=new ModelPackStore.Token();assertTrue(token.cancel());fails(s,c,zip(c,"core.pt",payload,true),token);assertEquals(first,s.ready(c));}
 @Test public void incompleteTraversalExtraAndWrongVersionReject()throws Exception{PackCatalog c=catalog();ModelPackStore s=store(Files.createTempDirectory("pack-invalid").toFile());for(byte[] b:new byte[][]{zip(c,"core.pt",payload,false),zip(c,"../core.pt",payload,true),zip(c,"other.pt",payload,true),zip(new PackCatalog("silero",2,c.files.get(0)),"core.pt",payload,true)}){fails(s,c,b,new ModelPackStore.Token());assertNull(s.ready(c));}}
 @Test public void truncatedArchiveAndSymlinkMetadataReject()throws Exception{PackCatalog c=catalog();ModelPackStore s=store(Files.createTempDirectory("pack-links").toFile());byte[] valid=zip(c,"core.pt",payload,true);fails(s,c,Arrays.copyOf(valid,valid.length-1),new ModelPackStore.Token());byte[] symlink=valid.clone();for(int i=0;i<symlink.length-46;i++)if(symlink[i]==0x50&&symlink[i+1]==0x4b&&symlink[i+2]==1&&symlink[i+3]==2){symlink[i+40]=0;symlink[i+41]=(byte)0xa0;break;}fails(s,c,symlink,new ModelPackStore.Token());assertNull(s.ready(c));}
 @Test public void interruptedInputPreservesPreviouslyReady()throws Exception{PackCatalog c=catalog();ModelPackStore s=store(Files.createTempDirectory("pack-eof").toFile());File ready=s.importPack(c,new ByteArrayInputStream(zip(c,"core.pt",payload,true)),new ModelPackStore.Token());try{s.importPack(c,new InputStream(){public int read()throws IOException{throw new IOException("provider gone");}},new ModelPackStore.Token());fail();}catch(IOException expected){}assertEquals(ready,s.ready(c));}
 @Test public void commitWinsCancellationOnlyOnce()throws Exception{ModelPackStore.Token t=new ModelPackStore.Token();t.beginCommit();assertFalse(t.cancel());t.done();assertFalse(t.cancel());ModelPackStore.Token cancelled=new ModelPackStore.Token();assertTrue(cancelled.cancel());try{cancelled.beginCommit();fail();}catch(IOException expected){}}
 @Test public void fixedPackTotalsAndVersionAreExplicit(){assertEquals(151434287,PackCatalog.SILERO.bytes);assertEquals(231897202,PackCatalog.GIGAAM.bytes);assertEquals(4,PackCatalog.SILERO.files.size());assertTrue(new String(PackCatalog.GIGAAM.manifest(),java.nio.charset.StandardCharsets.UTF_8).startsWith("RADIO_BUBUBU_PACK_V1\ngigaam\n1\n"));}
 @Test public void cancellationDuringCopyCannotPublish()throws Exception{PackCatalog c=catalog();ModelPackStore s=store(Files.createTempDirectory("pack-cancel-mid").toFile());File old=s.importPack(c,new ByteArrayInputStream(zip(c,"core.pt",payload,true)),new ModelPackStore.Token());ModelPackStore.Token token=new ModelPackStore.Token();byte[] bytes=zip(c,"core.pt",payload,true);InputStream in=new ByteArrayInputStream(bytes){public int read(byte[] b,int off,int len){int n=super.read(b,off,len);token.cancel();return n;}};try{s.importPack(c,in,token);fail();}catch(IOException expected){}assertEquals(old,s.ready(c));}
 @Test public void failedReadyDirectorySyncRollsBackToOldGeneration()throws Exception{File root=Files.createTempDirectory("pack-commit-fail").toFile();PackCatalog one=catalog(),two=new PackCatalog("silero",2,one.files.get(0));ModelPackStore first=store(root);File old=first.importPack(one,new ByteArrayInputStream(zip(one,"core.pt",payload,true)),new ModelPackStore.Token());final boolean[] thrown={false};ModelPackStore fault=new ModelPackStore(root,dir->{File pointer=new File(root,"silero.ready");if(!thrown[0]&&dir.getCanonicalFile().equals(root.getCanonicalFile())&&pointer.isFile()&&!new String(Files.readAllBytes(pointer.toPath()),java.nio.charset.StandardCharsets.US_ASCII).equals(old.getName())){thrown[0]=true;throw new IOException("injected sync failure");}});fails(fault,two,zip(two,"core.pt",payload,true),new ModelPackStore.Token());assertTrue(thrown[0]);assertEquals(old,fault.ready(one));assertNull(fault.ready(two));assertArrayEquals(payload,Files.readAllBytes(new File(old,"core.pt").toPath()));}

 @Test public void cancellationAfterGenerationMoveRemovesOnlyUnpublishedData()throws Exception{File root=Files.createTempDirectory("pack-late-cancel").toFile().getCanonicalFile();PackCatalog c=catalog();ModelPackStore.Token token=new ModelPackStore.Token();ModelPackStore s=new ModelPackStore(root,dir->{if(dir.equals(root))token.cancel();});fails(s,c,zip(c,"core.pt",payload,true),token);assertNull(s.ready(c));File[] remains=root.listFiles();assertNotNull(remains);for(File f:remains)assertFalse(f.getName().contains("-g-")||f.getName().contains("-stage-"));}

 @Test public void matchingWrongManifestHeaderCrcRejectsAndPreservesOldReady()throws Exception {
  PackCatalog c=catalog();File root=Files.createTempDirectory("pack-manifest-crc").toFile();ModelPackStore s=store(root);
  byte[] valid=zip(c,"core.pt",payload,true);File previous=s.importPack(c,new ByteArrayInputStream(valid),new ModelPackStore.Token());
  File pointer=new File(root,"silero.ready");byte[] oldPointer=Files.readAllBytes(pointer.toPath());byte[] wrong=valid.clone();
  java.nio.ByteBuffer headers=java.nio.ByteBuffer.wrap(wrong).order(java.nio.ByteOrder.LITTLE_ENDIAN);
  int central=headers.getInt(wrong.length-6);boolean patched=false;
  while(central<wrong.length-22&&headers.getInt(central)==0x02014b50) {
   int nameLength=Short.toUnsignedInt(headers.getShort(central+28)),extraLength=Short.toUnsignedInt(headers.getShort(central+30)),commentLength=Short.toUnsignedInt(headers.getShort(central+32));
   String name=new String(wrong,central+46,nameLength,java.nio.charset.StandardCharsets.US_ASCII);
   if(name.equals("manifest.txt")) {
    int local=headers.getInt(central+42);assertEquals(0x04034b50,headers.getInt(local));int declared=headers.getInt(central+16)^1;
    headers.putInt(central+16,declared);headers.putInt(local+14,declared);patched=true;
   }
   central+=46+nameLength+extraLength+commentLength;
  }
  assertTrue(patched);fails(s,c,wrong,new ModelPackStore.Token());assertEquals(previous,s.ready(c));
  assertArrayEquals(oldPointer,Files.readAllBytes(pointer.toPath()));assertArrayEquals(payload,Files.readAllBytes(new File(previous,"core.pt").toPath()));
  assertArrayEquals(c.manifest(),Files.readAllBytes(new File(previous,"manifest.txt").toPath()));
 }

}
