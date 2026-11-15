package ru.bububu.radio.sources;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

/** Fixed-enum observation only. The original verifier alone makes the security decision. */
final class DwHostnameVerifierObserver implements HostnameVerifier {
    interface Observer { void mark(DwNetworkDiagnostics.Stage stage); }
    interface SessionObserver { void afterVerify(String host,SSLSession session); }
    private final HostnameVerifier delegate;
    private final Observer observer;
    private final SessionObserver sessionObserver;
    private DwHostnameVerifierObserver(HostnameVerifier delegate,Observer observer,SessionObserver sessionObserver){this.delegate=delegate;this.observer=observer;this.sessionObserver=sessionObserver;}
    static HostnameVerifier wrap(HostnameVerifier original,boolean capturing,Observer observer){
        return wrap(original,capturing,observer,null);
    }
    static HostnameVerifier wrap(HostnameVerifier original,boolean capturing,Observer observer,SessionObserver sessionObserver){
        if(!capturing)return original;
        try{
            HostnameVerifier result=new DwHostnameVerifierObserver(original,observer,sessionObserver);
            observe(observer,DwNetworkDiagnostics.Stage.HOSTNAME_OBSERVER_ARMED);
            return result;
        }catch(Throwable observationFailure){return original;}
    }
    @Override public boolean verify(String host,SSLSession session){
        observe(observer,DwNetworkDiagnostics.Stage.HOSTNAME_VERIFY_ENTER);
        // Do not catch, inspect or replace the delegate's exception, host or session.
        try{
            boolean accepted=delegate.verify(host,session);
            observe(observer,accepted?DwNetworkDiagnostics.Stage.HOSTNAME_VERIFY_TRUE:DwNetworkDiagnostics.Stage.HOSTNAME_VERIFY_FALSE);
            return accepted;
        }finally{
            try{if(sessionObserver!=null)sessionObserver.afterVerify(host,session);}catch(Throwable observationFailure){/* Preserve the original result/throw. */}
        }
    }
    private static void observe(Observer observer,DwNetworkDiagnostics.Stage stage){
        try{observer.mark(stage);}catch(Throwable observationFailure){/* Observation never changes verification. */}
    }
}
