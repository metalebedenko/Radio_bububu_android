package ru.bububu.silero.stressprobe;
import org.junit.Test;import java.util.*;import static org.junit.Assert.*;
public final class PhraseRulesTest {
    private FrontendPlan.Data data(String pattern) {
        FrontendPlan.Data d=new FrontendPlan.Data();d.variants.put("замок",Arrays.asList("з+амок","зам+ок"));d.phrases.put("замок",pattern);return d;
    }
    @Test public void uniqueApprovedNamedVariantResolvesBeforeNeural() {
        FrontendPlan.Data d=data("(?P<зАмок>древний \\[HOMO\\] замок \\[/HOMO\\])");d.phraseGroups.put("замок",Collections.singletonMap("зАмок",1));
        FrontendPlan p=new FrontendPlan(d,"Древний замок.");assertFalse(p.needsHomo());assertEquals("древний з+амок.",p.homoAnnotated);assertEquals("древний замок.",p.homoAnnotated.replace("+",""));
    }
    @Test public void overlappingAlternativesCannotHideAnAmbiguousStress() {
        FrontendPlan.Data d=data("(?P<зАмок>древний \\[HOMO\\] замок \\[/HOMO\\])|(?P<замОк>древний \\[HOMO\\] замок \\[/HOMO\\])");
        Map<String,Integer> g=new HashMap<>();g.put("зАмок",1);g.put("замОк",2);d.phraseGroups.put("замок",g);
        assertEquals("UNSUPPORTED_PHRASE_AMBIGUOUS",assertThrows(FrontendPlan.Rejected.class,()->new FrontendPlan(d,"Древний замок.")).code);
    }
    @Test public void missingOrUnapprovedMetadataKeepsAnExplicitHold() {
        FrontendPlan.Data d=data("(?P<зАмок>древний \\[HOMO\\] замок \\[/HOMO\\])");
        assertEquals("UNSUPPORTED_PHRASE_ROUTE",assertThrows(FrontendPlan.Rejected.class,()->new FrontendPlan(d,"Древний замок.")).code);
        d.phraseGroups.put("замок",Collections.singletonMap("зАмок",9));
        assertEquals("UNSUPPORTED_PHRASE_GROUPS",assertThrows(FrontendPlan.Rejected.class,()->new FrontendPlan(d,"Древний замок.")).code);
    }
    @Test public void accentBoundsAreCheckedBeforeAnyNeuralWork() {
        assertEquals("ACCENTOR_TOKEN_BOUND",assertThrows(FrontendPlan.Rejected.class,()->new FrontendPlan(new FrontendPlan.Data(),"а ".repeat(33))).code);
        assertEquals("NGRAM_WORD_QUADRATIC_BOUND",assertThrows(FrontendPlan.Rejected.class,()->new FrontendPlan(new FrontendPlan.Data(),"а".repeat(33))).code);
    }
}
