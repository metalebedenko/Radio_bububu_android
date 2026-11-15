package ru.bububu.radio.sources;

import org.junit.Test;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class Source45PublicationTest {
    static final String CONFIG="a".repeat(64),URL="https://example.org/story";
    static SourcePublication45.Fact date(String raw){return SourcePublication45.parse(SourcePublication45.Role.ORIGINAL_PUBLISHED,raw,"fixture",URL,"time@datetime",false);}
    static SourcePublication45 publication(String raw){return new SourcePublication45(date(raw),List.of(),CONFIG,"test-rule","","b".repeat(64));}
    static SourceVersionRecord version(String body,SourcePublication45 p,SourcePublication45.Context context,ChannelProfile.Kind kind)throws Exception{
        SourceRecord source=SourceRecord.create("rss","https://example.org/feed","Издание","https://example.org/feed","closed45",true);
        source=new ChannelProfile("test45","test45","1",source.key,source.adapterId,"source45-v1",kind).bind(source);
        SourceItem item=SourceItem.create(source.id,"item",null,URL,"Заголовок",-1,-1,0,null,null);
        ExtractionResult r=new ExtractionResult(body,"Заголовок","Заголовок","full","","source45-html","source45-v1");
        return SourceVersionRecord.source45(source,item,r,p,context,123);
    }
    @Test public void monthOriginalAndRepublicationAreAudibleBeforeExactBody()throws Exception{
        SourcePublication45 p=new SourcePublication45(date("2011-02"),List.of(SourcePublication45.parse(SourcePublication45.Role.REPUBLISHED,"2026-10-25T08:00:00Z","rss",URL,"item/pubDate",false)),CONFIG,"vokrug45","","b".repeat(64));
        SourceVersionRecord v=version("Тело статьи.",p,SourcePublication45.Context.DATED,ChannelProfile.Kind.ARTICLE_ANALYSIS);
        assertNull(p.primary.epochMillis);assertNull(p.primary.offsetSeconds);
        assertEquals("Оригинальная публикация: февраль две тысячи одиннадцатого года. Повторная публикация: двадцать пятое октября две тысячи двадцать шестого года.\n\n",p.context(v.contextClass));
        assertEquals("Тело статьи.",v.body());assertEquals(SourceLimits.sha256(v.body()),v.bodyHash);assertTrue(v.spokenText.indexOf("февраль")<v.bodyStart);
        assertEquals(v.versionKey,v.withPayload(null).withPayload(v.spokenText).versionKey);assertSame(v,v.asArticle().sourceVersion);
    }
    @Test public void instantCalendarUsesSourceOffsetWhileIdentityKeepsExactTime()throws Exception{
        SourcePublication45 a=publication("2026-10-26T00:30:01.123+03:00"),b=publication("2026-10-26T00:30:02.123+03:00");
        assertEquals(a.context(SourcePublication45.Context.DATED),b.context(SourcePublication45.Context.DATED));
        assertTrue(a.context(SourcePublication45.Context.DATED).contains("двадцать шестое октября"));assertFalse(a.context(SourcePublication45.Context.DATED).contains("UTC"));assertNotEquals(a.identityDigest(),b.identityDigest());
        assertEquals(Integer.valueOf(10800),a.primary.offsetSeconds);assertEquals(Instant.parse("2026-10-25T21:30:01.123Z").toEpochMilli(),a.primary.epochMillis.longValue());
    }
    @Test public void publicationCodecRetainsFactsButCaptureDoesNotAlterVersionIdentity()throws Exception{
        SourcePublication45 a=publication("2026-10-25"),b=new SourcePublication45(a.primary,a.facts,CONFIG,a.ruleRevision,a.conflict,"c".repeat(64));
        assertArrayEquals(a.encode(),SourcePublication45.decode(a.encode()).encode());assertEquals(a.identityDigest(),b.identityDigest());
        assertEquals(version("Тело",a,SourcePublication45.Context.DATED,ChannelProfile.Kind.ARTICLE_ANALYSIS).versionKey,version("Тело",b,SourcePublication45.Context.DATED,ChannelProfile.Kind.ARTICLE_ANALYSIS).versionKey);
        byte[] trailing=Arrays.copyOf(a.encode(),a.encode().length+1);assertThrows(java.io.IOException.class,()->SourcePublication45.decode(trailing));
    }
    @Test public void totalCapIncludesDateAndWarningAndBodySpanSurvivesCache()throws Exception{
        SourcePublication45 p=publication("2011-02");SourceVersionRecord small=version("Я",p,SourcePublication45.Context.DATED,ChannelProfile.Kind.ARTICLE_ANALYSIS);int prefix=small.bodyStart;
        for(int n:new int[]{99999,100000}){SourceVersionRecord v=version("а".repeat(n-prefix),p,SourcePublication45.Context.DATED,ChannelProfile.Kind.ARTICLE_ANALYSIS);assertEquals(n,v.spokenLength);assertEquals(n-prefix,v.body().length());}
        assertThrows(SourceFailure.class,()->version("а".repeat(100001-prefix),p,SourcePublication45.Context.DATED,ChannelProfile.Kind.ARTICLE_ANALYSIS));
        SourceVersionRecord warning=version("Я",publication("invalid"),SourcePublication45.Context.UNKNOWN_MANUAL,ChannelProfile.Kind.ARTICLE_ANALYSIS);assertTrue(warning.spokenText.contains("не подтверждена"));assertEquals(100000,version("а".repeat(100000-warning.bodyStart),publication("invalid"),SourcePublication45.Context.UNKNOWN_MANUAL,ChannelProfile.Kind.ARTICLE_ANALYSIS).spokenLength);
        assertThrows(SourceFailure.class,()->version("а".repeat(100001-warning.bodyStart),publication("invalid"),SourcePublication45.Context.UNKNOWN_MANUAL,ChannelProfile.Kind.ARTICLE_ANALYSIS));
    }
    static final class Clock implements SourceClock{long wall=Instant.parse("2026-10-26T00:00:00Z").toEpochMilli(),mono=100;public long wallMillis(){return wall;}public long monotonicMillis(){return mono;}}
    @Test public void unknownInvalidFutureArticleIsNeverAutomaticallyAdmitted()throws Exception{
        Clock c=new Clock();SourceAdmission45 gate=new SourceAdmission45(c);
        for(String raw:List.of("","broken","2026-10-26T00:00:00.001Z","2026-10-27","2026-11","2027")){
            SourcePublication45 p=publication(raw);assertThrows(SourceFailure.class,()->gate.context(p,false));SourcePublication45.Context mode=gate.context(p,true);
            SourceVersionRecord v=version("Тело",p,mode,ChannelProfile.Kind.ARTICLE_ANALYSIS);gate.require(v,true);assertThrows(SourceFailure.class,()->gate.require(v,false));
        }
        gate.require(version("Тело",publication("2011-02"),SourcePublication45.Context.DATED,ChannelProfile.Kind.ARTICLE_ANALYSIS),false);
    }
    @Test public void briefSevenDayBoundaryAndClockDriftHaveNoManualBypass()throws Exception{
        Clock c=new Clock();SourceAdmission45 gate=new SourceAdmission45(c);String at=Instant.ofEpochMilli(c.wall-SourceAdmission45.BRIEF_AGE_MILLIS).toString();SourceVersionRecord v=version("Новость",publication(at),SourcePublication45.Context.DATED,ChannelProfile.Kind.NEWS_BRIEF);gate.require(v,false);gate.require(v,true);
        c.wall++;c.mono++;assertThrows(SourceFailure.class,()->gate.require(v,true));
        for(String raw:List.of("2026-10-25","","2026-10-26T00:00:01Z")){SourcePublication45 p=publication(raw);SourceVersionRecord b=version("Новость",p,gate.context(p,true),ChannelProfile.Kind.NEWS_BRIEF);assertThrows(SourceFailure.class,()->gate.require(b,true));}
        c.wall+=60001;assertNull(gate.now());assertThrows(SourceFailure.class,()->gate.context(publication("2011-02"),false));
    }
    @Test public void strictJsonRejectsDuplicatesDepthBadUtf8AndTrailingTokens()throws Exception{
        assertEquals("ок",BoundedJson45.object(BoundedJson45.parse("{\"value\":\"ок\"}")).get("value"));
        for(String bad:List.of("{\"a\":1,\"a\":2}","[01]","[NaN]","[] trailing","[\"\\uD800\"]","[".repeat(66)+"0"+"]".repeat(66)))assertThrows(SourceFailure.class,()->BoundedJson45.parse(bad));
        assertThrows(SourceFailure.class,()->BoundedJson45.parse(new byte[]{(byte)0xc3,(byte)0x28}));
    }
}
