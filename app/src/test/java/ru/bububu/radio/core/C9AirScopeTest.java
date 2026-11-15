package ru.bububu.radio.core;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import ru.bububu.radio.sources.*;

public class C9AirScopeTest {
    static SourceVersionRecord version(SourceProfiles45.Profile p,SourceItem item,String body)throws Exception {
        SourcePublication45 publication=new SourcePublication45(SourcePublication45.parse(SourcePublication45.Role.ORIGINAL_PUBLISHED,"2026-10-28","fixture",item.url,"date",false),List.of(),p.configSha,"fixture","","a".repeat(64));
        return SourceVersionRecord.source45(p.seed(),item,new ExtractionResult(body,"Обзор","Обзор","full","","source45-html",SourceProfiles45.PARSER),publication,SourcePublication45.Context.DATED,1);
    }
    @Test public void cachedCatalogIsNotAirAndLastMaterialRemovalDropsChipsAndMixPool()throws Exception {
        SourceProfiles45.Profile p=SourceProfiles45.byId("biomolecula-articles");SourceRecord source=p.seed();NoteBook book=FirstAirProductTest.book(SourceProfiles45.six());
        SourceItem a=SourceItem.create(source.id,"air",null,"https://biomolecula.ru/articles/air","Обзор",-1,-1,1),b=SourceItem.create(source.id,"cached",null,"https://biomolecula.ru/articles/cached","Обзор",-1,-1,1);
        SourceVersionRecord air=version(p,a,"Факт. Продолжение."),cached=version(p,b,"Другой факт.");
        book.mergeSourceItems(source.id,List.of(a.withLatest(air.recordKey),b.withLatest(cached.recordKey)),List.of(air,cached),null,null,1,null);
        assertTrue(OrdinaryRadio.sourceAir(book,true,null).isEmpty());
        assertTrue(OrdinaryRadio.catalog(book).stream().noneMatch(s->OrdinaryRadio.inAir(s,book::retiredStation)));
        book.selectSourceVersion(air,false);
        List<AirMix.Item> pool=OrdinaryRadio.sourceAir(book,true,null);assertEquals(1,pool.size());assertEquals(air.versionKey,pool.get(0).version);
        book.setSourceRead(source.id,a.itemKey,true);
        assertEquals(1,OrdinaryRadio.sourceAir(book,true,null).size());
        assertEquals(1,OrdinaryRadio.catalog(book).stream().filter(s->OrdinaryRadio.inAir(s,book::retiredStation)).count());
        book.removeSourceMaterial(source.stationId,air.articleId,null);
        assertTrue(OrdinaryRadio.sourceAir(book,true,null).isEmpty());assertNotNull(book.sourceVersion(cached.recordKey));
        assertTrue(OrdinaryRadio.catalog(book).stream().noneMatch(s->OrdinaryRadio.inAir(s,book::retiredStation)));
    }
    @Test public void virtualAirStationIsVisibleButDoesNotBroadenSourceMixEligibility(){
        NoteBook book=FirstAirProductTest.book(SourceProfiles45.six());
        book.addSavedMaterial(NoteBook.MY_TEXTS,"Мои тексты",Article.fromSentences("own","1","Свой текст","Один факт."));
        assertTrue(OrdinaryRadio.catalog(book).stream().anyMatch(s->s.id.equals(NoteBook.MY_TEXTS)&&OrdinaryRadio.inAir(s,book::retiredStation)));
        assertTrue(OrdinaryRadio.sourceAir(book,true,null).isEmpty());
        assertFalse(OrdinaryRadio.inAir(new Station("A","Диагностика",List.of(C6PlaybackTest.a("fixture"))),id->false));
        assertFalse(OrdinaryRadio.inAir(new Station("retired","Удалена",List.of(C6PlaybackTest.a("old"))),id->true));
    }
}
