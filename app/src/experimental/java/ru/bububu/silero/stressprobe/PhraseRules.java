package ru.bububu.silero.stressprobe;

import java.util.*;
import java.util.regex.*;

/** Only a unique named stress variant already present in the approved resource may resolve a phrase. */
final class PhraseRules {
    static String resolve(FrontendPlan.Data data,String word,String context) {
        String source=data.phrases.get(word);if(source==null)return null;
        Map<String,Integer> groups=data.phraseGroups.get(word);
        if(groups==null||groups.isEmpty()) {
            FrontendPlan.need(!compiled(data,word,source.replaceAll("\\(\\?P<[^>]+>","(")).matcher(context).find(),"UNSUPPORTED_PHRASE_ROUTE");
            return null;
        }
        Set<String> names=new HashSet<>();Matcher named=Pattern.compile("\\(\\?P<([^>]+)>").matcher(source);
        while(named.find())names.add(named.group(1));
        FrontendPlan.need(names.equals(groups.keySet())&&groups.size()<=4,"UNSUPPORTED_PHRASE_GROUPS");
        Set<String> resolved=new HashSet<>();
        for(Map.Entry<String,Integer> selected:groups.entrySet()) {
            String pattern=source;
            // Disable every other named alternative, so overlapping variants cannot be hidden by alternation order.
            for(String name:names)pattern=pattern.replace("(?P<"+name+">",name.equals(selected.getKey())?"(":"((?!)");
            Matcher match=compiled(data,word+":"+selected.getKey(),pattern).matcher(context);
            FrontendPlan.need(selected.getValue()>0&&selected.getValue()<=match.groupCount(),"UNSUPPORTED_PHRASE_GROUPS");
            if(!match.find()||match.group(selected.getValue())==null)continue;
            StringBuilder variant=new StringBuilder();int stress=0;
            for(char c:selected.getKey().toCharArray()) {
                if(Character.isUpperCase(c)){FrontendPlan.need(FrontendPlan.VOWELS.indexOf(Character.toLowerCase(c))>=0,"UNSUPPORTED_PHRASE_VARIANT");variant.append('+');stress++;}
                variant.append(Character.toLowerCase(c));
            }
            String value=variant.toString();List<String> approved=data.variants.get(word);
            FrontendPlan.need(stress>0&&approved!=null&&approved.contains(value)&&FrontendPlan.basePreserved(word,value.replace("+","")),"UNSUPPORTED_PHRASE_VARIANT");
            resolved.add(value);
        }
        FrontendPlan.need(resolved.size()<=1,"UNSUPPORTED_PHRASE_AMBIGUOUS");
        return resolved.isEmpty()?null:resolved.iterator().next();
    }

    private static Pattern compiled(FrontendPlan.Data data,String key,String source) {
        try{return data.compiledPhrases.computeIfAbsent(key,k->Pattern.compile(source,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE));}
        catch(PatternSyntaxException error){throw new FrontendPlan.Rejected("UNSUPPORTED_PHRASE_REGEX");}
    }
}
