package ru.bububu.radio.core;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import org.junit.Assume;
import ru.bububu.radio.sources.*;
import static org.junit.Assert.*;

/** Producer HOST fixture construction only. No device intake or production fixture hook. */
public class Source45QaFixturesTest {
    @Test public void sealSyntheticLongAndUnknownV2BookAndCasForUnique45()throws Exception{
        String job=System.getenv("BUBUBU_JOB45");Assume.assumeTrue(job!=null);
        Path root=Path.of(job,"fixtures","source-v2"),notes=root.resolve("notes"),cas=root.resolve("source-cas");
        Files.createDirectories(notes);Files.createDirectories(cas);
        Book45Test.Store store=new Book45Test.Store(notes.toFile());NoteBook book=new NoteBook(store);book.enable45();
        SourceRecord source=Book45Test.source(45);List<String> summary=new ArrayList<>();
        SourceItem longItem=SourceItem.create(source.id,"long-month",null,"https://example.org/long-month","Синтетическая длинная статья",-1,-1,1,null,null);
        SourceItem unknownItem=SourceItem.create(source.id,"unknown-warning",null,"https://example.org/unknown-warning","Синтетическая статья без даты",-1,-1,1,null,null);
        book.commitSource(source,List.of(longItem,unknownItem));SourceBodyCache cache=new SourceBodyCache(cas.toFile());
        for(SourceItem item:List.of(longItem,unknownItem)){
            boolean isLong=item==longItem;
            String body=isLong?("Это синтетический текст для проверки полного сохранения статьи. ").repeat(500):"Это синтетический короткий материал без известной даты. Ссылка [1–3].";
            SourcePublication45.Fact original=SourcePublication45.parse(SourcePublication45.Role.ORIGINAL_PUBLISHED,isLong?"2011-02":"","fixture",item.url,"synthetic",false);
            List<SourcePublication45.Fact> facts=isLong?List.of(SourcePublication45.parse(SourcePublication45.Role.REPUBLISHED,"2026-10-25T10:00:00+03:00","fixture",item.url,"synthetic-republication",false)):List.of();
            SourcePublication45 publication=new SourcePublication45(original,facts,"a".repeat(64),"synthetic45","","b".repeat(64));
            SourceVersionRecord v=SourceVersionRecord.source45(source,item,new ExtractionResult(body,item.title,item.title,"full","","source45-html","source45-v1"),publication,isLong?SourcePublication45.Context.DATED:SourcePublication45.Context.UNKNOWN_MANUAL,1);
            if(isLong){assertTrue(v.spokenText.length()>20000);assertTrue(v.spokenText.length()<100000);assertEquals(SourcePublication45.Precision.MONTH,v.publication.primary.precision);}
            else assertTrue(v.spokenText.substring(0,v.bodyStart).contains("дата исходной публикации не подтверждена"));
            book.saveSourceVersion(v.withPayload(null));cache.put(v);book.selectSourceVersion(cache.get(v.withPayload(null)),false);
            Files.write(root.resolve(isLong?"long-source-v2.txt":"unknown-warning-source-v2.txt"),v.spokenText.getBytes(StandardCharsets.UTF_8));
            summary.add(item.itemKey+"\trecordKey="+v.recordKey+"\tversion="+v.versionKey+"\tcanonicalUTF16="+v.spokenText.length()+"\tbodySpan="+v.bodyStart+":"+v.bodyEnd+"\tbodySHA="+v.bodyHash+"\tcontext="+v.contextClass);
        }
        Files.write(notes.resolve(NoteMetadataReader.BASE),store.bytes);NoteBook reopened=new NoteBook(store);assertEquals(4,reopened.schema());assertEquals(2,reopened.materials().size());assertNull(reopened.migrationWarning());
        for(NoteBook.Material material:reopened.materials()){SourceVersionRecord full=cache.get(reopened.sourceVersion(material.sourceVersionKey));assertEquals(material.text,full.spokenText);assertEquals(material.version,full.versionKey);}
        Files.write(root.resolve("SOURCE_FIXTURE_BINDING.tsv"),summary,StandardCharsets.UTF_8);
    }
}
