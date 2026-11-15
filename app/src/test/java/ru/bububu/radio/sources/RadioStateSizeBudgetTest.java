package ru.bububu.radio.sources;
import org.junit.Test;
import java.util.*;
import ru.bububu.radio.core.*;
import static org.junit.Assert.*;
public class RadioStateSizeBudgetTest {
    @Test public void boundIncludesAllFutureCoverageNotOnlyCurrentProgress() {
        Article small=Article.fromSentences("id","v","title","Речь.");
        Article many=Article.fromSentences("id","v","title","Раз.","Два.","Три.");
        long a=RadioStateSizeBudget.fullyCompletedBytes(Arrays.asList(new Station("s","S",Arrays.asList(small))));
        long b=RadioStateSizeBudget.fullyCompletedBytes(Arrays.asList(new Station("s","S",Arrays.asList(many))));
        assertEquals(4,b-a);
    }
    @Test public void escapingAndLongIdentifierCanRejectBeforeSelection() {
        String[] sentences=new String[100]; Arrays.fill(sentences,"Текст.");
        List<Article> items=new ArrayList<>();
        for(int i=0;i<10000;i++) items.add(Article.fromSentences("x".repeat(500)+i,"v".repeat(500),"T",sentences));
        assertThrows(IllegalStateException.class,()->RadioStateSizeBudget.requireFits(Arrays.asList(new Station("s","S",items))));
    }
}
