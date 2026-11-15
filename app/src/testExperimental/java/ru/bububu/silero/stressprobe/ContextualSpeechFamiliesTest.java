package ru.bububu.silero.stressprobe;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import ru.bububu.radio.core.*;
import ru.bububu.radio.models.SileroSpeechInput;
import ru.bububu.radio.sources.*;

public class ContextualSpeechFamiliesTest {
    @Test public void contextualCasesWorkAcrossUnrelatedSubjectsAndExactCanonicalCuts() throws Exception {
        Map<String,String> cases=new LinkedHashMap<>();
        cases.put("Открылась 25-я выставка.","двадцать пятая");
        cases.put("Назначили 49-е заседание.","сорок девятое");
        cases.put("В 10-м классе проходит урок.","десятом");
        cases.put("Под 3-м пунктом указана цена.","третьим");
        cases.put("К 21 участнику подошёл тренер.","двадцати одному");
        cases.put("Из 24 отделений поступили письма.","двадцати четырёх");
        cases.put("До 503 работ вошло в каталог.","пятисот трёх");
        cases.put("К 302 авторам обратилась редакция.","трёмстам двум");
        cases.put("Осталась 1 книга.","одна");
        cases.put("Доставили 2 коробки.","две");
        for(Map.Entry<String,String> row:cases.entrySet()){
            String raw=row.getKey();
            List<ContextualSpeech.Reading> readings=ContextualSpeech.scan(raw);
            assertEquals(raw,1,readings.size());
            ContextualSpeech.Reading r=readings.get(0);
            assertEquals(raw,row.getValue(),r.spoken);
            assertTrue(r.contextStart<=r.start&&r.contextEnd>=r.end);
            assertEquals(row.getValue(),SileroSpeechInput.forSentence(raw).project(r.start,r.end));
            Article article=C8SeeAlsoTest.version(raw,"Дайджест").asArticle();
            PlayerController.SpeechSentence sentence=C8SeeAlsoTest.sentence(article,article.sentences.size()-1);
            SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.sentence(sentence);
            for(int max:List.of(40,64,512)) {
                List<String> parts=RadioFragments.source45(C7LongAtomTest.data(),plan,max);
                assertEquals(parts,TextSegments.checkedFragments(sentence.text(),parts,max));
                StringBuilder spoken=new StringBuilder();
                for(int i=0;i<parts.size();i++){
                    String projected=SourceSpeechPlan45.project(new PlayerController.SpeechUnit(sentence,parts,i));
                    C7LongAtomTest.carrier(C7LongAtomTest.data(),projected);
                    spoken.append(' ').append(projected);
                }
                assertTrue(raw+" → "+spoken,spoken.toString().contains(row.getValue()));
            }
        }
    }
    @Test public void contextEvidenceDoesNotReclassifyIdentifiersDatesOrAmbiguousInflections(){
        for(String raw:List.of("в 90-е годы","код 3-я партия","с 2 коробками","параметр v2.3-е место",
                "русский URL https://site.test/90-е","перешли с 1.02 на 1.20","[3–5]","смесь 6:3, 6:4",
                "31.11.2026","2.4e-3 кг","артикул A-25-я выставка","до 2027 года","метка 007",
                "обсудили 1 книге","ПО-2-я выставка","под 2-м неизвестно","от 3 мая")){
            assertTrue(raw,ContextualSpeech.scan(raw).isEmpty());
        }
        assertEquals("UNSUPPORTED_SOURCE_CALENDAR_DATE",assertThrows(IllegalArgumentException.class,
                ()->SourceSpeechPlan45.compile("Дата 31.11.2026.")).getMessage());
    }
}
