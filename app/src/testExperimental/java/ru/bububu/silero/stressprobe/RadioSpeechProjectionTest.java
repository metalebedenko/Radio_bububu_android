package ru.bububu.silero.stressprobe;

import org.junit.Test;
import ru.bububu.radio.models.SileroSpeechInput;
import ru.bububu.silero.coreprobe.SemanticNormalizer;
import static org.junit.Assert.*;

public final class RadioSpeechProjectionTest {
    @Test public void syntheticFramedInputPassesExistingFrontendAfterOnlyLfProjection() {
        String raw="Источник\n\nЗаголовок\n\nКороткая новость.";
        assertEquals("UNSUPPORTED_CONTROL_CHAR",SemanticNormalizer.normalize(raw).code);
        String local=SileroSpeechInput.project(raw);
        FrontendPlan plan=new FrontendPlan(new FrontendPlan.Data(),local);
        assertEquals(raw.length(),local.length());assertEquals("PASSTHROUGH",plan.normalization.status);
        assertEquals("источник  заголовок  короткая новость.",plan.spoken);
        assertFalse(plan.needsHomo());assertFalse(plan.accentInputs().isEmpty());
    }

    @Test public void crTabAndOtherControlsRemainExplicitlyUnsupported() {
        for(String raw:new String[]{"Текст.\r\n", "Текст.\t\n", "Текст.\u0000\n"}) {
            String local=SileroSpeechInput.project(raw);
            assertEquals("UNSUPPORTED_CONTROL_CHAR",SemanticNormalizer.normalize(local).code);
            FrontendPlan.Rejected rejected=assertThrows(FrontendPlan.Rejected.class,()->new FrontendPlan(new FrontendPlan.Data(),local));
            assertEquals("UNSUPPORTED_CONTROL_CHAR",rejected.code);
        }
    }

    @Test public void projectionDoesNotRelaxRawSpokenNumericOrCharacterBounds() {
        assertRejects("RAW_TEXT_BOUND","а".repeat(512)+"\n");
        assertRejects("UNSUPPORTED_SPOKEN_BOUND","а".repeat(256)+"\n");
        assertEquals("UNSUPPORTED_INTEGER_RANGE",assertThrows(FrontendPlan.Rejected.class,
                ()->new FrontendPlan(new FrontendPlan.Data(),"Встреча состоится в 2027 году.")).code);
        assertEquals("UNSUPPORTED_CHARACTER",assertThrows(FrontendPlan.Rejected.class,
                ()->new FrontendPlan(new FrontendPlan.Data(),"Текст — продолжение.")).code);
    }

    private void assertRejects(String code,String raw) {
        String local=SileroSpeechInput.project(raw);
        assertEquals(raw.length(),local.length());
        assertEquals(code,assertThrows(FrontendPlan.Rejected.class,()->new FrontendPlan(new FrontendPlan.Data(),local)).code);
    }
}
