package ru.bububu.radio.models;
import org.junit.Test;import static org.junit.Assert.*;import java.nio.*;
public class Pcm16WaveTest {
 byte[] wav(int count,int rate){ByteBuffer b=ByteBuffer.allocate(44+count*2).order(ByteOrder.LITTLE_ENDIAN);b.put(new byte[]{82,73,70,70}).putInt(36+count*2).put(new byte[]{87,65,86,69,102,109,116,32}).putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16).put(new byte[]{100,97,116,97}).putInt(count*2);for(int i=0;i<count;i++)b.putShort((short)(i==0?-32768:32767));return b.array();}
 @Test public void runtimeLengthAndSignedPcmAreNotFixedN01()throws Exception{float[] a=Pcm16Wave.decode(wav(20,16000));assertEquals(20,a.length);assertEquals(-1f,a[0],0);assertEquals(32767f/32768f,a[1],0);assertEquals(480000,Pcm16Wave.decode(wav(480000,16000)).length);}
 @Test public void wrongRateOverlongAndIncompleteReject()throws Exception{for(byte[] b:new byte[][]{wav(1,24000),wav(480001,16000),new byte[20]})try{Pcm16Wave.decode(b);fail();}catch(java.io.IOException expected){}}
 @Test public void noteOnlyBranchAcceptsCompleteBalancedInputsWhileUriStillRejectsOver30()throws Exception{
  for(int n:new int[]{480001,960001,1440001,1920000}){byte[] b=wav(n,16000);float[] samples=Pcm16Wave.decodeNote(b);assertEquals(n,samples.length);assertEquals(-1f,samples[0],0);assertEquals(32767f/32768f,samples[n-1],0);assertThrows(java.io.IOException.class,()->Pcm16Wave.decode(b));}
 }
 @Test public void noteBranchRejectsOver120NullWrongRateAndMalformedHeader()throws Exception{
  byte[] damaged=wav(1,16000);damaged[40]=0;
  for(byte[] b:new byte[][]{wav(1920001,16000),null,wav(1,24000),new byte[20],damaged,wav(0,16000)})assertThrows(java.io.IOException.class,()->Pcm16Wave.decodeNote(b));
 }
}
