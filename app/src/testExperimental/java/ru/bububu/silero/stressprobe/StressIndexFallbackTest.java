package ru.bububu.silero.stressprobe;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import ru.bububu.radio.sources.SourceSpeechPlan45;

/** Fixed-shape synthetic accentor outputs through the production frontend/carrier; no NN. */
public final class StressIndexFallbackTest {
    static final int WIDTH=10;
    static FrontendPlan plan(String text){return plan(new FrontendPlan.Data(),text);}
    static FrontendPlan plan(FrontendPlan.Data data,String text){FrontendPlan p=new FrontendPlan(data,text);p.accentInputs();return p;}
    // Staged post-homosolver annotation; this fixture never invokes the model.
    static FrontendPlan annotatedPlan(String base,String annotation){FrontendPlan p=new FrontendPlan(new FrontendPlan.Data(),base);p.homoAnnotated=annotation;p.accentInputs();return p;}
    static float[] logits(FrontendPlan p,int best){float[] out=new float[p.rawTokens.size()*WIDTH];for(int row=0;row<p.rawTokens.size();row++)out[row*WIDTH+best]=12f;return out;}
    static String accept(FrontendPlan p,int best){return p.acceptAccent(logits(p,best),WIDTH,logits(p,0),WIDTH);}
    static void carrierPreserves(FrontendPlan p){assertTrue(new CorePlan(p).length>=3);assertEquals(p.spoken,p.annotated.replace("+",""));}
    static long fallbacks(FrontendPlan p){return p.decisions.stream().filter(x->x.startsWith("stress_index_fallback;")).count();}
    @Test public void confidentClassBeyondTwoVowelsRetainsWholeWordAndCoreCarrier(){
        FrontendPlan p=plan("Мама.");assertEquals("мама.",accept(p,9));carrierPreserves(p);assertEquals(1,fallbacks(p));
        String diagnostic=p.decisions.stream().filter(x->x.startsWith("stress_index_fallback;")).findFirst().get();
        assertTrue(diagnostic.contains("row=0;chars=4;slots=2;class=9;"));assertFalse(diagnostic.contains("мама"));assertTrue(diagnostic.contains("sha256="));
    }
    @Test public void monosyllabicOverridePrecedesUnusableLearnedPosition(){
        FrontendPlan p=plan("Кот!");assertEquals("к+от!",accept(p,9));carrierPreserves(p);assertEquals(1,fallbacks(p));
    }
    @Test public void fallbackForOneWordDoesNotDisableStressForItsNeighbour(){
        FrontendPlan p=plan("Мама читает.");float[] stress=logits(p,9);int row=p.cleanTokens.indexOf("читает");Arrays.fill(stress,row*WIDTH,(row+1)*WIDTH,0f);stress[row*WIDTH+1]=12f;
        assertEquals("мама чит+ает.",p.acceptAccent(stress,WIDTH,logits(p,0),WIDTH));carrierPreserves(p);assertEquals(1,fallbacks(p));
    }
    @Test public void inRangeVowelOrdinalsStillMapToFirstAndLastVowels(){
        for(int index:new int[]{0,2}){FrontendPlan p=plan("Молоко.");assertEquals(index==0?"м+олоко.":"молок+о.",accept(p,index));carrierPreserves(p);assertEquals(0,fallbacks(p));}
    }
    @Test public void lowConfidenceStillLeavesMultisyllabicTextUnaccented(){
        FrontendPlan p=plan("Мама.");float[] weak=new float[p.rawTokens.size()*WIDTH];for(int row=0;row<p.rawTokens.size();row++)weak[row*WIDTH+9]=0.1f;
        assertEquals("мама.",p.acceptAccent(weak,WIDTH,logits(p,0),WIDTH));carrierPreserves(p);assertEquals(0,fallbacks(p));
    }
    @Test public void manualDictionaryYoAndVowellessPathsRetainTheirPriority(){
        FrontendPlan manual=annotatedPlan("Мама.","м+ама.");assertEquals("м+ама.",accept(manual,9));carrierPreserves(manual);assertEquals(0,fallbacks(manual));
        FrontendPlan.Data data=new FrontendPlan.Data();data.exceptions.put("мама",new int[]{1,-1});FrontendPlan dictionary=plan(data,"Мама.");assertEquals("м+ама.",accept(dictionary,9));carrierPreserves(dictionary);assertEquals(0,fallbacks(dictionary));
        FrontendPlan yo=plan("Ёлка.");assertEquals("+ёлка.",accept(yo,9));carrierPreserves(yo);assertEquals(0,fallbacks(yo));
        FrontendPlan consonants=plan("Мгл.");assertEquals("мгл.",accept(consonants,9));carrierPreserves(consonants);assertEquals(0,fallbacks(consonants));
    }
    @Test public void shapeFiniteManualAndDictionaryGuardsAreNotRelaxed(){
        FrontendPlan p=plan("Мама.");assertEquals("ACCENTOR_OUTPUT_SHAPE",assertThrows(FrontendPlan.Rejected.class,()->p.acceptAccent(new float[1],WIDTH,logits(p,0),WIDTH)).code);
        for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}){FrontendPlan q=plan("Мама.");float[] stress=logits(q,0);stress[0]=invalid;assertEquals("LOGIT_NONFINITE",assertThrows(FrontendPlan.Rejected.class,()->q.acceptAccent(stress,WIDTH,logits(q,0),WIDTH)).code);}
        FrontendPlan manual=annotatedPlan("Мам.","мам+.");assertEquals("MANUAL_STRESS_POSITION",assertThrows(FrontendPlan.Rejected.class,()->accept(manual,9)).code);
        FrontendPlan.Data data=new FrontendPlan.Data();data.exceptions.put("мама",new int[]{99,-1});FrontendPlan dictionary=plan(data,"Мама.");assertEquals("EXCEPTION_STRESS_RANGE",assertThrows(FrontendPlan.Rejected.class,()->accept(dictionary,9)).code);

    }
    @Test public void yoZeroOneBasedAndUnusableClassMatchReferenceMapping(){
        for(int index:new int[]{0,1,9}){FrontendPlan p=plan("Мел.");assertEquals(index==1?"м+ёл.":"м+ел.",p.acceptAccent(logits(p,0),WIDTH,logits(p,index),WIDTH));assertTrue(new CorePlan(p).length>=3);assertTrue(FrontendPlan.basePreserved(p.spoken,p.annotated.replace("+","")));assertEquals(index==9?1:0,p.decisions.stream().filter(x->x.startsWith("yo_index_fallback;")).count());}
    }
    @Test public void exactlyHalfConfidenceDoesNotRequestLearnedStressOrYo(){
        FrontendPlan stress=plan("Мама.");float[] half=new float[stress.rawTokens.size()*2];assertEquals("мама.",stress.acceptAccent(half,2,logits(stress,0),WIDTH));carrierPreserves(stress);
        FrontendPlan yo=plan("Мел.");float[] same=new float[yo.rawTokens.size()*3];for(int row=0;row<yo.rawTokens.size();row++)same[row*3]=-100f;
        assertEquals("м+ел.",yo.acceptAccent(logits(yo,0),WIDTH,same,3));carrierPreserves(yo);
    }
    @Test public void sourceSentenceKeepsCanonicalCutsTextAndPunctuationThroughFallback(){
        String raw="Мама читает. Кот спит!";SourceSpeechPlan45.Plan source=SourceSpeechPlan45.compile(raw);FrontendPlan.Data data=new FrontendPlan.Data();
        SourceSpeechPlan45.Parts parts=source.parts(512,spoken->new FrontendPlan(data,spoken));StringBuilder spoken=new StringBuilder();long count=0;
        for(String part:parts.spoken){FrontendPlan p=plan(data,part);accept(p,9);carrierPreserves(p);spoken.append(p.annotated.replace("+","")).append(' ');count+=fallbacks(p);}
        assertEquals(String.join(" ",parts.spoken).toLowerCase(Locale.ROOT),spoken.toString().trim());assertEquals(raw,source.canonical);assertTrue(count>=4);assertTrue(spoken.toString().contains("."));assertTrue(spoken.toString().contains("!"));
    }
    /** Nearest persisted public units supplied by QA; not captured native requests/logits. */
    @Test public void twoPublicNearestCandidatesPreserveTextAndPunctuationWithSyntheticInvalidRows(){
        {
            String raw="Обладатель Почетного диплома Премии Г. Х. Андерсена (IBBY-2014), лауреат многочисленных литературных премий.";assertEquals("26e20e076518ffacdea255beebb40a3a26e8fb5ff704828f2947b15cd6f85ffa",ru.bububu.radio.sources.SourceLimits.sha256(raw));
            SourceSpeechPlan45.Plan source=SourceSpeechPlan45.compile(raw);FrontendPlan.Data data=new FrontendPlan.Data();SourceSpeechPlan45.Parts parts=source.parts(512,spoken->new FrontendPlan(data,spoken));
            assertEquals(raw,source.canonical);assertEquals(parts.raw,ru.bububu.radio.core.TextSegments.checkedFragments(raw,parts.raw,512));long fallback=0;
            for(String projected:parts.spoken){FrontendPlan plan=plan(data,projected);accept(plan,9);carrierPreserves(plan);fallback+=fallbacks(plan);}
            assertTrue("Injected unusable row exercises fallback on the public candidate",fallback>0);
        }
        {
            String raw="Рисунок Франца Антони (Franz Anthony) из обсуждаемой статьи\n\nПочти четверть века назад, в 2000 году, на территории знаменитого местонахождения Мазон-Крик в США нашли хорошо сохранившийся отпечаток, похожий на небольшого осьминога.";assertEquals("e6c90732165d4a124e426e6796d8ab96cc82b8063871d709d8b9e3769c15cdf2",ru.bububu.radio.sources.SourceLimits.sha256(raw));
            SourceSpeechPlan45.Plan source=SourceSpeechPlan45.compile(raw);FrontendPlan.Data data=new FrontendPlan.Data();SourceSpeechPlan45.Parts parts=source.parts(512,spoken->new FrontendPlan(data,spoken));
            assertEquals(raw,source.canonical);assertEquals(parts.raw,ru.bububu.radio.core.TextSegments.checkedFragments(raw,parts.raw,512));long fallback=0;
            for(String projected:parts.spoken){FrontendPlan plan=plan(data,projected);accept(plan,9);carrierPreserves(plan);fallback+=fallbacks(plan);}
            assertTrue("Injected unusable row exercises fallback on the public candidate",fallback>0);
        }
    }

}
