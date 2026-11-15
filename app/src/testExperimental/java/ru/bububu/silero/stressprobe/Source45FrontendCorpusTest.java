package ru.bububu.silero.stressprobe;

import org.junit.Test;
import ru.bububu.radio.sources.Source45SpeechPlanTest;

/** Existing pure frontend with fixture metadata; no ResourceData/models/neural execution. */
public class Source45FrontendCorpusTest {
    @Test public void dottedDollarSuffixRefusesActualSourcePlanBeforeFrontendAndApprovedMoneyPasses(){
        int[] calls={0};FrontendPlan.Data fixture=new FrontendPlan.Data();
        for(String raw:java.util.List.of("Цена $1.50.","Цена $1,2,3.","Цена $1e3.")){
            IllegalArgumentException rejected=org.junit.Assert.assertThrows(IllegalArgumentException.class,()->ru.bububu.radio.sources.SourceSpeechPlan45.compile(raw).parts(512,spoken->{calls[0]++;new FrontendPlan(fixture,spoken);}));
            org.junit.Assert.assertEquals("UNSUPPORTED_SOURCE_CURRENCY_SUFFIX",rejected.getMessage());org.junit.Assert.assertEquals(0,calls[0]);
        }
        for(String raw:java.util.List.of("Бюджет $2,5 млн.","Бюджет $140 млн.")){
            ru.bububu.radio.sources.SourceSpeechPlan45.Parts parts=ru.bububu.radio.sources.SourceSpeechPlan45.compile(raw).parts(512,spoken->{ru.bububu.radio.sources.SourceSpeechPlan45.boundedSpoken(spoken);new FrontendPlan(fixture,spoken);});
            org.junit.Assert.assertEquals(parts.raw,ru.bububu.radio.core.TextSegments.checkedFragments(raw,parts.raw,512));org.junit.Assert.assertTrue(String.join(" ",parts.spoken).contains("долларов"));
        }
    }
    @Test public void allEighteenActualPublisherHeadersAndContextUseProductionV2Framing()throws Exception{
        FrontendPlan.Data fixture=new FrontendPlan.Data();
        Source45SpeechPlanTest.wholeBodies(spoken->{try{ru.bububu.radio.sources.SourceSpeechPlan45.boundedSpoken(spoken);new FrontendPlan(fixture,spoken);}catch(FrontendPlan.Rejected rejected){throw new IllegalArgumentException(rejected.code);}},"whole-eighteen-actual-framing-frontend.tsv",java.util.Set.of("biomolecula-articles","elementy-science-explainers","poisknews-biographies","forbes-open-articles","knife-articles","naukatv-articles"),18,true);
    }
    @Test public void allEighteenStarterBodiesUseActualFrontendAdmissionAndExactCanonicalCoverage()throws Exception{
        FrontendPlan.Data fixture=new FrontendPlan.Data();
        Source45SpeechPlanTest.wholeBodies(spoken->{try{ru.bububu.radio.sources.SourceSpeechPlan45.boundedSpoken(spoken);new FrontendPlan(fixture,spoken);}catch(FrontendPlan.Rejected rejected){throw new IllegalArgumentException(rejected.code);}},"whole-eighteen-frontend-fixture-coverage.tsv",java.util.Set.of("biomolecula-articles","elementy-science-explainers","poisknews-biographies","forbes-open-articles","knife-articles","naukatv-articles"),18);
    }
    @Test public void allEighteenStarterBodiesUseSourceProjectionAndUnchangedCaps()throws Exception{
        Source45SpeechPlanTest.wholeBodies(ru.bububu.radio.sources.SourceSpeechPlan45::boundedSpoken,"whole-eighteen-source-projection.tsv",java.util.Set.of("biomolecula-articles","elementy-science-explainers","poisknews-biographies","forbes-open-articles","knife-articles","naukatv-articles"),18);
    }
}
