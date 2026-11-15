package ru.bububu.radio.models;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.system.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import ru.bububu.radio.BuildConfig;
import ru.bububu.radio.core.PcmWave;
/** One app-native worker for imports and both models; no microphones or autoplay. */
public final class ModelsController {
 public interface Result<T> extends ModelWorkQueue.Result<T>{}
 private static ModelsController instance;
 public static synchronized ModelsController get(Context c){if(instance==null)instance=new ModelsController(c.getApplicationContext());return instance;}
 private final Context context;private final Handler main=new Handler(Looper.getMainLooper());private final ExecutorService worker=Executors.newSingleThreadExecutor();private final ModelPackStore store;private final ModelWorkQueue queue;private final List<Runnable> packListeners=new ArrayList<>();private volatile long packEpoch;
 private ModelsController(Context c){context=c;try{store=new ModelPackStore(new File(c.getFilesDir(),"model-packs"),ModelsController::syncDirectory);queue=new ModelWorkQueue(worker,main::post,this::discard);}catch(IOException error){throw new IllegalStateException("Хранилище моделей недоступно",error);}}
 public File ready(PackCatalog c){return store.ready(c);}
 public ModelPackStore.Token prepareBundled(Consumer<String> progress,Result<Void> result){
  return queue.submitIdle(t->{String reason=availability();if(reason!=null)throw new IOException(reason);
   new BundledPacks(store,new PackCatalog[]{PackCatalog.SILERO,PackCatalog.GIGAAM},
    c->context.getAssets().open("bundled-models/"+c.kind+"-accepted-v1.zip"),
    ()->new StatFs(context.getFilesDir().getPath()).getAvailableBytes()).prepare(t,message->main.post(()->{if(!t.cancelled())progress.accept(message);}));
   return null;
  },result,()->progress.accept("Ожидание текущей операции перед проверкой моделей…"));
 }
 public boolean busy(){return queue.busy();}
 public boolean foregroundBlocked(){return queue.foregroundBlocked();}
 public java.util.List<String> radioFragments(String raw,int maxChars){return radioFragments(SileroSpeechInput.forSentence(raw),maxChars);}
 private java.util.List<String> radioFragments(SileroSpeechInput.SentencePlan sentence,int maxChars){if(availability()!=null)throw new IllegalArgumentException("LOCAL_MODEL_UNAVAILABLE");File pack=store.ready(PackCatalog.SILERO);if(pack==null)throw new IllegalArgumentException("LOCAL_PACK_NOT_READY");try{return new NativeModels().radioFragments(pack,sentence,maxChars);}catch(IllegalArgumentException rejected){throw rejected;}catch(Exception failure){throw new IllegalArgumentException("LOCAL_INPUT_PREPARATION_FAILED");}}
 public java.util.List<String> radioFragments(ru.bububu.radio.core.PlayerController.SpeechSentence sentence,int maxChars){
  if(!ru.bububu.radio.sources.SourceSpeechPlan45.source45(sentence.article))return radioFragments(SileroSpeechInput.forSentence(sentence),maxChars);
  if(availability()!=null)throw new IllegalArgumentException("LOCAL_MODEL_UNAVAILABLE");File pack=store.ready(PackCatalog.SILERO);if(pack==null)throw new IllegalArgumentException("LOCAL_PACK_NOT_READY");
  try{return new NativeModels().radioFragments45(pack,ru.bububu.radio.sources.SourceSpeechPlan45.sentence(sentence),Math.min(512,maxChars));}catch(IllegalArgumentException rejected){throw rejected;}catch(Exception failure){throw new IllegalArgumentException("LOCAL_INPUT_PREPARATION_FAILED");}
 }
 public String notesAvailability(){String reason=availability();return reason!=null?reason:ready(PackCatalog.GIGAAM)==null?"Сначала импортируйте пакет GigaAM":null;}
 public ModelPackStore.Token transcribeNote(File originals,String id,Result<String> result){return submit(t->{String unavailable=notesAvailability();if(unavailable!=null)throw new IOException(unavailable);byte[] wav=PcmWave.readLocalOriginal(originals,id,t::cancelled);File pack=verifiedGiga(t);return new NativeModels().transcribeNote(pack,wav,t::cancelled);},result);}
 private File verifiedGiga(ModelPackStore.Token t)throws IOException{String unavailable=notesAvailability();if(unavailable!=null)throw new IOException(unavailable);File pack=store.ready(PackCatalog.GIGAAM);if(pack==null)throw new IOException("Пакет GigaAM недоступен; оригинал сохранён");store.verify(PackCatalog.GIGAAM,pack,t);return pack;}
 public String availability(){if(!BuildConfig.MODEL_EXPERIMENT)return "Нужна experimental ARM64-версия";if(Build.VERSION.SDK_INT<28||!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"))return "Нужен ARM64 и Android 9+";try{if(Os.sysconf(OsConstants._SC_PAGESIZE)!=4096)return "Эта версия поддерживает страницы памяти 4 КБ";}catch(Exception e){return "Размер страниц памяти не подтверждён";}return null;}
 private interface Work<T> extends ModelWorkQueue.Work<T>{}
 private <T> ModelPackStore.Token submit(Work<T> work,Result<T> result){return queue.submit(false,work,result);}
 public boolean promote(ModelPackStore.Token token){return queue.promote(token);}
 public String sileroStamp(){File pack=store.ready(PackCatalog.SILERO);return pack==null?null:pack.getAbsolutePath()+"#"+packEpoch+"#eugene24k#"+ru.bububu.radio.sources.SourceSpeechPlan45.runtimeStamp();}
 public void addPackListener(Runnable listener){packListeners.add(listener);}
 public void removePackListener(Runnable listener){packListeners.remove(listener);}
 private void discard(Object value){File f=value instanceof PreparedSpeech?((PreparedSpeech)value).file:value instanceof File?(File)value:null;if(f!=null)try{if(f.getCanonicalFile().getParentFile().equals(context.getCacheDir().getCanonicalFile()))f.delete();}catch(IOException ignored){}}
 public static final class PreparedSpeech {public final ru.bububu.radio.core.PlayerController.SpeechUnit unit;public final String stamp,projectedHash;public final File file;PreparedSpeech(ru.bububu.radio.core.PlayerController.SpeechUnit unit,String stamp,String hash,File file){this.unit=unit;this.stamp=stamp;this.projectedHash=hash;this.file=file;}}
 public interface AheadResult extends Result<PreparedSpeech>{void planned(ru.bububu.radio.core.PlayerController.SpeechUnit unit);}
 public ModelPackStore.Token prepareAhead(RadioLookahead.Slot slot,int maxChars,AheadResult result){return queue.submit(true,t->{
  try{
  if(!Objects.equals(slot.stamp,sileroStamp()))throw new IOException("Пакет Silero изменён");
  ru.bububu.radio.core.PlayerController.SpeechUnit after=slot.after,unit;
  if(slot.sentence==after.sentence.index)unit=new ru.bububu.radio.core.PlayerController.SpeechUnit(after.sentence,after.fragments,slot.fragment);
  else{ru.bububu.radio.core.PlayerController.SpeechSentence sentence=new ru.bububu.radio.core.PlayerController.SpeechSentence(after.sentence.stationId,after.sentence.article,after.sentence.binding,slot.sentence);unit=new ru.bububu.radio.core.PlayerController.SpeechUnit(sentence,radioFragments(sentence,maxChars),0);}
  if(t.cancelled())throw new IOException("Подготовка отменена");main.post(()->{if(!t.cancelled())result.planned(unit);});
  String projected=ru.bububu.radio.sources.SourceSpeechPlan45.project(unit),hash=RadioLookahead.hash(unit);File file=prepareWav(projected,slot.stamp,t);return new PreparedSpeech(unit,slot.stamp,hash,file);
  }catch(IllegalArgumentException rejected){String code=ru.bububu.radio.core.LocalTextReject.preflight(rejected);if(code!=null)throw new IOException(ru.bububu.radio.core.LocalTextReject.tagged(code));throw rejected;}
 },result);}
 private File prepareWav(String raw,String expected,ModelPackStore.Token t)throws Exception{
  String unavailable=availability();if(unavailable!=null)throw new IOException(unavailable);File pack=store.ready(PackCatalog.SILERO);if(pack==null)throw new IOException("Сначала импортируйте пакет Silero");
  if(expected!=null&&!expected.equals(sileroStamp()))throw new IOException("Пакет Silero изменён");store.verify(PackCatalog.SILERO,pack,t);
  if(expected!=null&&!expected.equals(sileroStamp()))throw new IOException("Пакет Silero изменён");byte[] wav=new NativeModels().synthesize(pack,raw,t::cancelled);if(t.cancelled())throw new IOException("Подготовка отменена");
  if(wav==null||wav.length<46||wav.length>1048576)throw new IOException("Некорректный размер WAV");File out=File.createTempFile("silero-",".wav",context.getCacheDir());boolean keep=false;
  try{try(FileOutputStream stream=new FileOutputStream(out)){stream.write(wav);stream.getFD().sync();}if(!Arrays.equals(java.nio.file.Files.readAllBytes(out.toPath()),wav))throw new IOException("WAV не подтверждён");out.setWritable(false,false);if(t.cancelled()||expected!=null&&!expected.equals(sileroStamp()))throw new IOException("Подготовка отменена или пакет изменён");keep=true;return out;}finally{if(!keep)out.delete();}
 }
 public ModelPackStore.Token importPack(PackCatalog c,Uri uri,Result<File> result){if(c==PackCatalog.SILERO){packEpoch++;for(Runnable listener:new ArrayList<>(packListeners))listener.run();}return submit(t->{if(new StatFs(context.getFilesDir().getPath()).getAvailableBytes()<c.bytes*2+16777216)throw new IOException("Недостаточно места для безопасного импорта");InputStream in=context.getContentResolver().openInputStream(uri);if(in==null)throw new IOException("Файл не открыт");return store.importPack(c,in,t);},result);}
 public ModelPackStore.Token prepare(String raw,Result<File> result){return submit(t->prepareWav(raw,null,t),result);}
 public ModelPackStore.Token prepareRadio(String raw,String stamp,Result<File> result){return submit(t->prepareWav(raw,stamp,t),result);}
 public ModelPackStore.Token transcribe(Uri uri,Result<String> result){return submit(t->{String unavailable=availability();if(unavailable!=null)throw new IOException(unavailable);File pack=store.ready(PackCatalog.GIGAAM);if(pack==null)throw new IOException("Сначала импортируйте пакет GigaAM");store.verify(PackCatalog.GIGAAM,pack,t);byte[] wav;try(InputStream in=context.getContentResolver().openInputStream(uri);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){if(in==null)throw new IOException("WAV не открыт");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){if(t.cancelled())throw new IOException("Распознавание отменено");if(bytes.size()+n>960044)throw new IOException("Нужен PCM16 mono 16 кГц WAV до 30 секунд");bytes.write(b,0,n);}wav=bytes.toByteArray();}return new NativeModels().transcribe(pack,wav,t::cancelled);},result);}
 public static void syncDirectory(File directory)throws IOException{java.io.FileDescriptor fd=null;try{int flags=OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW;if(Build.VERSION.SDK_INT>=27)flags|=OsConstants.O_CLOEXEC;fd=Os.open(directory.getPath(),flags,0);Os.fsync(fd);}catch(ErrnoException error){throw new IOException("Не подтверждено сохранение каталога");}finally{if(fd!=null)try{Os.close(fd);}catch(ErrnoException ignored){}}}
}
