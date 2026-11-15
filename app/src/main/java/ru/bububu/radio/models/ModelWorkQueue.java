package ru.bububu.radio.models;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** One worker: foreground, discardable speculation, and one deferred setup owner. */
public final class ModelWorkQueue {
    public interface Work<T>{T run(ModelPackStore.Token token)throws Exception;}
    public interface Result<T>{void success(T value);void error(String message);}
    private final Thread owner=Thread.currentThread();
    private final Executor worker;private final Consumer<Runnable> dispatch;private final Consumer<Object> discard;
    private Job<?> active,pending;
    private Idle<?> idle;
    public ModelWorkQueue(Executor worker,Consumer<Runnable> dispatch,Consumer<Object> discard){this.worker=worker;this.dispatch=dispatch;this.discard=discard;}
    public boolean busy(){check();return active!=null||pending!=null||idle!=null;}
    public boolean foregroundBlocked(){check();return pending!=null&&!pending.token.cancelled()||active!=null&&!active.speculative&&!active.token.cancelled();}
    public boolean promote(ModelPackStore.Token token){check();if(active==null||active.token!=token||token.cancelled())return false;active.speculative=false;return true;}
    /** Single setup owner; repeated ensure calls coalesce until completion or owner cancellation. */
    public <T> ModelPackStore.Token submitIdle(Work<T> work,Result<T> result,Runnable waiting){
        check();
        if(idle!=null&&!idle.token.cancelled())return idle.token;
        Idle<?> previous=idle;
        if(previous!=null&&(active==null||active.idle!=previous))
            dispatch.accept(()->completeIdle(previous,null,"Операция отменена"));
        Idle<T> request=new Idle<>(work,result,waiting);idle=request;
        if(active!=null||pending!=null)waiting.run();
        drainIdle();return request.token;
    }
    public <T> ModelPackStore.Token submit(boolean speculative,Work<T> work,Result<T> result){
        check();Job<T> job=new Job<>(speculative,work,result);
        if(active==null){start(job);return job.token;}
        if(speculative)return null;
        if(active.speculative||active.token.cancelled()){
            active.token.cancel();
            if(pending==null||pending.token.cancelled()){
                Job<?> old=pending;pending=job;if(old!=null)dispatch.accept(()->old.result.error("Операция отменена"));
                if(active.idle!=null&&!active.idle.token.cancelled())active.idle.waiting.run();
                return job.token;
            }
        }
        dispatch.accept(()->result.error("Дождитесь завершения текущей операции"));return job.token;
    }
    private <T> void start(Job<T> job){
        active=job;
        try{worker.execute(()->{
            T value=null;String error=null;
            try{if(job.token.cancelled())throw new IOException("Операция отменена");value=job.work.run(job.token);if(job.token.cancelled())throw new IOException("Операция отменена");}
            catch(Throwable failure){error=failure instanceof IOException&&failure.getMessage()!=null?failure.getMessage():"Операция не выполнена; исходные данные сохранены";}
            T answer=value;String problem=error;dispatch.accept(()->finish(job,answer,problem));
        });}catch(RuntimeException rejected){dispatch.accept(()->finish(job,null,"Рабочая очередь недоступна"));}
    }
    private <T> void finish(Job<T> job,T answer,String error){
        check();if(active!=job){if(answer!=null)discard.accept(answer);return;}
        active=null;Job<?> next=pending;pending=null;
        if(next!=null){if(next.token.cancelled())next.result.error("Операция отменена");else start(next);}
        try{
            if(job.idle!=null){
                Idle<T> request=job.idle;
                if(job.token.cancelled()&&!request.token.cancelled()){
                    if(answer!=null)discard.accept(answer); // Foreground yield, not a setup error.
                }else{
                    if(idle==request)idle=null;
                    completeIdle(request,answer,request.token.cancelled()?"Операция отменена":error);
                }
            }else if(job.token.cancelled()||error!=null){if(answer!=null)discard.accept(answer);job.result.error(job.token.cancelled()?"Операция отменена":error);}
            else job.result.success(answer);
        }finally{drainIdle();}
    }
    private void drainIdle(){
        if(active!=null||pending!=null||idle==null)return;
        Idle<?> request=idle;
        if(request.token.cancelled()){idle=null;completeIdle(request,null,"Операция отменена");}
        else startIdle(request);
    }
    private <T> void startIdle(Idle<T> request){start(new Job<>(request));}
    private <T> void completeIdle(Idle<T> request,T answer,String error){
        if(request.delivered){if(answer!=null)discard.accept(answer);return;}
        request.delivered=true;
        if(error!=null){if(answer!=null)discard.accept(answer);request.result.error(error);}
        else{request.token.done();request.result.success(answer);}
    }
    private void check(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Model queue owner");}
    private static final class Idle<T>{final ModelPackStore.Token token=new ModelPackStore.Token();final Work<T> work;final Result<T> result;final Runnable waiting;boolean delivered;Idle(Work<T> work,Result<T> result,Runnable waiting){this.work=work;this.result=result;this.waiting=waiting;}}
    private static final class Job<T>{final ModelPackStore.Token token;final Work<T> work;final Result<T> result;final Idle<T> idle;boolean speculative;Job(boolean speculative,Work<T> work,Result<T> result){this.speculative=speculative;this.work=work;this.result=result;this.token=new ModelPackStore.Token();idle=null;}Job(Idle<T> idle){this.speculative=true;this.work=idle.work;this.result=idle.result;this.token=new ModelPackStore.Token(idle.token);this.idle=idle;}}
}
