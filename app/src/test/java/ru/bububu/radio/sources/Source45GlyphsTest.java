package ru.bububu.radio.sources;

import java.util.*;
import org.junit.Test;
import ru.bububu.radio.core.TextSegments;
import ru.bububu.radio.models.SileroSpeechInput;
import static org.junit.Assert.*;

public class Source45GlyphsTest {
    @Test public void everyObservedGlyphIsExplicitAndHasCanonicalCharacterCoverage(){
        for(Map.Entry<Character,String> e:SourceGlyphs45.observedTable().entrySet()){
            String raw="До "+e.getKey()+" после.";SourceSpeechPlan45.Plan p=SourceSpeechPlan45.compile(raw);
            assertTrue(e.getKey().toString(),p.atoms.stream().anyMatch(a->a.start==3&&a.end==4&&a.spoken.equals(e.getValue())));
            SourceSpeechPlan45.Parts parts=p.parts(512,SourceSpeechPlan45::boundedSpoken);assertEquals(parts.raw,TextSegments.checkedFragments(raw,parts.raw,512));assertTrue(String.join(" ",parts.spoken).contains(e.getValue()));
        }
    }
    @Test public void actualForeignNamesAndTypographyUseClosedLiteralSubatoms(){
        for(String raw:List.of("‘paradise’","Clément","d’Errico,","We've","ΔMtln","фильма…","℗","Rußlands.","l’Étranger.","migration-naïve","бо́льшую","***","Монтемайор·Salmon")){
            SourceSpeechPlan45.Plan p=SourceSpeechPlan45.compile(raw);SourceSpeechPlan45.Parts parts=p.parts(512,SourceSpeechPlan45::boundedSpoken);assertEquals(parts.raw,TextSegments.checkedFragments(raw,parts.raw,512));
            int covered=p.atoms.stream().mapToInt(a->a.end-a.start).sum();assertEquals(raw.length(),covered);for(String projected:parts.spoken)assertTrue(projected,projected.matches("[А-Яа-яёЁ .,!?:;+]+"));
        }
    }
    @Test public void onlyObservedUpperIndexFormsAreReadWithoutEvaluationOrEndpointExpansion(){
        String raw="10⁻¹⁹, 10⁻¹¹–10⁻¹²";SourceSpeechPlan45.Plan p=SourceSpeechPlan45.compile(raw);
        List<SourceSpeechPlan45.Atom> powers=new ArrayList<>();for(SourceSpeechPlan45.Atom a:p.atoms)if(a.rule.endsWith(":upper-index"))powers.add(a);
        assertEquals(3,powers.size());assertEquals(List.of("десять верхний индекс минус девятнадцать","десять верхний индекс минус одиннадцать","десять верхний индекс минус двенадцать"),powers.stream().map(a->a.spoken).collect(java.util.stream.Collectors.toList()));
        for(SourceSpeechPlan45.Atom a:powers)for(int cut=a.start+1;cut<a.end;cut++){final int at=cut;assertThrows(IllegalArgumentException.class,()->p.project(a.start,at));}
        SourceSpeechPlan45.Parts parts=p.parts(12,SourceSpeechPlan45::boundedSpoken);assertEquals(parts.raw,TextSegments.checkedFragments(raw,parts.raw,12));assertTrue(String.join(" ",parts.spoken).contains("тире"));
        for(String unknown:List.of("10⁻³","x^2","\\frac{1}{2}","$x&"))assertThrows(IllegalArgumentException.class,()->SourceSpeechPlan45.compile(unknown));
    }
    @Test public void spacedAndAdjacentPowerRangesKeepOnlyTheirOwnDashes() {
        String raw = "10⁻¹⁹ –10⁻¹¹— 10⁻¹² — ответ";
        String projected = SourceSpeechPlan45.compile(raw).project(0, raw.length());
        assertEquals("десять верхний индекс минус девятнадцать тире десять верхний индекс минус одиннадцать тире десять верхний индекс минус двенадцать , ответ", projected);
        assertThrows(IllegalArgumentException.class, () -> SourceSpeechPlan45.compile("10⁻¹¹–10⁻³"));
    }
    @Test public void longLiteralHasContextualCutsWithoutRaisingAnyCap(){
        String raw="A".repeat(4000)+"é";SourceSpeechPlan45.Plan p=SourceSpeechPlan45.compile(raw);SourceSpeechPlan45.Parts parts=p.parts(512,SourceSpeechPlan45::boundedSpoken);
        assertTrue(parts.raw.size()>100);assertEquals(parts.raw,TextSegments.checkedFragments(raw,parts.raw,512));assertTrue(parts.candidateChecks<raw.length()*70L);
        for(String spoken:parts.spoken){assertTrue(spoken.length()<=256);SourceSpeechPlan45.boundedSpoken(spoken);}
    }
    @Test public void cyrillicYoAndLegacyNotesProjectionAreNotNormalizedAway(){
        assertEquals("ёлка",SourceSpeechPlan45.compile("ёлка").project(0,4));assertEquals("Ёлка.",SileroSpeechInput.project("Ёлка."));
        assertThrows(IllegalArgumentException.class,()->SileroSpeechInput.project("В 1859 году"));
        assertEquals("бо́льшую",SileroSpeechInput.project("бо́льшую"));
    }
}
