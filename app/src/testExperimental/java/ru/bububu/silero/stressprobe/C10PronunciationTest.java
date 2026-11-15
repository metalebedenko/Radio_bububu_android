package ru.bububu.silero.stressprobe;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import ru.bububu.radio.core.*;
import ru.bububu.radio.sources.*;

public class C10PronunciationTest {
    static byte[] data()throws Exception {try(InputStream in=C10PronunciationTest.class.getResourceAsStream("/"+SourcePronunciation48.ASSET)){assertNotNull(in);return in.readAllBytes();}}
    static SourceSpeechPlan45.Plan body(String text)throws Exception {
        Article a=C8SeeAlsoTest.version(text,"Обзор").asArticle();
        for(int n=0;n<a.sentences.size();n++)if(a.sentences.get(n).start>=a.sourceVersion.bodyStart)return SourceSpeechPlan45.sentence(C8SeeAlsoTest.sentence(a,n));
        throw new AssertionError("No body sentence");
    }
    static String projected(String text)throws Exception {SourceSpeechPlan45.Plan plan=body(text);return plan.project(0,plan.canonical.length());}
    @Test public void newExplicitVowelInitialismIsSourceOnlyAndEveryLetterIsOneAtom()throws Exception {
        SourceSpeechPlan45.Plan plan=body("В отчёте МГУ приведён факт.");
        assertEquals("В отчёте МГУ приведён факт.",plan.canonical);
        SourceSpeechPlan45.Atom acronym=plan.atoms.stream().filter(a->plan.canonical.substring(a.start,a.end).equals("МГУ")).findFirst().orElseThrow();
        assertEquals("эм, гэ, у",acronym.spoken);assertTrue(acronym.rule.startsWith("lexicon-spell:"));
        assertEquals("МГУ",SourceInitialisms45.expand("МГУ"));assertEquals("МГУ",ru.bububu.radio.models.SileroSpeechInput.project("МГУ"));
        C7SpeechProjectionTest.carrier(acronym.spoken);
    }
    @Test public void fallbackUsesOriginalCaseBodyContextAndDoesNotTouchHeadingsOrCodes()throws Exception {
        assertTrue(projected("В отчёте БЖФ приведён факт.").contains("бэ, жэ, эф"));
        for(String raw:List.of("БЖФ","НОВОСТИ БЖФ","Исследование НАУКИ.","Длина 5 см.","20 ВТ.","Имя А. С. Пушкин.","XIX","COVID-19","МВД1","МВД́","αМВДβ")) {
            List<SourcePronunciation48.Replacement> replacements=SourcePronunciation48.source(raw,new SourcePronunciation48.Context(raw,0,true,""));
            assertFalse(raw,replacements.stream().anyMatch(r->r.rule.equals("source-consonant-caps-v1")));
        }
        String heading="НОВОСТИ БЖФ";Article article=C8SeeAlsoTest.version("Обычный текст.",heading).asArticle();
        SourceSpeechPlan45.Plan meta=SourceSpeechPlan45.sentence(C8SeeAlsoTest.sentence(article,0));assertFalse(meta.project(0,meta.canonical.length()).contains("бэ, жэ, эф"));
        assertFalse(projected("20 ВТ.").contains("вэ, тэ"));
        assertEquals("Мвд",SourceInitialisms45.expand("Мвд"));
    }
    @Test public void loaderRejectsCorruptionAndFailureNeverReusesCachedSentence()throws Exception {
        byte[] valid=data();String text=new String(valid,StandardCharsets.UTF_8);
        SourcePronunciation48.Lexicon lexicon=SourcePronunciation48.parse(new ByteArrayInputStream(valid));assertTrue(lexicon.entries.size()>=10);assertEquals(SourceLimits.hash(valid),lexicon.sha256);
        for(String bad:List.of(text.replace("BUBUBU_PRONUNCIATION\t1", "BUBUBU_PRONUNCIATION\t9"),text+"SPELL\tМВД\t-\tANY\t1\tduplicate\n",text.replace("REFERENCE_NEXT_SENTENCE","ARBITRARY_REGEX"),text.replace("Смотрите.","skip text")))
            assertThrows(IllegalArgumentException.class,()->SourcePronunciation48.parse(new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8))));
        assertThrows(IllegalArgumentException.class,()->SourcePronunciation48.parse(new ByteArrayInputStream(new byte[262145])));
        Article article=C8SeeAlsoTest.version("Отчёт МВД.","Обзор").asArticle();PlayerController.SpeechSentence owner=C8SeeAlsoTest.sentence(article,1);SourceSpeechPlan45.sentence(owner);
        try {
            SourcePronunciation48.install(new ByteArrayInputStream(new byte[]{0}));
            assertEquals("UNSUPPORTED_PRONUNCIATION_DATA_FORMAT",assertThrows(IllegalArgumentException.class,()->SourceInitialisms45.expand("МВД")).getMessage());
            assertEquals("UNSUPPORTED_PRONUNCIATION_DATA_FORMAT",assertThrows(IllegalArgumentException.class,()->SourceSpeechPlan45.sentence(owner)).getMessage());
            assertTrue(SourceSpeechPlan45.runtimeStamp().contains("UNSUPPORTED_PRONUNCIATION_DATA_FORMAT"));
            SourcePronunciation48.unavailable();assertEquals("UNSUPPORTED_PRONUNCIATION_DATA_IO",assertThrows(IllegalArgumentException.class,()->SourcePronunciation48.dictionary()).getMessage());
        } finally {SourcePronunciation48.install(new ByteArrayInputStream(valid));}
        assertTrue(SourceSpeechPlan45.sentence(owner).project(0,owner.text().length()).contains("эм, вэ, дэ"));
    }
    @Test public void dataShaChangesPlanAndRuntimeStampEvenAtSameVersion()throws Exception {
        byte[] valid=data();Article article=C8SeeAlsoTest.version("Отчёт МВД.","Обзор").asArticle();PlayerController.SpeechSentence owner=C8SeeAlsoTest.sentence(article,1);
        SourceSpeechPlan45.Plan before=SourceSpeechPlan45.sentence(owner);String stamp=SourceSpeechPlan45.runtimeStamp();
        try {SourcePronunciation48.install(new ByteArrayInputStream((new String(valid,StandardCharsets.UTF_8)+"# test-only metadata delta\n").getBytes(StandardCharsets.UTF_8)));
            assertNotEquals(stamp,SourceSpeechPlan45.runtimeStamp());SourceSpeechPlan45.Plan after=SourceSpeechPlan45.sentence(owner);assertNotSame(before,after);assertNotEquals(before.hash,after.hash);assertEquals(before.canonical,after.canonical);
        } finally {SourcePronunciation48.install(new ByteArrayInputStream(valid));}
    }
    @Test public void closedTextRecipesKeepCanonicalCoverageAndUnitsAndInitialsUnexpanded()throws Exception {
        byte[] valid=data();StringBuilder seed=new StringBuilder("BUBUBU_PRONUNCIATION\t1\ttest-context\n");
        for(String line:new String(valid,StandardCharsets.UTF_8).split("\n")){String[] f=line.split("\t",-1);if(f.length==6&&(f[4].equals("1")||f[3].equals("REFERENCE_NEXT_SENTENCE")))seed.append(line).append('\n');}
        seed.append("WORD\tНАТО\tнато\tANY\t0\tTEST\nAMBIGUOUS\tМИР\t-\tANY\t0\tTEST\n");
        for(Map.Entry<String,String> e:Map.of("т. е.","то есть","т.к.","так как","и т.д.","и так далее","и т. п.","и тому подобное").entrySet())seed.append("TEXT\t").append(e.getKey()).append('\t').append(e.getValue()).append("\tINLINE\t0\tTEST\n");
        try {
            SourcePronunciation48.install(new ByteArrayInputStream(seed.toString().getBytes(StandardCharsets.UTF_8)));
            for(Map.Entry<String,String> row:Map.of("Это верно, т. е. ясно.","то есть","Это так, т.к. известно.","так как","Даны факты и т.д.","и так далее.","Даны примеры и т. п.","и тому подобное.").entrySet()){
                Article article=C8SeeAlsoTest.version(row.getKey(),"Обзор").asArticle();StringBuilder spoken=new StringBuilder(),raw=new StringBuilder();
                for(int n=0;n<article.sentences.size();n++)if(article.sentences.get(n).start>=article.sourceVersion.bodyStart){SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.sentence(C8SeeAlsoTest.sentence(article,n));SourceSpeechPlan45.Parts parts=plan.parts(512,s->new FrontendPlan(new FrontendPlan.Data(),s));spoken.append(String.join(" ",parts.spoken)).append(' ');raw.append(String.join("",parts.raw));}
                assertTrue(spoken.toString(),spoken.toString().contains(row.getValue()));assertEquals(row.getKey(),article.sourceVersion.body());
                assertEquals(row.getKey().replaceAll("\\s+",""),raw.toString().replaceAll("\\s+",""));
            }
            assertEquals("нато",projected("НАТО"));assertEquals("МИР",projected("МИР"));
            for(String raw:List.of("5 см.","г.","им.","А. С. Пушкин.","См. Другой текст."))assertFalse(raw,projected(raw).contains("Смотрите"));
        } finally {SourcePronunciation48.install(new ByteArrayInputStream(valid));}
    }
    @Test public void actualAcceptedAssetHas143RecordsAndAllActiveReadingsBindOneWholeToken()throws Exception {
        SourcePronunciation48.Lexicon dictionary=SourcePronunciation48.dictionary();assertEquals(143,dictionary.entries.size());int spell=0,word=0,ambiguous=0,text=0,activeText=0;
        for(SourcePronunciation48.Entry entry:dictionary.entries){
            if(entry.kind==SourcePronunciation48.Kind.TEXT){text++;if(entry.context!=SourcePronunciation48.ContextRule.DISABLED)activeText++;continue;}
            if(entry.kind==SourcePronunciation48.Kind.AMBIGUOUS){ambiguous++;assertFalse(SourcePronunciation48.source("В обзоре "+entry.key+" приведён факт.",new SourcePronunciation48.Context("В обзоре "+entry.key+" приведён факт.",0,true,"")).stream().anyMatch(r->r.spoken.equals(SourcePronunciation48.letters(entry.key))));continue;}
            if(entry.kind==SourcePronunciation48.Kind.SPELL)spell++;else word++;
            SourceSpeechPlan45.Plan plan=body("В обзоре "+entry.key+" приведён факт.");
            SourceSpeechPlan45.Atom atom=plan.atoms.stream().filter(a->plan.canonical.substring(a.start,a.end).equals(entry.key)).findFirst().orElseThrow();assertEquals(entry.key,entry.reading,atom.spoken);C7SpeechProjectionTest.carrier(atom.spoken);
        }
        assertEquals(79,spell);assertEquals(35,word);assertEquals(14,ambiguous);assertEquals(15,text);assertEquals(12,activeText);
        assertTrue(SourcePronunciation48.status().contains("records=143 spell=79 word=35 ambiguous=14 text=15 textActive=12 legacy=9"));
    }
    @Test public void everyActiveTextVariantPassesActualFramerAndAllCanonicalBodySentences()throws Exception {
        for(SourcePronunciation48.Entry entry:SourcePronunciation48.dictionary().entries){
            if(entry.kind!=SourcePronunciation48.Kind.TEXT||entry.context==SourcePronunciation48.ContextRule.DISABLED)continue;
            String body=entry.context==SourcePronunciation48.ContextRule.REFERENCE_NEXT_SENTENCE?entry.key+" также: Обзор.":"Это факт, "+entry.key+" известно.";
            SourceVersionRecord version=C8SeeAlsoTest.version(body,"Обзор");Article article=version.asArticle();StringBuilder raw=new StringBuilder(),spoken=new StringBuilder();boolean matched=false;
            for(int n=0;n<article.sentences.size();n++)if(article.sentences.get(n).start>=version.bodyStart){SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.sentence(C8SeeAlsoTest.sentence(article,n));for(SourceSpeechPlan45.Atom atom:plan.atoms)if(atom.rule.startsWith("text-")&&atom.spoken.startsWith(entry.reading))matched=true;SourceSpeechPlan45.Parts parts=plan.parts(512,s->new FrontendPlan(new FrontendPlan.Data(),s));raw.append(String.join("",parts.raw));spoken.append(String.join(" ",parts.spoken)).append(' ');}
            assertTrue(entry.key,matched);assertTrue(entry.key,spoken.toString().contains(entry.reading));assertEquals(entry.key,body.replaceAll("\\s+",""),raw.toString().replaceAll("\\s+",""));assertEquals(TextSegments.sentenceStarts(version.spokenText),version.sentenceStarts);
        }
        for(String raw:List.of("напр. слово.","напр. 42.","5 см. также: длина.","им. Пушкина.","Т. Е. Пушкин."))assertFalse(raw,projected(raw).contains("например"));
    }
    @Test public void c9WaveCannotSatisfyDataBackedSourceUnitAndIsDiscarded()throws Exception {
        Article article=C8SeeAlsoTest.version("В отчёте МГУ приведён факт.","Обзор").asArticle();PlayerController.SpeechSentence owner=C8SeeAlsoTest.sentence(article,1);
        SourceSpeechPlan45.Parts parts=SourceSpeechPlan45.sentence(owner).parts(512,s->new FrontendPlan(new FrontendPlan.Data(),s));PlayerController.SpeechUnit unit=new PlayerController.SpeechUnit(owner,parts.raw,0);
        String oldSpoken=SourceSpeechPlan45.compile(unit.text).project(0,unit.text.length());assertTrue(oldSpoken.contains("МГУ"));
        String oldHash=SourceLimits.snapshotVersion("source-spoken-runtime47-c9-v1",SourceNumbers45.REVISION,article.version,"1","0",Integer.toString(unit.text.length()),oldSpoken);assertNotEquals(oldHash,SourceSpeechPlan45.hash(unit));
        PlayerController.SpeechSentence meta=C8SeeAlsoTest.sentence(article,0);SourceSpeechPlan45.Parts prefix=C8SeeAlsoTest.parts(meta);ru.bububu.radio.models.RadioLookahead cache=new ru.bububu.radio.models.RadioLookahead();String stamp=SourceSpeechPlan45.runtimeStamp();
        ru.bububu.radio.models.RadioLookahead.Slot slot=cache.reserve(new PlayerController.SpeechUnit(meta,prefix.raw,prefix.raw.size()-1),stamp);assertTrue(cache.planned(slot,unit));java.nio.file.Path wave=java.nio.file.Files.createTempFile("c10-stale-",".wav");java.nio.file.Files.write(wave,new byte[46]);assertFalse(cache.ready(slot,unit,stamp,oldHash,wave.toFile()));assertFalse(java.nio.file.Files.exists(wave));
    }
    @Test public void ownerReferenceCuesUseRealCanonicalSentencesAndRefuseUnitsOrUppercaseNext()throws Exception {
        for(String abbreviation:List.of("см.","См."))for(String cue:List.of("далее","выше","ниже","также:")){
            String raw=abbreviation+" "+cue+" приведён пример.";SourceVersionRecord v=C8SeeAlsoTest.version(raw,"Обзор");Article a=v.asArticle();int found=-1;
            for(int n=0;n<a.sentences.size();n++)if(a.sentences.get(n).start>=v.bodyStart&&a.sentences.get(n).text.equals(abbreviation)){found=n;break;}
            assertTrue(raw,found>=0);SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.sentence(C8SeeAlsoTest.sentence(a,found));SourceSpeechPlan45.Parts parts=plan.parts(512,s->new FrontendPlan(new FrontendPlan.Data(),s));
            assertEquals(abbreviation,plan.canonical);assertEquals(List.of(abbreviation),parts.raw);assertEquals(List.of("Смотрите."),parts.spoken);assertEquals(1,plan.atoms.size());assertEquals(0,plan.atoms.get(0).start);assertEquals(3,plan.atoms.get(0).end);assertTrue(a.sentences.get(found+1).text.startsWith(cue));assertEquals(raw,v.body());assertEquals(TextSegments.sentenceStarts(v.spokenText),v.sentenceStarts);
        }
        for(String raw:List.of("5 см. далее приведён пример.","15 см.","5 см. Также приведён пример.","См. Далее приведён пример.","см. Выше приведён пример.","См. Ниже приведён пример.","см. также без двоеточия.","См. ТАКЖЕ: пример.","см. далее-2.","см. нижеприведённое.","5. см. далее приведён пример.","Код A1. см. далее приведён пример.","idсм. далее приведён пример.","СМ. далее приведён пример.","А. С. Иванов.")){
            Article a=C8SeeAlsoTest.version(raw,"Обзор").asArticle();for(int n=0;n<a.sentences.size();n++)if(a.sentences.get(n).start>=a.sourceVersion.bodyStart){SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.sentence(C8SeeAlsoTest.sentence(a,n));assertFalse(raw,plan.atoms.stream().anyMatch(atom->atom.rule.startsWith("text-reference:")));}
        }
    }
}
