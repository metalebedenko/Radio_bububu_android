package ru.bububu.radio.sources;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.time.*;

public class BasicIsoOffset45Test {
    static SourcePublication45.Fact parse(String raw){return Source45PublicationTest.date(raw);}
    static final SourceClock CLOCK=new SourceClock(){public long wallMillis(){return Instant.parse("2026-10-26T16:00:00Z").toEpochMilli();}public long monotonicMillis(){return 0;}};
    @Test public void basicPositiveAndNegativeOffsetsHaveSameEpochAndKeepExactRaw(){
        for(String raw:List.of("2026-10-26T15:43:33+0300","2026-10-26T11:47:02+0300","2026-10-26T12:37:30+0300","2026-10-26T15:43:33-0430","2026-10-26T15:43+0000")){
            SourcePublication45.Fact a=parse(raw),b=parse(raw.substring(0,raw.length()-2)+":"+raw.substring(raw.length()-2));
            assertEquals(SourcePublication45.Precision.INSTANT,a.precision);assertEquals(b.epochMillis,a.epochMillis);assertEquals(b.offsetSeconds,a.offsetSeconds);assertEquals(b.value,a.value);assertEquals(raw,a.raw);
            assertEquals("fixture",a.authority);assertEquals("time@datetime",a.selector);assertEquals("date45-iso-basic-offset-v1",a.rule);
        }
    }
    @Test public void invalidOffsetAndMissingZoneAreStillUnknown(){
        for(String raw:List.of("2026-10-26T12:00:00+2460","2026-10-26T12:00:00+2360","2026-10-26T12:00:00+1801","2026-10-26T12:00:00+030","2026-10-26T12:00:00","2026-02-30T12:00:00+0300"))assertEquals(raw,SourcePublication45.Precision.UNKNOWN,parse(raw).precision);
    }
    @Test public void sevenDayFutureAndUnknownBriefGuardsRemain()throws Exception {
        SourceAdmission45 admission=new SourceAdmission45(CLOCK);
        for(String raw:List.of("2026-10-26T15:43:33+0300","2026-10-19T19:00:00+0300"))admission.require(Source45PublicationTest.version("Текст",Source45PublicationTest.publication(raw),SourcePublication45.Context.DATED,ChannelProfile.Kind.NEWS_BRIEF),false);
        for(String raw:List.of("2026-10-19T18:59:59+0300","2026-10-27T15:43:33+0300","2026-10-26T12:00:00")){
            SourcePublication45 p=Source45PublicationTest.publication(raw);SourceVersionRecord v=Source45PublicationTest.version("Текст",p,p.primary.precision==SourcePublication45.Precision.UNKNOWN?SourcePublication45.Context.UNKNOWN_MANUAL:SourcePublication45.Context.DATED,ChannelProfile.Kind.NEWS_BRIEF);
            assertThrows(SourceFailure.class,()->admission.require(v,false));assertThrows(SourceFailure.class,()->admission.require(v,true));
        }
    }
    @Test public void modifiedAndRepublishedNeverReplaceOriginal()throws Exception {
        SourcePublication45 p=new SourcePublication45(parse("2026-10-26T12:00:00"),List.of(SourcePublication45.parse(SourcePublication45.Role.MODIFIED,"2026-10-26T15:37:30+0300","meta",Source45PublicationTest.URL,"modified",false),SourcePublication45.parse(SourcePublication45.Role.REPUBLISHED,"2026-10-26T15:37:30+0300","meta",Source45PublicationTest.URL,"republished",false)),Source45PublicationTest.CONFIG,"test","INVALID","b".repeat(64));
        assertEquals(SourcePublication45.Precision.UNKNOWN,p.primary.precision);assertEquals(SourcePublication45.Role.MODIFIED,p.facts.get(1).role);
        assertThrows(SourceFailure.class,()->new SourceAdmission45(CLOCK).context(p,false));assertArrayEquals(p.encode(),SourcePublication45.decode(p.encode()).encode());
    }
}
