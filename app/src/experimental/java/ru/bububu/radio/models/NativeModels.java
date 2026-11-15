package ru.bububu.radio.models;
import java.io.*;
import java.util.function.BooleanSupplier;
import com.k2fsa.sherpa.onnx.*;
public final class NativeModels implements ModelRuntime {
 private static volatile boolean blocked;
 public java.util.List<String> radioFragments(File pack,SileroSpeechInput.SentencePlan sentence,int maxChars)throws Exception{return ru.bububu.silero.stressprobe.RadioFragments.fromResources(PackGuard.resources(pack),sentence,maxChars);}
 public java.util.List<String> radioFragments45(File pack,ru.bububu.radio.sources.SourceSpeechPlan45.Plan plan,int maxChars)throws Exception{return ru.bububu.silero.stressprobe.RadioFragments.fromSource45Resources(PackGuard.resources(pack),plan,maxChars);}
 public byte[] synthesize(File pack,String raw,BooleanSupplier cancelled)throws IOException{if(blocked)throw new IOException("Освобождение модели не подтверждено; перезапустите приложение");try{return ru.bububu.silero.stressprobe.RepeatableSilero.synthesize(pack,raw,cancelled);}catch(IOException failure){if(failure.getMessage()!=null&&failure.getMessage().contains("CLEANUP_FAILED"))blocked=true;throw failure;}}
 public String transcribe(File pack,byte[] wav,BooleanSupplier cancelled)throws IOException {
  if(blocked)throw new IOException("Освобождение модели не подтверждено; перезапустите приложение");OfflineRecognizer recognizer=null;OfflineStream stream=null;boolean release=true;long start=android.os.SystemClock.elapsedRealtime();
  try {check(cancelled,start);float[] samples=Pcm16Wave.decode(wav);OfflineTransducerModelConfig transducer=new OfflineTransducerModelConfig();transducer.setEncoder(PackGuard.path(pack,PackCatalog.GIGAAM,"encoder.int8.onnx"));transducer.setDecoder(PackGuard.path(pack,PackCatalog.GIGAAM,"decoder.onnx"));transducer.setJoiner(PackGuard.path(pack,PackCatalog.GIGAAM,"joiner.onnx"));OfflineModelConfig model=new OfflineModelConfig();model.setTransducer(transducer);model.setTokens(PackGuard.path(pack,PackCatalog.GIGAAM,"tokens.txt"));model.setNumThreads(2);model.setProvider("cpu");model.setDebug(false);model.setModelType("");model.setModelingUnit("");model.setBpeVocab("");OfflineRecognizerConfig config=new OfflineRecognizerConfig();config.setFeatConfig(new FeatureConfig(16000,64,0f));config.setModelConfig(model);config.setDecodingMethod("greedy_search");config.setBlankPenalty(0f);config.setHotwordsFile("");config.setRuleFsts("");config.setRuleFars("");recognizer=new OfflineRecognizer(null,config);check(cancelled,start);stream=recognizer.createStream();stream.acceptWaveform(samples,16000);recognizer.decode(stream);check(cancelled,start);String text=recognizer.getResult(stream).getText();if(text==null||text.isEmpty()||text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw new IOException("Пустой или слишком длинный результат");for(PackCatalog.Entry entry:PackCatalog.GIGAAM.files)PackGuard.path(pack,PackCatalog.GIGAAM,entry.name);return text;
  }catch(Throwable error){throw new IOException("GigaAM: файл или распознавание не поддержаны");}
  finally {try{if(stream!=null)stream.release();}catch(Throwable error){release=false;}try{if(recognizer!=null)recognizer.release();}catch(Throwable error){release=false;}if(!release){blocked=true;throw new IOException("GigaAM: освобождение модели не подтверждено; перезапустите приложение");}}
 }
 public String transcribeNote(File pack,byte[] wav,BooleanSupplier cancelled)throws IOException {
  if(blocked)throw new IOException("Освобождение модели не подтверждено; перезапустите приложение");
  long start=android.os.SystemClock.elapsedRealtime();
  check(cancelled,start);
  float[] samples=Pcm16Wave.decodeNote(wav);
  try {
   return GigaNoteChunks.run(samples,()->{
    OfflineTransducerModelConfig transducer=new OfflineTransducerModelConfig();
    transducer.setEncoder(notePath(pack,"encoder.int8.onnx",cancelled,start));
    transducer.setDecoder(notePath(pack,"decoder.onnx",cancelled,start));
    transducer.setJoiner(notePath(pack,"joiner.onnx",cancelled,start));
    OfflineModelConfig model=new OfflineModelConfig();
    model.setTransducer(transducer);model.setTokens(notePath(pack,"tokens.txt",cancelled,start));
    model.setNumThreads(2);model.setProvider("cpu");model.setDebug(false);model.setModelType("");model.setModelingUnit("");model.setBpeVocab("");
    OfflineRecognizerConfig config=new OfflineRecognizerConfig();
    config.setFeatConfig(new FeatureConfig(16000,64,0f));config.setModelConfig(model);config.setDecodingMethod("greedy_search");config.setBlankPenalty(0f);config.setHotwordsFile("");config.setRuleFsts("");config.setRuleFars("");
    check(cancelled,start);
    OfflineRecognizer recognizer=new OfflineRecognizer(null,config);
    return new GigaNoteChunks.Session(){
     public GigaNoteChunks.Stream openStream(){
      OfflineStream stream=recognizer.createStream();
      if(stream==null)return null;
      return new GigaNoteChunks.Stream(){
       public void accept(float[] part){stream.acceptWaveform(part,16000);}
       public void decode(){recognizer.decode(stream);}
       public String text(){return recognizer.getResult(stream).getText();}
       public void close(){stream.release();}
      };
     }
     public void close(){recognizer.release();}
    };
   },()->check(cancelled,start),()->{
    for(PackCatalog.Entry entry:PackCatalog.GIGAAM.files)notePath(pack,entry.name,cancelled,start);
   });
  }catch(GigaNoteChunks.CleanupFailure failure){blocked=true;throw failure;}
 }
 private static String notePath(File pack,String name,BooleanSupplier cancelled,long start)throws IOException {
  check(cancelled,start);String path=PackGuard.path(pack,PackCatalog.GIGAAM,name);check(cancelled,start);return path;
 }
 private static void check(BooleanSupplier c,long start)throws IOException{if(c.getAsBoolean()||android.os.SystemClock.elapsedRealtime()-start>120000)throw new IOException("Подготовка отменена или превышено время");}
}
