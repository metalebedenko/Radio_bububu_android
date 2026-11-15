package ru.bububu.silero.coreprobe;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A checked boundary only. No automatic annotation engine or full-frontend READY state. */
public final class CheckedCarrier {
    public static final String STAGE="NEEDS_AUTOMATIC_STRESS_ENGINE";
    private final AnnotatedCarrier encoder;
    public CheckedCarrier(Map<Character,Integer> dictionary) { encoder=new AnnotatedCarrier(dictionary); }
    public List<AnnotatedCarrier.Arg> encodeReviewedAnnotation(SemanticNormalizer.Outcome normalized,String annotated) {
        if(normalized==null||!normalized.supported()) throw new AnnotatedCarrier.Rejected("NORMALIZER_REJECTED");
        if(annotated==null) throw new AnnotatedCarrier.Rejected("ANNOTATION_REQUIRED");
        String spoken=normalized.spoken.toLowerCase(Locale.ROOT);
        String before=spoken.replace("+",""),after=annotated.replace("+","");
        if(before.length()!=after.length()) throw new AnnotatedCarrier.Rejected("ANNOTATION_TEXT_CHANGED");
        for(int i=0;i<before.length();i++) {
            char a=before.charAt(i),b=after.charAt(i);
            if(a!=b&&!(a=='е'&&b=='ё')) throw new AnnotatedCarrier.Rejected("ANNOTATION_TEXT_CHANGED");
        }
        if(!stressSlots(annotated).containsAll(stressSlots(spoken)))
            throw new AnnotatedCarrier.Rejected("MANUAL_STRESS_LOST");
        return encoder.encode(annotated,AnnotatedCarrier.Options.plain(AnnotatedCarrier.Mode.ANDROID_NONE_ONLY));
    }
    public String decode(List<AnnotatedCarrier.Arg> args) { return encoder.decode(args.get(0)); }
    private static Set<Integer> stressSlots(String text) {
        Set<Integer> slots=new HashSet<>();int base=0;
        for(int i=0;i<text.length();i++) if(text.charAt(i)=='+') slots.add(base);else base++;
        return slots;
    }
}
