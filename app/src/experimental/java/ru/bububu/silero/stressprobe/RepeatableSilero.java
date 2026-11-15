package ru.bububu.silero.stressprobe;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.pytorch.*;
import ru.bububu.radio.models.PackGuard;
import ru.bububu.radio.models.PackCatalog;
/** Repeatable worker entry; no UI, claim, playback, fixed raw or native cache. */
public final class RepeatableSilero {
 private static volatile boolean blocked;
 public static byte[] synthesize(File pack,String text,BooleanSupplier cancelled)throws IOException {
  if(blocked)throw new IOException("SILERO_CLEANUP_FAILED: перезапустите приложение");
  try{return run(pack,text,cancelled);}catch(Throwable failure){String code=failure instanceof FrontendPlan.Rejected?((FrontendPlan.Rejected)failure).code:blocked?"CLEANUP_FAILED":"NATIVE_OR_INPUT_FAILED";throw new IOException("Silero: "+code);}
 }
 private static byte[] run(File pack,String text,BooleanSupplier cancelled)throws Exception {
  long started=android.os.SystemClock.elapsedRealtime();check(started,cancelled);FrontendPlan.need(PageSizeGate.checkPlatform().passed(),"UNSUPPORTED_PAGE_SIZE");
  FrontendPlan plan=new FrontendPlan(ResourceData.parse(PackGuard.resources(pack)),text);CorePlan core=null;byte[] wav=null;PyTorchAndroid.setNumThreads(2);
  for(int index=0;index<3;index++) {
   if(index==0&&!plan.needsHomo())continue;check(started,cancelled);org.pytorch.Module module=null;
   try {
    module=org.pytorch.Module.load(PackGuard.path(pack,PackCatalog.SILERO,index==0?"homosolver.pt":index==1?"accentor.pt":"core.pt"),Collections.<String,String>emptyMap(),Device.CPU);check(started,cancelled);IValue[] args;
    if(index==0)args=new IValue[]{IValue.from(Tensor.fromBlob(plan.ids,new long[]{1,plan.ids.length})),IValue.from(Tensor.fromBlob(new long[]{plan.markerStart},new long[]{1})),IValue.from(Tensor.fromBlob(new long[]{plan.markerEnd},new long[]{1}))};
    else if(index==1){List<String> words=plan.accentInputs();IValue[] list=new IValue[words.size()];for(int i=0;i<list.length;i++)list[i]=IValue.from(words.get(i));args=new IValue[]{IValue.listFrom(list)};}
    else{FrontendPlan.need(core!=null,"LIVE_CORE_ARGS_REQUIRED");args=CoreArguments.assemble(core);}
    IValue output=module.forward(args);check(started,cancelled);
    if(index==0)plan.acceptHomo(frontendTensor(output,1,false)[0]);
    else if(index==1){FrontendPlan.need(output.isTuple(),"ACCENTOR_OUTPUT_TUPLE");IValue[] tuple=output.toTuple();FrontendPlan.need(tuple.length==2,"ACCENTOR_OUTPUT_TUPLE");float[] st=frontendTensor(tuple[0],plan.rawTokens.size(),true),yo=frontendTensor(tuple[1],plan.rawTokens.size(),true);plan.acceptAccent(st,st.length/plan.rawTokens.size(),yo,yo.length/plan.rawTokens.size());for(String decision:plan.decisions)if(decision.startsWith("stress_index_fallback;")||decision.startsWith("yo_index_fallback;"))android.util.Log.w("BububuSilero",decision);core=new CorePlan(plan);}
    else {FrontendPlan.need(output.isTuple()&&output.toTuple().length==2,"CORE_OUTPUT_TUPLE");float[] pcm=null;IValue[] tuple=output.toTuple();for(int i=0;i<2;i++){FrontendPlan.need(tuple[i].isTensor(),"CORE_OUTPUT_TENSOR");Tensor t=tuple[i].toTensor();long n=CorePlan.outputElements(t.shape(),i,core.length);FrontendPlan.need(t.dtype()==DType.FLOAT32,"CORE_OUTPUT_DTYPE");float[] a=t.getDataAsFloatArray();FrontendPlan.need(a.length==n,"CORE_OUTPUT_ARRAY");for(float f:a)FrontendPlan.need(Float.isFinite(f),"CORE_OUTPUT_NONFINITE");if(i==0)pcm=a;}wav=CorePlan.wav(pcm);SemanticWav.validate(wav);}
   } finally {if(module!=null)try{module.destroy();}catch(Throwable release){blocked=true;throw new IOException("SILERO_CLEANUP_FAILED");}}
   PackGuard.path(pack,PackCatalog.SILERO,index==0?"homosolver.pt":index==1?"accentor.pt":"core.pt");check(started,cancelled);
  }
  FrontendPlan.need(wav!=null,"WAV_REQUIRED");return wav;
 }
 private static float[] frontendTensor(IValue v,long rows,boolean matrix){FrontendPlan.need(v.isTensor(),"OUTPUT_NOT_TENSOR");Tensor t=v.toTensor();FrontendPlan.need(t.dtype()==DType.FLOAT32,"OUTPUT_DTYPE");long n=FrontendPlan.shapeElements(t.shape(),rows,matrix);float[] a=t.getDataAsFloatArray();FrontendPlan.need(a.length==n,"OUTPUT_SIZE");for(float f:a)FrontendPlan.need(Float.isFinite(f),"OUTPUT_NONFINITE");return a;}
 private static void check(long started,BooleanSupplier cancelled){FrontendPlan.need(!cancelled.getAsBoolean(),"CANCELLED");FrontendPlan.need(android.os.SystemClock.elapsedRealtime()-started<=120000,"DEADLINE");}
 private RepeatableSilero(){}
}
