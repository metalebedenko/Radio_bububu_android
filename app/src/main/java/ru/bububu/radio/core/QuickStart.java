package ru.bububu.radio.core;

import java.util.*;
import java.util.function.Predicate;
import ru.bububu.radio.sources.ChannelProfile;
import ru.bububu.radio.sources.SourceRecord;

/** One explicit intent; events and generic retries never reserve a new listening command. */
public final class QuickStart {
    public static final class Candidate {
        public final String station,article,version,source,channel;
        public final ChannelProfile.Kind kind;
        public final boolean full,current;
        public Candidate(String station,String article,String version,String source,String channel,ChannelProfile.Kind kind,boolean full,boolean current){
            this.station=station;this.article=article;this.version=version;this.source=source;this.channel=channel;this.kind=kind;this.full=full;this.current=current;
        }
    }
    public enum Readiness { SELECTION_CHANGED, VOICE_CHANGED, WAIT_VOICE, WAIT_ARTICLE, READY }
    public static final class Ticket {
        public final String run,selection,voice,explicitSource;
        public final Set<String> sources;
        public final long epoch;
        private Candidate candidate;private boolean ready,playerEntered;
        public Readiness readiness(String currentSelection,String currentVoice){
            if(!selection.equals(currentSelection))return Readiness.SELECTION_CHANGED;
            if(!voice.equals(currentVoice))return Readiness.VOICE_CHANGED;
            if(!ready)return Readiness.WAIT_VOICE;
            return candidate==null?Readiness.WAIT_ARTICLE:Readiness.READY;
        }
        private Ticket(String run,String selection,String voice,Collection<String> sources,String explicitSource,long epoch,Candidate current){
            this.run=run;this.selection=selection;this.voice=voice;this.sources=Collections.unmodifiableSet(new HashSet<>(sources));this.explicitSource=explicitSource;this.epoch=epoch;candidate=current;
        }
    }
    private Ticket pending,awaitingAudio;private long epoch;
    /** Bind primary Listen to the visible station; only extend this request, never saved choices. */
    public static String primarySource(boolean mix,String visibleStation,Collection<SourceRecord> activeSources,Set<String> downloadSources){
        if(mix)return null;
        for(SourceRecord source:activeSources)if(source.stationId.equals(visibleStation)){downloadSources.add(source.id);return source.id;}
        return null;
    }
    public static boolean waitForBatch(boolean running,boolean preferCached,Candidate current){return running&&!(preferCached&&current!=null&&current.current);}
    /** Reuse the live air's existing order only when no material/selection is being resumed. */
    public static AirMix.Item existingAir(AirMix mix,List<AirMix.Item> pool,List<String> stations,PlayerController.Snapshot state,PlayerController.SavedState progress){
        if(state.article!=null||state.selectionRequired||stations.isEmpty())return null;
        String from=stations.contains(state.station.id)?state.station.id:stations.get(0);
        return mix.nextInAir(pool,stations,from,null,progress,false);
    }
    /** Same eligibility as an explicit cached Listen, with no shuffle/history mutation. */
    public static boolean hasExistingAir(AirMix mix,List<AirMix.Item> pool,List<String> stations,PlayerController.Snapshot state,PlayerController.SavedState progress){
        if(state.article!=null||state.selectionRequired||stations.isEmpty())return false;
        String from=stations.contains(state.station.id)?state.station.id:stations.get(0);
        return mix.hasNextInAir(pool,stations,from,null,progress,false);
    }
    public Ticket begin(String run,String selection,String voice,Collection<String> sources,String explicitSource,Candidate current){
        awaitingAudio=null;pending=new Ticket(run,selection,voice,sources,explicitSource,++epoch,current);return pending;
    }
    public boolean pending(){return pending!=null||awaitingAudio!=null;}
    public boolean hasCandidate(String run){return pending!=null&&pending.run.equals(run)&&pending.candidate!=null||awaitingAudio!=null&&awaitingAudio.run.equals(run)&&awaitingAudio.candidate!=null;}
    public boolean awaitingAudio(){return awaitingAudio!=null;}
    public long awaitingEpoch(){return awaitingAudio==null?-1:awaitingAudio.epoch;}
    public boolean owns(String run){return pending!=null&&pending.run.equals(run)||awaitingAudio!=null&&awaitingAudio.run.equals(run);}
    /** A source refresh cannot recall a cached target already transferred to speech. */
    public boolean batchMayRevoke(String run){return owns(run)&&!(awaitingAudio!=null&&awaitingAudio.run.equals(run)&&awaitingAudio.candidate!=null&&awaitingAudio.candidate.current);}
    public void actualStarted(){awaitingAudio=null;}
    /** Only the epoch carried into this Player.play call may own terminal settlement. */
    public boolean playerEntered(long expectedEpoch){if(awaitingAudio==null||awaitingAudio.epoch!=expectedEpoch)return false;awaitingAudio.playerEntered=true;return true;}
    /** A deferred text-recovery pass can terminate before any audio callback exists. */
    public boolean settleStopped(boolean playing,boolean seeking,String requestId){if(awaitingAudio==null||!awaitingAudio.playerEntered||playing||seeking||requestId!=null)return false;revoke();return true;}
    public Ticket ticket(){return pending;}
    public void revoke(){pending=null;awaitingAudio=null;++epoch;}
    public void terminal(String run){if(owns(run))revoke();}
    public void ready(Ticket ticket,String voice){if(ticket==pending&&ticket.voice.equals(voice))ticket.ready=true;}
    public boolean published(String run,Candidate item){
        Ticket t=pending;
        if(t==null||!t.run.equals(run)||item.current||!item.full||item.source==null||!t.sources.contains(item.source))return false;
        boolean allowed=t.explicitSource!=null?t.explicitSource.equals(item.source):(item.kind==ChannelProfile.Kind.ARTICLE_ANALYSIS||item.kind==ChannelProfile.Kind.NEWS_BRIEF);
        if(!allowed)return false;
        if(t.candidate==null)t.candidate=item;
        return true;
    }
    public Candidate take(Ticket ticket,String selection,String voice,Predicate<Candidate> admission){
        if(ticket==null||ticket!=pending)return null;
        switch(ticket.readiness(selection,voice)){
            case SELECTION_CHANGED: case VOICE_CHANGED: revoke();return null;
            case WAIT_VOICE: case WAIT_ARTICLE: return null;
            default: break;
        }
        if(!admission.test(ticket.candidate)){revoke();return null;}
        Candidate value=ticket.candidate;pending=null;awaitingAudio=ticket;return value;
    }
}
