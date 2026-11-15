package ru.bububu.silero.stressprobe;

import java.util.*;
import ru.bububu.radio.core.TextSegments;
import ru.bububu.radio.models.SileroSpeechInput;

/** Pure existing-profile checks choose canonical slices before any model is loaded. */
public final class RadioFragments {
    private RadioFragments() { }
    public static List<String> fromResources(byte[] resources,String text,int maxChars)throws Exception {
        return plan(ResourceData.parse(resources),text,maxChars);
    }
    public static List<String> fromResources(byte[] resources,SileroSpeechInput.SentencePlan sentence,int maxChars)throws Exception {
        return plan(ResourceData.parse(resources),sentence,maxChars);
    }
    public static List<String> fromSource45Resources(byte[] resources,ru.bububu.radio.sources.SourceSpeechPlan45.Plan source,int maxChars)throws Exception{
        return source45(ResourceData.parse(resources),source,maxChars);
    }
    static List<String> source45(FrontendPlan.Data data,ru.bububu.radio.sources.SourceSpeechPlan45.Plan source,int maxChars){
        return source.parts(Math.min(512,maxChars),spoken->new FrontendPlan(data,spoken)).raw;
    }
    static List<String> plan(FrontendPlan.Data data,String text,int maxChars) {
        FrontendPlan.need(text!=null&&text.length()<=20000&&maxChars>=2,"RADIO_INPUT_BOUND");
        return plan(data,SileroSpeechInput.forSentence(text),maxChars);
    }
    static List<String> plan(FrontendPlan.Data data,SileroSpeechInput.SentencePlan sentence,int maxChars) {
        String text=sentence.text();
        FrontendPlan.need(text.length()<=20000&&maxChars>=2,"RADIO_INPUT_BOUND");
        List<Integer> ends=sentence.boundaries();List<String> result=new ArrayList<>();int start=0,contentEnd=text.length();while(contentEnd>0&&Character.isWhitespace(text.charAt(contentEnd-1)))contentEnd--;
        while(start<text.length()) {
            while(start<text.length()&&Character.isWhitespace(text.charAt(start)))start++;
            if(start==text.length())break;
            int best=-1,clause=-1;String reason="UNSUPPORTED_RADIO_ATOM";
            for(int end:ends) {
                if(end<=start)continue;if(end-start>maxChars)break;
                String raw=text.substring(start,end).trim();
                try {
                    String spoken=sentence.project(start,start+raw.length());
                    if(spoken.length()>256)break;
                    new FrontendPlan(data,spoken);best=end;if(TextSegments.clauseBoundary(text,start,end))clause=end;
                } catch(IllegalArgumentException rejected) {
                    if(rejected.getMessage()!=null)reason=rejected.getMessage();
                }
            }
            FrontendPlan.need(best>start,reason);if(best<contentEnd&&clause>start)best=clause;result.add(text.substring(start,best).trim());start=best;
        }
        return TextSegments.checkedFragments(text,result,maxChars);
    }
}
