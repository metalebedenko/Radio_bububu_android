package ru.bububu.radio;
import android.content.Context;
import android.media.*;
import android.os.Handler;
import java.io.File;
import java.util.UUID;
import java.util.function.Consumer;
import ru.bububu.radio.core.PlayerController;
import ru.bububu.radio.models.*;
/** System default plus explicit local Silero; callbacks are owner-thread and identity guarded. */
final class SpeechRouter implements PlayerController.SpeechEngine {
 interface Host extends AndroidSpeechEngine.Host {void previewFinished();default void speechDebug(String stage,long epoch,int sentence,int fragment,long value){}default void textFailed(String id,String code){failed(id,code);}}
 private static final java.util.Set<SpeechRouter> UNKNOWN=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
 static boolean isOutputBlocked(){return !UNKNOWN.isEmpty();}
 private final Context context;private final Handler main;private final Host host;private final AndroidSpeechEngine system;private final ModelsController models;private final PlaybackTicket gate=new PlaybackTicket();
 private final RadioLookahead ahead=new RadioLookahead();private PlayerController.SpeechUnit activeUnit;private PlayerController.SpeechWindow window;private PlaybackTicket.Token activeTicket;private RadioLookahead.Slot adopted;private String packStamp;private final Runnable packListener=this::packChanged;
 private ModelPackStore.Token work;private MediaPlayer output;private boolean silero,closed,unsafe,outputPrepared,outputStarted;private Consumer<String> preview;private File transientWav;
 SpeechRouter(Context c,Handler h,Host host){context=c;main=h;this.host=host;system=new AndroidSpeechEngine(c,h,host);models=ModelsController.get(c);models.addPackListener(packListener);silero=c.getSharedPreferences("local-model-choice",0).getBoolean("silero",false);}
 boolean selected(){return silero;}
 boolean ready(){return !closed&&!unsafe&&(silero?models.availability()==null&&models.ready(PackCatalog.SILERO)!=null:system.ready());}
 boolean terminalVoiceError(){return silero?models.availability()!=null:system.terminalError();}
 void reloadSavedChoice(){boolean saved=context.getSharedPreferences("local-model-choice",0).getBoolean("silero",false);if(saved!=silero){stop();silero=saved;host.voiceChanged();}}
 @Override public java.util.List<String> fragments(PlayerController.SpeechSentence sentence,int maxChars){if(!silero)return ru.bububu.radio.sources.SourceSpeechPlan45.source45(sentence.article)?ru.bububu.radio.sources.SourceSpeechPlan45.sentence(sentence).parts(Math.min(512,maxChars),ru.bububu.radio.sources.SourceSpeechPlan45::boundedSpoken).raw:PlayerController.SpeechEngine.super.fragments(sentence,maxChars);String stamp=syncStamp();java.util.List<String> cached=ahead.plan(sentence,stamp);return cached!=null?cached:models.radioFragments(sentence,maxChars);}
 @Override public java.util.List<String> fragments(String text,int maxChars){return silero?models.radioFragments(text,maxChars):PlayerController.SpeechEngine.super.fragments(text,maxChars);}
 void select(boolean selected){stop();if(selected&&(models.availability()!=null||models.ready(PackCatalog.SILERO)==null))throw new IllegalStateException("Пакет Silero не готов");silero=selected;context.getSharedPreferences("local-model-choice",0).edit().putBoolean("silero",selected).apply();host.voiceChanged();}
 String description(){return silero?"Silero · eugene · локальный · 24 кГц\nЛатиница — названия английских букв; неподдержанный текст остановит чтение":system.description();}
 void refreshIfIdle(){system.refreshIfIdle();}
 @Override public void speak(String text,String id){if(!silero){stop();system.speak(text,id);return;}stop();speakLocal(null,text,id);}
 @Override public void speak(PlayerController.SpeechUnit unit,String id){
  if(!silero){stop();system.speak(ru.bububu.radio.sources.SourceSpeechPlan45.source45(unit.sentence.article)?ru.bububu.radio.sources.SourceSpeechPlan45.project(unit):unit.text,id);return;}
  if(activeUnit!=null&&!activeUnit.sentence.sameArticle(unit.sentence))clearAhead();
  if(work!=null){work.cancel();work=null;}gate.cancel();
  try{releaseOutput();}catch(RuntimeException failure){host.failed(id,"Остановка звука не подтверждена");return;}
  speakLocal(unit,unit.text,id);
 }
 private void speakLocal(PlayerController.SpeechUnit unit,String text,String id){
  if(closed||unsafe||isOutputBlocked()){host.failed(id,"Остановка звука не подтверждена");return;}
  String stamp=syncStamp();activeUnit=unit;String problem=host.prepareOutput();if(problem!=null){host.failed(id,problem);return;}
  PlaybackTicket.Token token=gate.begin(id);activeTicket=token;
  RadioLookahead.Slot slot=unit==null?null:ahead.claim(unit,stamp);
  if(slot!=null){trace(slot.finished?(slot.error==null?"consume_hit":"consume_error"):"consume_inflight",unit,0);if(slot.finished){if(slot.error!=null){gate.cancel();preparationFailure(id,unit,slot.error,"adopted_error",null);}else{transientWav=ahead.transfer(slot);play(transientWav,token,false);}return;}
   adopted=slot;work=slot.work;models.promote(work);trace("adopt",unit,0);return;
  }
  trace("consume_miss",unit,0);long started=android.os.SystemClock.elapsedRealtime();trace("prepare_start",unit,0);
  String projected;try{projected=unit==null?SileroSpeechInput.project(text):ru.bububu.radio.sources.SourceSpeechPlan45.project(unit);}catch(IllegalArgumentException rejected){String code=ru.bububu.radio.core.LocalTextReject.preflight(rejected);gate.cancel();preparationFailure(id,unit,code==null?ru.bububu.radio.core.LocalTextReject.diagnostic(rejected.getMessage()):ru.bububu.radio.core.LocalTextReject.tagged(code),"projection_error",null);return;}
  String submittedHash=ru.bububu.radio.sources.SourceLimits.sha256(projected);
  diagnostic("prepare_submit","INPUT_SUBMITTED",id,-1,unit==null?null:unit.sentence,unit,submittedHash);
  work=models.prepareRadio(projected,stamp,new ModelsController.Result<File>(){
   public void success(File file){if(!gate.current(token)||!java.util.Objects.equals(stamp,models.sileroStamp())){file.delete();if(gate.current(token))host.failed(id,"Пакет Silero изменён");return;}work=null;trace("ready",unit,android.os.SystemClock.elapsedRealtime()-started);transientWav=file;play(file,token,false);}
   public void error(String message){if(gate.current(token)){work=null;gate.cancel();preparationFailure(id,unit,message,"prepare_error",submittedHash);}}
  });
 }
 @Override public void lookahead(PlayerController.SpeechWindow hint){if(!silero||closed||activeUnit==null||activeUnit.sentence.binding==null||activeTicket==null||!gate.playing(activeTicket)||!activeTicket.id.equals(hint.requestId)||!activeUnit.same(hint.current))return;window=hint;fillAhead();}
 private void fillAhead(){
  if(window==null||activeTicket==null||!gate.playing(activeTicket)||!activeTicket.id.equals(window.requestId)||closed||unsafe)return;
  if(!window.allowed()){clearAhead();return;}String stamp=syncStamp();if(window==null)return;RadioLookahead.Slot slot=ahead.reserve(window.current,stamp);if(slot==null)return;
  long started=android.os.SystemClock.elapsedRealtime();
  ModelPackStore.Token token=models.prepareAhead(slot,window.maxChars,new ModelsController.AheadResult(){
   public void planned(PlayerController.SpeechUnit unit){if(ahead.planned(slot,unit)&&adopted==slot&&activeUnit!=null&&!activeUnit.same(unit)){clearAhead();if(activeTicket!=null&&gate.current(activeTicket))host.failed(activeTicket.id,"Изменилось разбиение текста");}}
   public void success(ModelsController.PreparedSpeech value){
    boolean accepted=ahead.ready(slot,value.unit,value.stamp,value.projectedHash,value.file);
    if(!accepted){if(ahead.valid(slot)){ahead.failure(slot,"Подготовленный WAV не подтверждён");deliverAhead(slot);}return;}
    trace("ready",value.unit,android.os.SystemClock.elapsedRealtime()-started);deliverAhead(slot);
   }
   public void error(String message){if(ahead.valid(slot)){if(adopted!=slot&&slot.work!=null&&slot.work.cancelled()){ahead.abandon(slot);return;}diagnostic("prefetch_error",ru.bububu.radio.core.LocalTextReject.prepared(message),window==null?"none":window.requestId,-1,slot.unit==null?null:slot.unit.sentence,slot.unit,null);ahead.failure(slot,message);deliverAhead(slot);}}
  });
  if(token==null){ahead.abandon(slot);return;}slot.work=token;host.speechDebug("prepare_start",slot.epoch,slot.sentence,slot.fragment,0);
 }
 private void deliverAhead(RadioLookahead.Slot slot){
  if(!ahead.valid(slot))return;
  if(adopted==slot){
   adopted=null;work=null;
   if(activeTicket==null||!gate.current(activeTicket)||activeUnit==null||slot.unit!=null&&!slot.unit.same(activeUnit)||!java.util.Objects.equals(slot.stamp,models.sileroStamp())){File f=ahead.transfer(slot);if(f!=null)f.delete();if(activeTicket!=null&&gate.current(activeTicket))host.failed(activeTicket.id,"Подготовленный фрагмент устарел");return;}
   if(slot.error!=null){String id=activeTicket.id;gate.cancel();preparationFailure(id,activeUnit,slot.error,"adopted_error",null);return;}
   transientWav=ahead.transfer(slot);play(transientWav,activeTicket,false);
  }else fillAhead();
 }
 private String syncStamp(){String stamp=models.sileroStamp();if(!java.util.Objects.equals(packStamp,stamp)){clearAhead();packStamp=stamp;}return stamp;}
 private void clearAhead(){ahead.clear();window=null;adopted=null;trace("clear",activeUnit,0);}
 private void packChanged(){String id=activeTicket!=null&&gate.current(activeTicket)?activeTicket.id:null;try{stop();}catch(RuntimeException failure){unsafe=true;}if(id!=null)host.failed(id,"Пакет Silero изменён; чтение остановлено");}
 private void trace(String stage,PlayerController.SpeechUnit unit,long value){host.speechDebug(stage,ahead.epoch(),unit==null?-1:unit.sentence.index,unit==null?-1:unit.fragment,value);}
 private void preparationFailure(String id,PlayerController.SpeechUnit unit,String message,String stage,String inputHash){String code=ru.bububu.radio.core.LocalTextReject.prepared(message);diagnostic(stage,ru.bububu.radio.core.LocalTextReject.diagnostic(message),id,-1,unit==null?null:unit.sentence,unit,inputHash);if(code!=null)host.textFailed(id,code);else host.failed(id,message);}
 @Override public void textEvent(String stage,String reason,String id,long intent,PlayerController.SpeechSentence sentence,PlayerController.SpeechUnit unit){diagnostic(stage,reason,id,intent,sentence,unit,null);}
 private void diagnostic(String stage,String code,String id,long intent,PlayerController.SpeechSentence sentence,PlayerController.SpeechUnit unit,String inputHash){String kind=inputHash==null?"canonical":"projected_submission";if(inputHash==null&&sentence!=null)inputHash=ru.bububu.radio.sources.SourceLimits.sha256(unit==null?sentence.text():unit.text);android.util.Log.i("BububuRecovery","stage="+stage+" reason="+(code==null?"NON_TEXT_FAILURE":code)+" request="+id+" intent="+intent+" epoch="+ahead.epoch()+" article="+(sentence==null?"unknown":sentence.article.id)+" version="+(sentence==null?"unknown":sentence.article.version)+" sentence="+(sentence==null?-1:sentence.index)+" fragment="+(unit==null?-1:unit.fragment)+" input_kind="+kind+" input_sha256="+(inputHash==null?"unknown":inputHash));}
 void preview(File file,Consumer<String> callback){stop();if(unsafe||closed){callback.accept("Остановка звука не подтверждена");return;}String error=host.prepareOutput();if(error!=null){callback.accept(error);return;}preview=callback;play(file,gate.begin("preview-"+UUID.randomUUID()),true);}
 private void play(File file,PlaybackTicket.Token token,boolean demo){if(!gate.ready(token))return;try{MediaPlayer p=new MediaPlayer();output=p;outputStarted=false;p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());p.setDataSource(file.getPath());p.setOnPreparedListener(mp->{if(!current(mp,token))return;try{outputPrepared=true;mp.start();outputStarted=true;pollStart(mp,token,demo);}catch(RuntimeException e){failed(token,demo,"Не удалось начать воспроизведение");}});p.setOnCompletionListener(mp->completeOutput(mp,token,demo));p.setOnErrorListener((mp,a,b)->{if(current(mp,token))failed(token,demo,"Ошибка воспроизведения WAV");return true;});p.prepareAsync();main.postDelayed(()->{if(current(p,token)&&!gate.playing(token))failed(token,demo,"Звук не начался за 10 секунд");},10000);main.postDelayed(()->{if(current(p,token))failed(token,demo,"Воспроизведение не завершилось");},45000);}catch(Exception e){failed(token,demo,"WAV не удалось открыть");}}
 private void completeOutput(MediaPlayer p,PlaybackTicket.Token token,boolean demo){
  if(!current(p,token))return;
  long finalPosition=-1;
  if(!gate.playing(token)){
   try{finalPosition=p.getCurrentPosition();}catch(RuntimeException unavailable){trace("completion_position_error",activeUnit,0);}
   trace("completion_position",activeUnit,finalPosition);
  }
  boolean done;
  try{done=finishPlayback(gate,token,outputStarted,finalPosition,()->{releaseOutput();window=null;},()->reportStarted(token,demo));}
  catch(RuntimeException failure){failed(token,demo,"Остановка звука не подтверждена");return;}
  if(done){trace("done",activeUnit,0);if(demo){Consumer<String> c=preview;preview=null;if(c!=null)c.accept("Прослушивание завершено");host.previewFinished();}else host.done(token.id);}
  else failed(token,demo,"Не подтверждено начало звука");
 }
 // The current completion may contain the first positive position sample. Release must
 // succeed before callbacks, and either callback may synchronously cancel/replace the owner.
 static boolean finishPlayback(PlaybackTicket gate,PlaybackTicket.Token token,boolean startIssued,long finalPosition,Runnable release,Runnable started){
  if(!gate.current(token))return false;
  release.run();
  if(!gate.current(token))return false;
  if(!gate.playing(token)&&startIssued&&gate.audioProgress(token,finalPosition))started.run();
  return gate.done(token);
 }
 private void reportStarted(PlaybackTicket.Token token,boolean demo){if(demo){if(preview!=null)preview.accept("Воспроизведение…");}else{trace("audio_start",activeUnit,0);host.started(token.id);}}
 private boolean current(MediaPlayer p,PlaybackTicket.Token token){return !closed&&p==output&&gate.current(token);}
 private void pollStart(MediaPlayer p,PlaybackTicket.Token token,boolean demo){if(!current(p,token))return;try{if(p.isPlaying()&&gate.audioProgress(token,p.getCurrentPosition())){reportStarted(token,demo);return;}main.postDelayed(()->pollStart(p,token,demo),25);}catch(RuntimeException e){trace("start_probe_error",activeUnit,0);failed(token,demo,"Не подтверждено начало звука");}}
 private void failed(PlaybackTicket.Token token,boolean demo,String message){if(!gate.current(token))return;clearAhead();gate.cancel();try{releaseOutput();}catch(RuntimeException e){unsafe=true;message="Остановка звука не подтверждена; перезапустите приложение";}if(demo){Consumer<String> c=preview;preview=null;if(c!=null)c.accept(message);host.previewFinished();}else host.failed(token.id,message);}
 private void releaseOutput(){MediaPlayer old=output;if(old!=null){try{if(outputPrepared){old.stop();if(old.isPlaying())throw new IllegalStateException();}old.reset();old.release();output=null;outputPrepared=false;outputStarted=false;UNKNOWN.remove(this);unsafe=false;}catch(RuntimeException failure){unsafe=true;UNKNOWN.add(this);throw new IllegalStateException("WAV stop not confirmed");}}if(transientWav!=null){transientWav.delete();transientWav=null;}}
 @Override public void stop(){clearAhead();gate.cancel();activeUnit=null;activeTicket=null;if(work!=null){work.cancel();work=null;}Consumer<String> c=preview;preview=null;RuntimeException failure=null;try{system.stop();}catch(RuntimeException e){failure=e;}try{releaseOutput();}catch(RuntimeException e){failure=e;}if(c!=null){c.accept("Прослушивание остановлено");host.previewFinished();}if(failure!=null)throw failure;if(unsafe||isOutputBlocked())throw new IllegalStateException("WAV stop not confirmed");}
 void stopPreview(){if(preview!=null)stop();}
 void close(){try{stop();}finally{closed=true;models.removePackListener(packListener);system.close();}}
}
