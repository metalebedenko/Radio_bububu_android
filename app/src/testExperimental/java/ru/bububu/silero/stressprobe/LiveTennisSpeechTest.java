package ru.bububu.silero.stressprobe;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.PlayerController.SpeechSentence;
import ru.bububu.radio.core.PlayerController.SpeechUnit;
import ru.bububu.radio.core.TextSegments;
import ru.bububu.radio.sources.SourceSpeechPlan45;
import ru.bububu.radio.sources.SourceVersionRecord;

public class LiveTennisSpeechTest {
    @Test public void realSourceUnitsKeepEachCanonicalRankAndSetThroughFragmentation() throws Exception {
        String body="25-я ракетка и 83-я ракетка разыграли 49-е место; обладатель 24 титулов играл под 10-м номером, "
                +"как и в прошлом сезоне, ".repeat(12)
                +"матч завершился со счётом 6:3, 6:4. Другой игрок уступил в трёх сетах – 6:2, 3:6, 3:6.";
        SourceVersionRecord version=C8SeeAlsoTest.version(body,"Турнир");
        Article article=version.asArticle();
        FrontendPlan.Data data=C7LongAtomTest.data();
        List<String> covered=new ArrayList<>(),projected=new ArrayList<>(),rankSpans=new ArrayList<>(),setSpans=new ArrayList<>();
        boolean split=false;
        for (int i=0;i<article.sentences.size();i++) {
            SpeechSentence sentence=C8SeeAlsoTest.sentence(article,i);
            SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.sentence(sentence);
            for (SourceSpeechPlan45.Atom atom:plan.tokens) {
                String span=plan.canonical.substring(atom.start,atom.end);
                if ((atom.rule.equals("ordinal") || atom.rule.equals("governed-cardinal"))) rankSpans.add(span);
                if (atom.rule.equals("tennis-score")) setSpans.add(span);
            }
            // Short request pieces exercise the production unit's offset reconstruction.
            List<String> fragments=RadioFragments.source45(data,plan,64);
            TextSegments.checkedFragments(sentence.text(),fragments,64);
            if (fragments.size()>2) split=true;
            for (int j=0;j<fragments.size();j++) {
                SpeechUnit unit=new SpeechUnit(sentence,fragments,j);
                String spoken=SourceSpeechPlan45.project(unit);
                C7LongAtomTest.carrier(data,spoken);
                covered.add(unit.text);
                projected.add(spoken);
            }
        }
        assertTrue(split);
        assertEquals(article.text.replaceAll("\\s+",""),String.join("",covered).replaceAll("\\s+",""));
        assertEquals(body,version.body());
        assertEquals(List.of("25-я","83-я","49-е","24","10-м"),rankSpans);
        assertEquals(List.of("6:3","6:4","6:2","3:6","3:6"),setSpans);
        String spoken=String.join(" ",projected);
        for (String expected:List.of("двадцать пятая","восемьдесят третья","сорок девятое","под десятым номером","обладатель двадцати четырёх титулов","шесть три","шесть четыре","шесть два","три шесть")) {
            assertTrue(expected,spoken.contains(expected));
        }
        assertFalse(spoken.contains("двоеточие"));
    }

    @Test public void symbolicRatiosAndUnknownFormsKeepTheirExistingReadings() {
        for (String raw:List.of("Отношения 6:3, 6:4.","Таблица со счётом 6:3, 6:4.","Матч шёл в 17:40 мск.",
                "В сетах — 6:3:4.","В сетах — 6:3, 6:40.","Код ID/25-я ракетка, X25-я ракетка, A.25-я ракетка.",
                "2.25-я ракетка, 1 125-я ракетка, 25-ж модель и 83-язычная версия.","Ключ https://example.org/6:3.")) {
            SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.compile(raw);
            assertFalse(raw,plan.tokens.stream().anyMatch(a->List.of("ordinal","governed-cardinal","tennis-score").contains(a.rule)));
        }
        String ratios="Отношения 6:3, 6:4.";
        assertEquals(2,SourceSpeechPlan45.compile(ratios).project(0,ratios.length()).split("двоеточие",-1).length-1);
        assertTrue(SourceSpeechPlan45.compile("Матч в 17:40 мск.").tokens.stream().anyMatch(a->a.rule.equals("source49-clock-v1")));
        String outside="После матча обсуждали параметры; величины со счётом 6:3, 6:4.";
        assertFalse(SourceSpeechPlan45.compile(outside).tokens.stream().anyMatch(a->a.rule.equals("tennis-score")));
        IllegalArgumentException refused=assertThrows(IllegalArgumentException.class,()->SourceSpeechPlan45.compile("Матч со счетом 6:3, цена $1.50."));
        assertEquals("UNSUPPORTED_SOURCE_CURRENCY_SUFFIX",refused.getMessage());
    }
}
