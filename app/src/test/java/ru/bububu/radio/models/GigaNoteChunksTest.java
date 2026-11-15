package ru.bububu.radio.models;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GigaNoteChunksTest {
    private static float[] samples(int n) { float[] s=new float[n];for(int i=0;i<n;i++)s[i]=i;return s; }
    private static final class Fake implements GigaNoteChunks.Factory,GigaNoteChunks.Session {
        final List<String> events=new ArrayList<>();final String[] texts;
        int factories,opened,closed,sessionCloses,active,peak,offset,packChecks,nullStream;
        String fail;Consumer<String> observe=e->{};
        Fake(String... texts){this.texts=texts;}
        void event(String name)throws IOException{events.add(name);observe.accept(name);if(name.equals(fail))throw new IOException("injected "+name);}
        public GigaNoteChunks.Session open()throws Exception{factories++;event("load");return this;}
        public GigaNoteChunks.Stream openStream()throws Exception{
            int index=++opened;event("open:"+index);if(index==nullStream)return null;active++;peak=Math.max(peak,active);
            return new GigaNoteChunks.Stream(){
                public void accept(float[] part)throws Exception{
                    assertTrue(part.length>0&&part.length<=480000);
                    for(float value:part)if(value!=offset++)throw new AssertionError("sample order/coverage");
                    event("accept:"+index);
                }
                public void decode()throws Exception{event("decode:"+index);}
                public String text()throws Exception{event("text:"+index);return texts[index-1];}
                public void close()throws Exception{active--;closed++;event("release:"+index);}
            };
        }
        public void close()throws Exception{sessionCloses++;event("recognizer_release");}
        void verify()throws Exception{assertEquals(0,active);event("pack:"+(++packChecks));}
        String run(int n)throws IOException{return GigaNoteChunks.run(samples(n),this,()->{},this::verify);}
    }
    @Test public void fiveRequiredPartitionsHaveExactIntegerCoverageAndNoTinyTail()throws Exception{
        int[] ns={480000,480001,960001,1440001,1920000};
        int[][] expected={{0,480000},{0,240001,480001},{0,320001,640001,960001},{0,360001,720001,1080001,1440001},{0,480000,960000,1440000,1920000}};
        for(int t=0;t<ns.length;t++){
            int[] cuts=GigaNoteChunks.boundaries(ns[t]);assertArrayEquals(expected[t],cuts);assertTrue(cuts.length<=5);
            int min=Integer.MAX_VALUE,max=0;
            for(int i=0;i+1<cuts.length;i++){int length=cuts[i+1]-cuts[i];assertTrue(length>0&&length<=480000);min=Math.min(min,length);max=Math.max(max,length);}
            assertTrue(max-min<=1);if(ns[t]>480000)assertTrue(min>=240000);
        }
        assertArrayEquals(new int[]{0,1},GigaNoteChunks.boundaries(1));
        for(int n:new int[]{-1,0,1920001,Integer.MAX_VALUE})assertThrows(IOException.class,()->GigaNoteChunks.boundaries(n));
    }
    @Test public void allSamplesStayOrderedOneRecognizerAndAtMostOneStream()throws Exception{
        Fake f=new Fake("a","b","c","d");assertEquals("a\nb\nc\nd",f.run(1920000));
        assertEquals(1920000,f.offset);assertEquals(1,f.factories);assertEquals(4,f.opened);assertEquals(4,f.closed);assertEquals(1,f.peak);assertEquals(1,f.sessionCloses);assertEquals(5,f.packChecks);
        assertTrue(f.events.indexOf("release:1")<f.events.indexOf("open:2"));assertEquals("pack:5",f.events.get(f.events.size()-1));
    }
    @Test public void emptyMiddleStillCoversIntervalAndNonemptyTextsKeepExactWhitespace()throws Exception{
        Fake f=new Fake("  первый\n","","второй  ");assertEquals("  первый\n\nвторой  ",f.run(960001));
        assertEquals(960001,f.offset);assertEquals(3,f.closed);assertEquals(1,f.sessionCloses);
    }
    @Test public void allEmptyOrWhitespaceReturnsNoSpeechOnlyAfterCleanup()throws Exception{
        for(String[] texts:new String[][]{{"",""},{" \n","\t"}}){Fake f=new Fake(texts);IOException e=assertThrows(IOException.class,()->f.run(480001));assertTrue(e.getMessage().contains("Речь не распознана"));assertEquals(2,f.closed);assertEquals(1,f.sessionCloses);assertEquals(3,f.packChecks);}
    }
    @Test public void nullTextOrNullStreamAfterFirstSuccessNeverPublishesPrefix()throws Exception{
        for(boolean noStream:new boolean[]{false,true}){
            Fake f=new Fake("first",null);if(noStream)f.nullStream=2;List<String> published=new ArrayList<>();
            assertThrows(IOException.class,()->published.add(f.run(480001)));assertTrue(published.isEmpty());assertEquals(1,f.sessionCloses);assertEquals(noStream?1:2,f.closed);
        }
    }
    @Test public void laterDecodeFailureDiscardsWholeBufferAndClosesBothOwners()throws Exception{
        Fake f=new Fake("first","second");f.fail="decode:2";List<String> published=new ArrayList<>();assertThrows(IOException.class,()->published.add(f.run(480001)));
        assertTrue(published.isEmpty());assertEquals(2,f.closed);assertEquals(1,f.sessionCloses);assertFalse(f.events.contains("text:2"));
    }
    @Test public void cancellationAfterFirstCleanupNeverOpensNextStream()throws Exception{
        Fake f=new Fake("first","second");AtomicBoolean cancelled=new AtomicBoolean();f.observe=e->{if(e.equals("release:1"))cancelled.set(true);};
        assertThrows(IOException.class,()->GigaNoteChunks.run(samples(480001),f,()->{if(cancelled.get())throw new IOException("cancelled");},f::verify));
        assertEquals(1,f.opened);assertEquals(1,f.closed);assertEquals(1,f.sessionCloses);
    }
    @Test public void oneTotalDeadlineCanExpireDuringSecondDecodeWithoutPrefixPublication()throws Exception{
        Fake f=new Fake("first","second","third");long[] now={0};long start=0;f.observe=e->{if(e.equals("decode:2"))now[0]=120001;};List<String> published=new ArrayList<>();
        assertThrows(IOException.class,()->published.add(GigaNoteChunks.run(samples(960001),f,()->{if(now[0]-start>120000)throw new IOException("deadline");},f::verify)));
        assertTrue(published.isEmpty());assertEquals(2,f.opened);assertEquals(2,f.closed);assertEquals(1,f.sessionCloses);
    }
    @Test public void streamOrRecognizerReleaseFailureOverridesSuccessAndNeverPublishes()throws Exception{
        for(String failure:new String[]{"release:2","recognizer_release"}){
            Fake f=new Fake("first","second");f.fail=failure;List<String> published=new ArrayList<>();assertThrows(GigaNoteChunks.CleanupFailure.class,()->published.add(f.run(480001)));
            assertTrue(published.isEmpty());assertEquals(2,f.closed);assertEquals(1,f.sessionCloses);
        }
    }
    @Test public void packFailureAfterSuccessfulChunkOrFinalReleaseDiscardsWholeResult()throws Exception{
        for(String failure:new String[]{"pack:2","pack:3"}){Fake f=new Fake("first","second");f.fail=failure;assertThrows(IOException.class,()->f.run(480001));assertEquals(1,f.sessionCloses);assertEquals(2,f.closed);}
    }
    @Test public void perChunkAndAggregateUtf8LimitsIncludeSeparatingLf()throws Exception{
        Fake tooLong=new Fake("a".repeat(65537));assertThrows(IOException.class,()->tooLong.run(480000));assertEquals(1,tooLong.closed);assertEquals(1,tooLong.sessionCloses);
        Fake aggregate=new Fake("я".repeat(16384),"я".repeat(16384));assertThrows(IOException.class,()->aggregate.run(480001));assertEquals(2,aggregate.closed);assertEquals(1,aggregate.sessionCloses);
        Fake exact=new Fake("я".repeat(16384),"a".repeat(32767));String result=exact.run(480001);assertEquals(65536,result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
    @Test public void invalidLengthAndPreCancelledJobNeverLoadRecognizer()throws Exception{
        Fake f=new Fake("unused");assertThrows(IOException.class,()->f.run(1920001));assertEquals(0,f.factories);
        assertThrows(IOException.class,()->GigaNoteChunks.run(samples(1),f,()->{throw new IOException("cancelled");},f::verify));assertEquals(0,f.factories);
    }
}
