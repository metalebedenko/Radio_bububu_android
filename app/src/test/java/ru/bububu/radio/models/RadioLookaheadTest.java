package ru.bububu.radio.models;
import org.junit.Test;import org.junit.Rule;import org.junit.rules.TemporaryFolder;import java.io.*;import java.util.*;import ru.bububu.radio.core.*;import ru.bububu.radio.core.PlayerController.*;import static org.junit.Assert.*;
public final class RadioLookaheadTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private SpeechUnit unit(int sentence,int fragment){Article a=Article.fromSentences("article","v1","Тест","Один два три четыре.","Пять шесть семь восемь.");SpeechSentence s=new SpeechSentence("station",a,new PlaybackMembership.Binding(7,"source-version","source"),sentence);return new SpeechUnit(s,TextSegments.fragments(s.text(),10),fragment);}
    private File wav(int size)throws Exception{File f=temp.newFile();try(RandomAccessFile r=new RandomAccessFile(f,"rw")){r.setLength(size);}return f;}
    private SpeechUnit target(RadioLookahead.Slot s){return unit(s.sentence,s.fragment);}
    @Test public void slotsIncludeInFlightAndStayBoundedAcrossSentenceBoundary()throws Exception {
        RadioLookahead b=new RadioLookahead();SpeechUnit current=unit(0,unit(0,0).fragments.size()-1);RadioLookahead.Slot first=b.reserve(current,"pack/voice");assertEquals(1,first.sentence);assertEquals(0,first.fragment);assertEquals(1,b.size());
        SpeechUnit u=target(first);assertTrue(b.planned(first,u));assertTrue(b.ready(first,u,"pack/voice",RadioLookahead.hash(u),wav(100)));assertEquals(u.fragments,b.plan(u.sentence,"pack/voice"));
        RadioLookahead.Slot second=b.reserve(current,"pack/voice");assertNotNull(second);assertEquals(2,b.size());assertNull(b.reserve(current,"pack/voice"));SpeechUnit v=target(second);assertTrue(b.ready(second,v,"pack/voice",RadioLookahead.hash(v),wav(100)));assertEquals(200,b.bytes());
    }
    @Test public void transferProtectsActiveFileFromClearAndDuplicateLateResult()throws Exception {
        RadioLookahead b=new RadioLookahead();RadioLookahead.Slot s=b.reserve(unit(0,0),"pack/voice");SpeechUnit u=target(s);File f=wav(100);assertTrue(b.ready(s,u,"pack/voice",RadioLookahead.hash(u),f));
        assertSame(s,b.claim(u,"pack/voice"));assertSame(f,b.transfer(s));b.clear();assertTrue(f.exists());assertFalse(b.ready(s,u,"pack/voice",RadioLookahead.hash(u),f));assertTrue(f.exists());assertNull(b.claim(u,"pack/voice"));
    }
    @Test public void clearBeforeReadyCancelsOwnedWorkAndDeletesStaleWav()throws Exception {
        RadioLookahead b=new RadioLookahead();RadioLookahead.Slot s=b.reserve(unit(0,0),"pack/voice");s.work=new ModelPackStore.Token();b.clear();assertTrue(s.work.cancelled());File f=wav(100);assertFalse(b.ready(s,target(s),"pack/voice",RadioLookahead.hash(target(s)),f));assertFalse(f.exists());assertEquals(0,b.size());
    }
    @Test public void exactInFlightTargetCanBeClaimedOnceThenDeliveredToActive()throws Exception {
        RadioLookahead b=new RadioLookahead();RadioLookahead.Slot s=b.reserve(unit(0,0),"pack/voice");SpeechUnit u=target(s);assertTrue(b.planned(s,u));assertSame(s,b.claim(u,"pack/voice"));assertEquals(0,b.size());assertTrue(b.valid(s));
        File f=wav(100);assertTrue(b.ready(s,u,"pack/voice",RadioLookahead.hash(u),f));assertSame(f,b.transfer(s));assertFalse(b.valid(s));assertNull(b.claim(u,"pack/voice"));
    }
    @Test public void foregroundCancelledSpeculationBecomesAMissNotACurrentError() {
        RadioLookahead b=new RadioLookahead();RadioLookahead.Slot s=b.reserve(unit(0,0),"pack/voice");s.work=new ModelPackStore.Token();SpeechUnit u=target(s);s.work.cancel();assertNull(b.claim(u,"pack/voice"));assertEquals(0,b.size());assertFalse(b.valid(s));
    }
    @Test public void packVoiceAndPartitionMismatchNeverConsumeWrongFile()throws Exception {
        RadioLookahead b=new RadioLookahead();RadioLookahead.Slot s=b.reserve(unit(0,0),"pack/voice");SpeechUnit u=target(s);File f=wav(100);assertTrue(b.ready(s,u,"pack/voice",RadioLookahead.hash(u),f));assertNull(b.claim(u,"other-pack/voice"));assertFalse(f.exists());
        s=b.reserve(unit(0,0),"pack/voice");u=target(s);f=wav(100);assertTrue(b.ready(s,u,"pack/voice",RadioLookahead.hash(u),f));SpeechUnit changed=new SpeechUnit(u.sentence,TextSegments.fragments(u.sentence.text(),8),u.fragment);assertNull(b.claim(changed,"pack/voice"));assertFalse(f.exists());
    }
    @Test public void oversizedAndMismatchedReadyRemainExplicitFutureFailures()throws Exception {
        RadioLookahead b=new RadioLookahead();RadioLookahead.Slot s=b.reserve(unit(0,0),"pack/voice");SpeechUnit u=target(s);File big=wav(1048577);assertFalse(b.ready(s,u,"pack/voice",RadioLookahead.hash(u),big));assertFalse(big.exists());assertTrue(s.finished);assertNotNull(s.error);assertNull(b.reserve(unit(0,0),"pack/voice"));
        assertSame(s,b.claim(u,"pack/voice"));assertEquals(0,b.size());
    }
}
