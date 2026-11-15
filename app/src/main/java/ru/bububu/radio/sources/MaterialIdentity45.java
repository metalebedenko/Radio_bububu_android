package ru.bububu.radio.sources;

import java.net.URI;
import java.util.*;

/** Closed same-material rule. Tracking/host equivalence exists only for accepted RT/Habr mappings. */
public final class MaterialIdentity45 {
    public static final String REVISION="same-material45-v1";
    private static final Set<String> TRACKING=Set.of("utm_source","utm_medium","utm_campaign");
    private MaterialIdentity45(){}
    public static boolean same(SourceProfiles45.Profile profile,String candidate,String target){
        try{return canonical(profile,candidate).equals(canonical(profile,target));}catch(SourceFailure|IllegalArgumentException rejected){return false;}
    }
    public static void require(SourceProfiles45.Profile profile,String candidate,String target)throws SourceFailure{if(!same(profile,candidate,target))throw new SourceFailure("ARTICLE_SUBJECT_MISMATCH");}
    public static String canonical(SourceProfiles45.Profile profile,String value)throws SourceFailure{
        if(value==null||!profile.articleUrl(value))throw new SourceFailure("ARTICLE_SUBJECT_MISMATCH");URI url=URI.create(SafeHttpTransport.normalizeUrl(value));String host=url.getHost(),query=url.getRawQuery();
        if(profile.id.equals("rt-briefs")){if(!host.equals("russian.rt.com")&&!host.equals("russiatoday.ru"))throw new SourceFailure("ARTICLE_SUBJECT_MISMATCH");host="russian.rt.com";}
        if(profile.id.equals("rt-briefs")||profile.id.equals("habr-hardware-reviews-retrospectives")){
            if(query!=null){List<String> kept=new ArrayList<>();Set<String> seen=new HashSet<>();for(String part:query.split("&",-1)){String key=part.split("=",2)[0];if(TRACKING.contains(key)){if(!seen.add(key))throw new SourceFailure("ARTICLE_TRACKING_DUPLICATE");}else kept.add(part);}query=kept.isEmpty()?null:String.join("&",kept);}
        }
        return "https://"+host+(url.getPort()==-1||url.getPort()==443?"":":"+url.getPort())+url.getRawPath()+(query==null?"":"?"+query);
    }
    public static void subject(SourceProfiles45.Profile profile,String candidate,String canonical,Map<String,Object> article)throws SourceFailure{
        for(String key:List.of("url","@id"))if(article.containsKey(key))claim(profile,candidate,canonical,article.get(key));
        if(article.containsKey("mainEntityOfPage")){
            Object value=article.get("mainEntityOfPage");if(value instanceof Map){Map<?,?> object=(Map<?,?>)value;boolean locator=false;for(String key:List.of("url","@id"))if(object.containsKey(key)){locator=true;claim(profile,candidate,canonical,object.get(key));}if(!locator)throw new SourceFailure("ARTICLE_SUBJECT_UNPROVEN");}
            else claim(profile,candidate,canonical,value);
        }
    }
    private static void claim(SourceProfiles45.Profile profile,String candidate,String canonical,Object claim)throws SourceFailure{
        if(!(claim instanceof String)||((String)claim).length()>4096)throw new SourceFailure("ARTICLE_SUBJECT_UNPROVEN");String value=(String)claim;if(value.isEmpty())throw new SourceFailure("ARTICLE_SUBJECT_UNPROVEN");
        try{String resolved=URI.create(canonical).resolve(value).toString();require(profile,candidate,resolved);}catch(IllegalArgumentException invalid){throw new SourceFailure("ARTICLE_SUBJECT_MISMATCH",invalid);}
    }
}
