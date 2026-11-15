package ru.bububu.silero.stressprobe;
import java.nio.*;
import java.util.*;
import ru.bububu.silero.coreprobe.*;
/** Own checked carrier and accepted C272 PCM16 converter; no native calls. */
final class CorePlan {
 final List<AnnotatedCarrier.Arg> arguments;final int length;
 CorePlan(FrontendPlan plan) {
  FrontendPlan.need(plan.annotated!=null,"ANNOTATION_REQUIRED_FOR_CORE");
  CheckedCarrier c=new CheckedCarrier(DictionaryData.symbols());arguments=c.encodeReviewedAnnotation(plan.normalization,plan.annotated);
  FrontendPlan.need(arguments.size()==14&&c.decode(arguments).equals("|"+plan.annotated+"~"),"CARRIER_ROUNDTRIP");
  length=arguments.get(0).shape[1];FrontendPlan.need(length>=3&&length<=514,"CORE_SEQUENCE_BOUND");
  FrontendPlan.need(arguments.get(1).bytes.length==8&&ByteBuffer.wrap(arguments.get(1).bytes).order(ByteOrder.LITTLE_ENDIAN).getLong()==3&&arguments.get(2).scalar.equals("24000")&&arguments.get(3).kind.equals("none"),"CORE_PROFILE");
 }
 static long outputElements(long[] shape,int index,int length) {
  FrontendPlan.need(shape.length==2&&shape[0]==1&&shape[1]>0&&shape[1]<=(index==0?SemanticWav.MAX_SAMPLES:length)&&(index==0||shape[1]==length),"CORE_OUTPUT_SHAPE");return shape[1];
 }
 static byte[] wav(float[] pcm) {
  FrontendPlan.need(pcm!=null&&pcm.length>0&&pcm.length<=SemanticWav.MAX_SAMPLES,"CORE_PCM_BOUND");
  int samples=pcm.length;ByteBuffer out=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN);
  out.put(new byte[]{'R','I','F','F'}).putInt(36+samples*2).put(new byte[]{'W','A','V','E','f','m','t',' '});
  out.putInt(16).putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16);
  out.put(new byte[]{'d','a','t','a'}).putInt(samples*2);
  for(float value:pcm) { FrontendPlan.need(Float.isFinite(value),"CORE_PCM_NONFINITE");out.putShort((short)Math.round(Math.max(-1f,Math.min(1f,value))*32767f)); }
  return out.array();
 }
}
