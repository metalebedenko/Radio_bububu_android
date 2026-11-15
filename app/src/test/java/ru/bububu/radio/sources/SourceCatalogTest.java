package ru.bububu.radio.sources;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class SourceCatalogTest {
    @Test public void acceptedBuiltInsMatchApprovedNamesUrlsFormatsAndKnownAliasExactly() {
        // Closed45 approved names/endpoints; custom persisted lifetimes remain independent.
        String[][] approved={
                {"Элементы — объясняющие статьи о науке","https://elementy.ru/rss/news"},
                {"Биомолекула — статьи и интервью","https://biomolecula.ru/articles"},
                {"Нож — статьи о культуре и обществе","https://knife.media/feed/"},
                {"Российская газета — короткие новости","https://rg.ru/xml/index.xml"},
                {"Ведомости — открытые короткие новости","https://www.vedomosti.ru/rss/news"},
                {"Интерфакс — короткие новости","https://www.interfax.ru/rss"},
                {"iXBT — короткие новости технологий","https://www.ixbt.com/export/news/rss.xml"},
                {"TechInsider — короткие научные новости","https://www.techinsider.ru/out/public-feed-all-news.xml"},
                {"Forbes — открытые статьи и мнения","https://www.forbes.ru/"},
                {"Поиск — биографии и история науки","https://poisknews.ru/wp-json/wp/v2/posts?per_page=3&categories=1814&_fields=id%2Cdate%2Cdate_gmt%2Cmodified%2Cmodified_gmt%2Clink%2Ctitle%2Ccontent%2Cexcerpt%2Ccategories%2Ctags"},
                {"Прошлое — архив истории повседневности","https://proshloe.com/wp-json/wp/v2/posts?per_page=3&categories=900&_fields=id%2Cdate%2Cdate_gmt%2Cmodified%2Cmodified_gmt%2Clink%2Ctitle%2Ccontent%2Cexcerpt%2Ccategories%2Ctags"},
                {"Наука ТВ — объяснения и интервью","https://naukatv.ru/articles"},
                {"Инвест-Форсайт — интервью","https://www.if24.ru/wp-json/wp/v2/posts?per_page=3&_fields=id%2Cdate%2Cdate_gmt%2Cmodified%2Cmodified_gmt%2Clink%2Ctitle%2Ccontent%2Cexcerpt%2Ccategories%2Ctags&categories=5"},
                {"Вокруг света — истории и интервью","https://www.vokrugsveta.ru/rss-feeds/rss.xml"},
                {"АиФ — короткие новости экономики","https://aif.ru/money"},
                {"РИА Новости — короткие новости","https://ria.ru/export/rss2/archive/index.xml"},
                {"Hi-Tech Mail — короткие новости технологий","https://hi-tech.mail.ru/"},
                {"RT на русском — короткие новости","https://russiatoday.ru/rss"},
                {"Коммерсантъ — короткие новости","https://www.kommersant.ru/rss/news.xml"},
                {"Профиль — короткие новости","https://profile.ru/feed/short/"},
                {"Hi-Tech Mail — обзоры устройств, включая рекламные","https://hi-tech.mail.ru/review/"},
                {"Хабр — железо: обзоры и ретроспективы авторов","https://habr.com/ru/rss/flows/hardware_and_gadgets/articles/?fl=ru"}
        };
        List<SourceCatalog.Entry> entries=SourceCatalog.entries();assertEquals(approved.length,entries.size());Set<String> unique=new HashSet<>();
        for(int i=0;i<approved.length;i++) {
            SourceCatalog.Entry entry=entries.get(i);assertEquals(approved[i][0],entry.name);assertEquals(approved[i][1],entry.input);assertTrue(entry.description.contains("полный текст"));assertFalse(entry.telegram);assertTrue(unique.add(entry.input));
            SourceRecord existing=source("rss",entry.input);assertSame(existing,SourceCatalog.connected(entry,List.of(existing)));
        }
        Set<String> keys=new HashSet<>();
        for(int i=0;i<entries.size();i++){SourceRecord identity=source("rss",entries.get(i).input);assertTrue(keys.add(identity.key));for(int j=0;j<entries.size();j++)if(i!=j)assertNull(SourceCatalog.connected(entries.get(j),List.of(identity)));}
        SourceRecord lowerCaseAlias=source("rss","https://www.kommersant.ru/rss/news.xml");
        assertSame(lowerCaseAlias,SourceCatalog.connected(entries.get(18),List.of(lowerCaseAlias)));
        assertNull(SourceCatalog.connected(entries.get(18),List.of(source("rss","https://www.kommersant.ru/rss/other.xml"))));
        assertThrows(UnsupportedOperationException.class,()->entries.remove(0));
    }
    @Test public void readingCatalogueAndMatchingChoicesPreservesDurableBookExactly() {
        final byte[][] saved={null};final int[] writes={0};
        ru.bububu.radio.core.NoteBook.Storage storage=new ru.bububu.radio.core.NoteBook.Storage(){
            public byte[] load(){return saved[0]==null?null:saved[0].clone();}
            public void save(byte[] bytes){writes[0]++;saved[0]=bytes.clone();}
        };
        ru.bububu.radio.core.NoteBook book=new ru.bububu.radio.core.NoteBook(storage);
        ru.bububu.radio.core.NoteBook.Note note=book.beginRecording("Own retained note",null,null,null,1);
        book.finishRecording(note.id,0,false,false,"");book.edit(note.id,"Own retained note","Own retained text.");
        byte[] before=saved[0].clone();int beforeWrites=writes[0];
        for(int read=0;read<2;read++)for(SourceCatalog.Entry entry:SourceCatalog.entries())
            assertNull(SourceCatalog.connected(entry,book.sources()));
        assertArrayEquals(before,saved[0]);assertEquals(beforeWrites,writes[0]);
        assertTrue(book.sources().isEmpty());assertTrue(book.materials().isEmpty());
        assertEquals("Own retained text.",book.find(note.id).text);
        // Construction/lookup is pure URL identity work; even an unresolved .invalid name is accepted without DNS/HTTP.
        assertNull(SourceCatalog.connected(new SourceCatalog.Entry("Own choice","https://catalogue-must-not-resolve.invalid/rss",false,""),book.sources()));
        assertArrayEquals(before,saved[0]);assertEquals(beforeWrites,writes[0]);
    }
    @org.junit.Rule public final org.junit.rules.TemporaryFolder retainedSourceTemp=new org.junit.rules.TemporaryFolder();
    @Test public void excludedPresetKeepsPersistedVzConnectionAndCachedBodyByteExact()throws Exception {
        final byte[][] saved={null};final int[] writes={0};
        ru.bububu.radio.core.MigrationCapsuleStore capsules=new ru.bububu.radio.core.MigrationCapsuleStore(retainedSourceTemp.newFolder("capsules"));
        ru.bububu.radio.core.NoteBook.MigrationStorage storage=new ru.bububu.radio.core.NoteBook.MigrationStorage(){
            public byte[] load(){return saved[0]==null?null:saved[0].clone();}
            public void save(byte[] bytes){writes[0]++;saved[0]=bytes.clone();}
            public ru.bububu.radio.core.MigrationStamp prepareMigration(byte[] baseline,int target){return capsules.prepare(baseline,target,new byte[][]{saved[0],null,null});}
            public void verifyMigrationStamp(ru.bububu.radio.core.MigrationStamp stamp){capsules.verify(stamp);}
        };
        ru.bububu.radio.core.NoteBook book=new ru.bububu.radio.core.NoteBook(storage);
        String url="https://vz.ru/export/yandex.xml";
        SourceRecord vz=SourceRecord.create("rss",url,"Own retained VZ",url,"feed-generic-v1",true)
                .withState(true,"kept-etag","kept-modified",1234,"DL1|TIMEOUT|0|TIMEOUT");
        SourceItem item=SourceItem.create(vz.id,"own:vz-item","own-provider","https://vz.ru/news/own-fixture.html","Own headline",1,2,3,"own-date",null);
        SourceVersionRecord version=SourceCoordinator.version(vz,item,new ExtractionResult("Own retained body. Whole fixture.","Own headline","Own headline","unknown","DESCRIPTION_COMPLETENESS_UNKNOWN","rss_inline","feed-inline-v1"));
        item=item.withLatest(version.recordKey);
        book.commitSource(vz,List.of(item),List.of(version.withPayload(null)));
        java.io.File cacheDir=retainedSourceTemp.newFolder("cas");SourceBodyCache cache=new SourceBodyCache(cacheDir);cache.put(version);
        java.io.File cas=new java.io.File(cacheDir,SourceLimits.sha256(version.recordKey)+".cas");
        byte[] before=saved[0].clone(),cached=java.nio.file.Files.readAllBytes(cas.toPath());int beforeWrites=writes[0];
        assertEquals(22,SourceCatalog.entries().size());
        for(SourceCatalog.Entry entry:SourceCatalog.entries()){assertNotEquals(url,entry.input);assertNull(SourceCatalog.connected(entry,book.sources()));}
        assertSame(vz,SourceCatalog.connected(vz,book.sources()));
        assertSame(vz,SourceCatalog.connected(new SourceCatalog.Entry("Own explicit VZ",url,false,""),book.sources()));
        assertArrayEquals(before,saved[0]);assertEquals(beforeWrites,writes[0]);assertArrayEquals(cached,java.nio.file.Files.readAllBytes(cas.toPath()));
        ru.bububu.radio.core.NoteBook reopened=new ru.bububu.radio.core.NoteBook(storage);SourceRecord retained=reopened.source(vz.id);
        assertNotNull(retained);assertTrue(retained.enabled);assertTrue(retained.catalog);assertEquals(vz.stationId,retained.stationId);
        assertEquals(vz.url,retained.url);assertEquals(vz.aliases,retained.aliases);assertEquals(vz.name,retained.name);
        assertEquals(vz.etag,retained.etag);assertEquals(vz.lastModified,retained.lastModified);assertEquals(vz.lastSuccess,retained.lastSuccess);assertEquals(vz.lastError,retained.lastError);
        assertEquals(version.spokenText,cache.get(reopened.sourceVersion(version.recordKey)).spokenText);
        assertArrayEquals(before,saved[0]);assertEquals(beforeWrites,writes[0]);
    }
    private SourceRecord source(String type,String url,String... aliases) {
        SourceRecord value=SourceRecord.create(type,url,"Своё имя",url,"feed-generic-v1",false);
        return new SourceRecord(value.id,value.stationId,type,url,value.name,url,value.adapterId,value.adapterVersion,Arrays.asList(aliases),false,false,null,null,0,null);
    }
    @Test public void canonicalAliasAndDisabledCustomConnectionAreAlreadyConnectedWithoutMutation() {
        SourceCatalog.Entry entry=new SourceCatalog.Entry("Каталог","https://EXAMPLE.org/feed#section",false,"Доступный текст");
        SourceRecord active=source("rss","https://example.org/new-feed","https://example.org/feed");
        assertSame(active,SourceCatalog.connected(entry,List.of(active)));
        assertFalse(active.enabled);assertFalse(active.catalog);assertEquals("Своё имя",active.name);
        assertNull(SourceCatalog.connected(new SourceCatalog.Entry("Другой","https://example.org/other",false,""),List.of(active)));
    }
    @Test public void knownCatalogueRedirectAndPreviewDiscoveredAliasBothResolveSameConnection() {
        SourceRecord active=source("atom","https://example.org/canonical");
        SourceCatalog.Entry entry=new SourceCatalog.Entry("Издание","https://example.org/feed",false,"","https://example.org/canonical");
        assertSame(active,SourceCatalog.connected(entry,List.of(active)));
        SourceRecord preview=source("atom","https://example.org/feed","https://example.org/canonical");
        assertSame(active,SourceCatalog.connected(preview,List.of(active)));
        assertNull(SourceCatalog.connected(source("rss",active.key),List.of(active)));
    }
    @Test public void telegramFormsShareIdentityWithoutConflatingRssAndChannel() {
        SourceCatalog.Entry entry=new SourceCatalog.Entry("Канал","https://t.me/s/Own_News",true,"");
        SourceRecord channel=source("telegram_public","telegram_public:own_news");
        assertSame(channel,SourceCatalog.connected(entry,List.of(channel)));
        assertSame(channel,SourceCatalog.connected(new SourceCatalog.Entry("Канал","@OWN_NEWS",true,""),List.of(channel)));
        assertNull(SourceCatalog.connected(entry,List.of(source("rss","https://t.me/s/own_news"))));
    }
    @Test public void absentActiveConnectionDoesNotReuseHistoricalLifetimeOrChangeCatalogue() {
        SourceCatalog.Entry entry=new SourceCatalog.Entry("Издание","https://example.org/rss",false,"");
        SourceRecord retired=source("rss",entry.input),fresh=source("rss",entry.input);
        assertNull(SourceCatalog.connected(entry,Collections.emptyList()));
        assertSame(retired,SourceCatalog.connected(entry,List.of(retired)));
        assertSame(fresh,SourceCatalog.connected(entry,List.of(fresh)));
        assertNotEquals(retired.id,fresh.id);
        assertThrows(UnsupportedOperationException.class,()->SourceCatalog.entries().add(entry));
    }
    @Test public void invalidBuiltInIdentityIsRejectedBeforeAnyPreview() {
        for(String input:List.of("http://example.org/feed","https://user:secret@example.org/feed","https://127.0.0.1/feed"))
            assertThrows(IllegalArgumentException.class,()->new SourceCatalog.Entry("Издание",input,false,""));
        assertThrows(IllegalArgumentException.class,()->new SourceCatalog.Entry(" ","https://example.org/feed",false,""));
        assertThrows(IllegalArgumentException.class,()->new SourceCatalog.Entry("Канал","https://t.me/+private",true,""));
    }
}
