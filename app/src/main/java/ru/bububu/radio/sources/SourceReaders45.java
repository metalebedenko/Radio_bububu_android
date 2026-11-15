package ru.bububu.radio.sources;

import org.jsoup.nodes.*;
import org.jsoup.select.Elements;
import org.jsoup.parser.Parser;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import static ru.bububu.radio.sources.SourcePublication45.Role.*;
import static ru.bububu.radio.sources.SourcePublication45.Precision.*;

/** Four closed body routes plus bounded discovery. No generic extraction fallback. */
public final class SourceReaders45 {
    private SourceReaders45(){}
    public static FeedDocument discover(SourceProfiles45.Profile p,SourceTransport.Response response,RequestScope scope)throws SourceFailure{
        if(response.status!=200||!p.discoveryIdentity(SafeHttpTransport.normalizeUrl(response.finalUrl)))throw new SourceFailure("PROFILE_DISCOVERY_RESPONSE");
        if(p.route.equals("J"))return wp(p,response,scope);
        if(p.route.equals("R"))return rss(p,response,scope);
        Document doc=html(p,response,scope);Map<String,FeedItem> items=new LinkedHashMap<>();
        for(Element link:doc.select("a[href]")){
            scope.check();String url=link.absUrl("href");try{url=SafeHttpTransport.normalizeUrl(url);}catch(SourceFailure rejected){continue;}
            if(!p.articleUrl(url))continue;String title=SourceSemantic45.plain(link,scope);if(title.isEmpty()||title.length()>2000)continue;
            items.putIfAbsent(url,new FeedItem(null,url,title,null,null,null,null,null));if(items.size()>=SourceLimits.MAX_PARSED_ITEMS)break;
        }
        return new FeedDocument(p.name,response.finalUrl,p.type(),new ArrayList<>(items.values()),items.size()>=SourceLimits.MAX_PARSED_ITEMS);
    }
    public static ExtractionResult extract(SourceProfiles45.Profile p,SourceTransport.Response response,FeedItem item,RequestScope scope)throws SourceFailure{
        if(item==null||!p.articleUrl(item.url))throw new SourceFailure("PROFILE_ARTICLE_URL");
        if(p.family.equals("full_rss")){if(item.inline==null)throw new SourceFailure("RSS_CONTENT_MISSING");return item.inline;}
        MaterialIdentity45.require(p,item.url,response.finalUrl);Document doc=html(p,response,scope);String documentCanonical=canonical(p,doc,response.finalUrl);MaterialIdentity45.require(p,item.url,documentCanonical);
        if(p.family.equals("wp_json")){if(item.inline==null||item.inline.publication==null)throw new SourceFailure("WP_API_BODY_REQUIRED");MaterialIdentity45.require(p,item.url,item.inline.publication.subjectUrl);if(!Objects.equals(item.providerId,item.inline.publication.subjectId))throw new SourceFailure("WP_SUBJECT_ID_MISMATCH");return item.inline.withPublication(publication(p,doc,item,response,item.inline.publication));}
        if(p.family.equals("jsonld"))return jsonLdBody(p,doc,response,item,scope);
        if(!p.family.equals("addressed_html"))throw new SourceFailure("PROFILE_READER_UNSUPPORTED");
        if(p.id.equals("habr-hardware-reviews-retrospectives")){
            Elements labels=doc.select("div.publication-label.variant-review,div.publication-label.variant-retrospective");if(labels.size()!=1)throw new SourceFailure("HABR_GENRE_UNSUPPORTED");
        }
        Elements roots=doc.select(p.selector);if(roots.size()!=1)throw new SourceFailure("BODY_ROOT_COUNT");Element root=roots.get(0).clone();
        for(String exclude:p.excludes)root.select(exclude).remove();
        if(p.id.equals("forbes-open-articles"))forbesCredits(root,scope);
        if(p.id.equals("kommersant-briefs")){Elements blocks=root.select("div.doc-content__text-block");if(!blocks.isEmpty()){Element last=blocks.last();String value=SourceSemantic45.plain(last,scope).toLowerCase(Locale.ROOT);if(value.startsWith("подробнее")&&value.contains("материале")&&!last.select("a").isEmpty())throw new SourceFailure("PRIMARY_STORY_TEASER_NOT_FULL");}}
        String body=SourceSemantic45.plain(root,scope);if(!p.lead.isEmpty())body=lead(doc,p.lead,body,scope,false);
        if(body.isEmpty())throw new SourceFailure("BODY_EMPTY");String title=headline(doc,item.title,scope);
        return result(body,title,"source45-html",publication(p,doc,item,response,null));
    }
    private static ExtractionResult result(String body,String title,String method,SourcePublication45 publication)throws SourceFailure{
        if(body.isEmpty())throw new SourceFailure("BODY_EMPTY");if(body.length()>SourceLimits.MAX_SOURCE_SPOKEN_UTF16||title.length()>SourceLimits.MAX_PROVIDER_TITLE)throw new SourceFailure("TEXT_OR_TITLE_LIMIT");
        return new ExtractionResult(body,title,SemanticTextNormalizer.displayTitle(title),"full","",method,SourceProfiles45.PARSER).withPublication(publication);
    }
    private static Document html(SourceProfiles45.Profile p,SourceTransport.Response r,RequestScope scope)throws SourceFailure{
        if(r.status!=200||!p.allows(r.finalUrl)||!("text/html".equals(r.mime)||"application/xhtml+xml".equals(r.mime)))throw new SourceFailure("PROFILE_HTML_RESPONSE");return DomBudgetGuard.parse(r,scope).document;
    }
    private static String canonical(SourceProfiles45.Profile p,Document d,String responseUrl)throws SourceFailure{
        Elements links=d.select("link[rel=canonical][href]");if(links.size()!=1)throw new SourceFailure("CANONICAL_COUNT");String canonical=links.get(0).absUrl("href");
        if(!p.articleUrl(canonical))throw new SourceFailure("CANONICAL_OUTSIDE_PROFILE");try{if(!Objects.equals(URI.create(canonical).getPath(),URI.create(responseUrl).getPath()))throw new SourceFailure("CANONICAL_PATH_MISMATCH");}catch(IllegalArgumentException invalid){throw new SourceFailure("CANONICAL_URL");}return canonical;
    }
    private static String headline(Document d,String fallback,RequestScope scope)throws SourceFailure{Elements h=d.select("h1");if(h.size()==1){String title=SourceSemantic45.plain(h.get(0),scope);if(!title.isEmpty())return title;}if(fallback==null||fallback.isEmpty())throw new SourceFailure("TITLE_MISSING");return fallback;}
    private static String lead(Document doc,String selector,String body,RequestScope scope,boolean verifyMeta)throws SourceFailure{
        Elements leads=doc.select(selector);if(leads.size()!=1)throw new SourceFailure("LEAD_COUNT");String text=SourceSemantic45.plain(leads.get(0),scope);if(text.isEmpty())throw new SourceFailure("LEAD_EMPTY");
        if(verifyMeta){Elements meta=doc.select("meta[property=og:description]");if(meta.size()!=1||!SourceSemantic45.fold(meta.get(0).attr("content")).equals(SourceSemantic45.fold(text)))throw new SourceFailure("VISIBLE_LEAD_METADATA_MISMATCH");}
        return SourceSemantic45.fold(body).contains(SourceSemantic45.fold(text))?body:text+"\n\n"+body;
    }
    private static void forbesCredits(Element root,RequestScope scope)throws SourceFailure{
        Elements figures=root.select("figure[data-image-key]");for(int i=0;i<figures.size();i++){
            Elements captions=figures.get(i).select("figcaption.Z-01U");if(captions.size()!=1)continue;Element caption=captions.get(0);String text=SourceSemantic45.plain(caption,scope);if(text.isEmpty()||text.length()>120)continue;
            boolean narrative=false;for(Element n:caption.select("div.mDYeF"))if(!SourceSemantic45.plain(n,scope).isEmpty())narrative=true;if(narrative)continue;
            boolean duplicate=false;for(int neighbor:new int[]{i-1,i+1})if(neighbor>=0&&neighbor<figures.size())for(Element n:figures.get(neighbor).select("figcaption.Z-01U")){String other=SourceSemantic45.plain(n,scope);if(other.equals(text)||other.startsWith(text+"\n"))duplicate=true;}
            if(duplicate)caption.empty();
        }
    }
    private static boolean articleType(Map<String,Object> object){Object type=object.get("@type");if(type instanceof String)return type.equals("Article")||type.equals("NewsArticle");if(type instanceof List)for(Object value:(List<?>)type)if(value.equals("Article")||value.equals("NewsArticle"))return true;return false;}
    private static void walk(Object value,List<Map<String,Object>> result){if(value instanceof Map){Map<String,Object> object=cast(value);if(articleType(object))result.add(object);for(Object child:object.values())if(child instanceof Map||child instanceof List)walk(child,result);}else if(value instanceof List)for(Object child:(List<?>)value)walk(child,result);}
    @SuppressWarnings("unchecked")private static Map<String,Object> cast(Object object){return (Map<String,Object>)object;}
    private static List<Map<String,Object>> articles(Document d)throws SourceFailure{
        List<Map<String,Object>> result=new ArrayList<>();for(Element script:d.select("script[type=application/ld+json]")){
            String data=script.data();try{walk(BoundedJson45.parse(data),result);}catch(SourceFailure invalid){if(data.contains("articleBody")||data.contains("datePublished")&&data.contains("NewsArticle"))throw new SourceFailure("REQUIRED_JSONLD_INVALID");}
        }return result;
    }
    private static ExtractionResult jsonLdBody(SourceProfiles45.Profile p,Document doc,SourceTransport.Response response,FeedItem item,RequestScope scope)throws SourceFailure{
        List<Map<String,Object>> candidates=new ArrayList<>();for(Map<String,Object> a:articles(doc))if(a.get("articleBody") instanceof String&&!SourceSemantic45.trim((String)a.get("articleBody")).isEmpty())candidates.add(a);
        if(candidates.size()!=1)throw new SourceFailure("ARTICLEBODY_COUNT");Map<String,Object> a=candidates.get(0);MaterialIdentity45.subject(p,item.url,canonical(p,doc,response.finalUrl),a);String body=SourceSemantic45.jsonBody((String)a.get("articleBody"));if(!p.lead.isEmpty())body=lead(doc,p.lead,body,scope,p.id.equals("techinsider-briefs"));String title=a.get("headline") instanceof String?Parser.unescapeEntities((String)a.get("headline"),false):headline(doc,item.title,scope);
        return result(body,title,"source45-jsonld",publication(p,doc,item,response,null));
    }
    private static FeedDocument rss(SourceProfiles45.Profile p,SourceTransport.Response response,RequestScope scope)throws SourceFailure{
        if(!SafeHttpTransport.isXmlMime(response.mime)&&!p.id.equals("profile-news-briefs"))throw new SourceFailure("PROFILE_RSS_MIME");ClosedRss45.Node root=ClosedRss45.parse(response,scope);if(!root.ns.isEmpty()||!root.name.equals("rss")||!"2.0".equals(root.attrs.get("version")))throw new SourceFailure("RSS_ROOT");
        List<ClosedRss45.Node> channels=new ArrayList<>();for(ClosedRss45.Node n:root.children)if(n.ns.isEmpty()&&n.name.equals("channel"))channels.add(n);if(channels.size()!=1)throw new SourceFailure("RSS_CHANNEL");List<FeedItem> items=new ArrayList<>();int seen=0;boolean limit=false;
        for(ClosedRss45.Node n:channels.get(0).children){if(!n.ns.isEmpty()||!n.name.equals("item"))continue;scope.check();if(++seen>SourceLimits.MAX_PARSED_ITEMS){limit=true;break;}String url=n.value("link");if(url==null||!p.articleUrl(url))continue;
            if(p.id.equals("knife-articles")){boolean genre=false;for(ClosedRss45.Node category:n.children)if(category.ns.isEmpty()&&category.name.equals("category")&&SourceSemantic45.trim(category.text.toString()).equals("Статьи"))genre=true;if(!genre)continue;}
            String title=n.value("title");if(title==null)continue;title=SourceSemantic45.fragment(title,scope);String published=n.value("pubDate"),updated=null;SourcePublication45.Fact primary=SourcePublication45.parse(ORIGINAL_PUBLISHED,published,"rss",url,"item/pubDate",false);ExtractionResult inline=null;
            if(p.family.equals("full_rss")){ClosedRss45.Node encoded=n.child(ClosedRss45.CONTENT,"encoded");if(encoded==null||encoded.text.toString().isEmpty())continue;Document body=DomBudgetGuard.parse(encoded.text.toString(),url,scope).document;
                if(p.id.equals("ixbt-news-briefs"))for(Element caption:body.select("figcaption"))for(TextNode text:caption.textNodes()){Matcher m=Pattern.compile("(.+?)\\s*//\\s*(https?://\\S+)\\s*").matcher(text.getWholeText());if(m.matches())text.text(SourceSemantic45.trim(m.group(1)));}
                SourcePublication45 publication=new SourcePublication45(primary,List.of(),p.configSha,"date45-"+p.dateRule,primary.precision==UNKNOWN&&!Objects.toString(published,"").isEmpty()?"INVALID":"",SourceLimits.hash(response.bytes),"",SourceSemantic45.linkDigest(body,scope),MaterialIdentity45.canonical(p,url),Objects.toString(n.value("guid"),""));inline=result(SourceSemantic45.plain(body,scope),title,"source45-full-rss",publication);
            }
            items.add(new FeedItem(n.value("guid"),url,title,published,updated,primary.epochMillis,null,inline));
        }
        return new FeedDocument(p.name,response.finalUrl,p.type(),items,limit);
    }
    private static FeedDocument wp(SourceProfiles45.Profile p,SourceTransport.Response response,RequestScope scope)throws SourceFailure{
        if(!response.mime.equals("application/json"))throw new SourceFailure("PROFILE_JSON_MIME");if(response.charset!=null&&!response.charset.equalsIgnoreCase("UTF-8")&&!response.charset.equalsIgnoreCase("utf8"))throw new SourceFailure("JSON_CHARSET");List<Object> posts=BoundedJson45.array(BoundedJson45.parse(response.bytes));if(posts.size()>3)throw new SourceFailure("WP_UNPROVEN_PAGE_SIZE");List<FeedItem> items=new ArrayList<>();int category=p.id.equals("poisknews-biographies")?1814:p.id.equals("if24-interviews")?5:900;
        for(Object value:posts){scope.check();Map<String,Object> post=BoundedJson45.object(value);Object id=post.get("id");if(!(id instanceof BigDecimal))throw new SourceFailure("WP_ID");String provider;try{provider=((BigDecimal)id).toBigIntegerExact().toString();}catch(ArithmeticException e){throw new SourceFailure("WP_ID");}if(provider.startsWith("-")||provider.equals("0"))throw new SourceFailure("WP_ID");String url=BoundedJson45.text(post.get("link"));if(!p.articleUrl(url))throw new SourceFailure("WP_LINK_OUTSIDE_PROFILE");boolean allowed=false;for(Object c:BoundedJson45.array(post.get("categories")))if(c instanceof BigDecimal&&((BigDecimal)c).compareTo(BigDecimal.valueOf(category))==0)allowed=true;if(!allowed)continue;
            Map<String,Object> content=BoundedJson45.object(post.get("content"));if(!Boolean.FALSE.equals(content.get("protected")))continue;String body=SourceSemantic45.fragment(BoundedJson45.text(content.get("rendered")),scope);String title=SourceSemantic45.fragment(BoundedJson45.text(BoundedJson45.object(post.get("title")).get("rendered")),scope);String raw=BoundedJson45.optional(post,"date_gmt");SourcePublication45.Fact primary=SourcePublication45.parse(ORIGINAL_PUBLISHED,raw,"wp-api",p.endpoint,"post[id="+provider+"].date_gmt",true);List<SourcePublication45.Fact> facts=new ArrayList<>();
            for(String field:List.of("date","modified","modified_gmt")){String x=BoundedJson45.optional(post,field);if(x!=null)facts.add(SourcePublication45.parse(field.equals("date")?OTHER_CLAIM:MODIFIED,x,"wp-api",p.endpoint,"post[id="+provider+"]."+field,field.endsWith("_gmt")));}
            SourcePublication45 pub=new SourcePublication45(primary,facts,p.configSha,"date45-WP_GMT",primary.precision==UNKNOWN?"INVALID":"",SourceLimits.hash(response.bytes),"",SourceSemantic45.linkDigest(DomBudgetGuard.parse(BoundedJson45.text(content.get("rendered")),url,scope).document,scope),MaterialIdentity45.canonical(p,url),provider);ExtractionResult inline=result(body,title,"source45-wp-json",pub);items.add(new FeedItem(provider,url,title,raw,BoundedJson45.optional(post,"modified_gmt"),primary.epochMillis,null,inline));
        }return new FeedDocument(p.name,response.finalUrl,p.type(),items,false);
    }
    private static String uniqueRaw(Document d,String selector,String attr){Elements e=d.select(selector);return e.size()==1?e.get(0).attr(attr):null;}
    private static String uniqueJsonDate(Document d,SourceProfiles45.Profile p,String candidate,String canonical,String field)throws SourceFailure{List<Map<String,Object>> values=new ArrayList<>();for(Map<String,Object> a:articles(d))if(a.get(field) instanceof String)values.add(a);if(values.size()!=1)return null;MaterialIdentity45.subject(p,candidate,canonical,values.get(0));return (String)values.get(0).get(field);}
    private static String uniqueNewsArticleDate(Document d,SourceProfiles45.Profile p,String candidate,String canonical)throws SourceFailure{List<Map<String,Object>> values=new ArrayList<>();for(Map<String,Object> a:articles(d)){Object type=a.get("@type");boolean news="NewsArticle".equals(type)||type instanceof List&&((List<?>)type).contains("NewsArticle");if(news&&a.get("datePublished") instanceof String)values.add(a);}if(values.size()!=1)return null;MaterialIdentity45.subject(p,candidate,canonical,values.get(0));return (String)values.get(0).get("datePublished");}
    private static SourcePublication45 publication(SourceProfiles45.Profile p,Document d,FeedItem item,SourceTransport.Response response,SourcePublication45 api)throws SourceFailure{
        String url=canonical(p,d,response.finalUrl);List<SourcePublication45.Fact> facts=new ArrayList<>();if(api!=null)facts.addAll(api.facts);SourcePublication45.Fact primary;String conflict="";
        String jsonPublished=p.dateRule.equals("FORBES_TIME")?uniqueNewsArticleDate(d,p,item.url,url):uniqueJsonDate(d,p,item.url,url,"datePublished"),jsonModified=uniqueJsonDate(d,p,item.url,url,"dateModified");
        switch(p.dateRule){
            case "RSS":primary=SourcePublication45.parse(ORIGINAL_PUBLISHED,item.publishedRaw,"rss",item.url,"item/pubDate",false);break;
            case "WP_GMT":if(api==null)throw new SourceFailure("WP_API_DATE_REQUIRED");primary=api.primary;break;
            case "FORBES_TIME":{
                String raw=uniqueRaw(d,"article time[itemprop=datePublished]","datetime");primary=SourcePublication45.parse(ORIGINAL_PUBLISHED,raw,"microdata",url,"article time[itemprop=datePublished]@datetime",false);
                SourcePublication45.Fact corroboration=SourcePublication45.parse(OTHER_CLAIM,uniqueNewsArticleDate(d,p,item.url,url),"jsonld",url,"NewsArticle.datePublished",false);if(raw==null||primary.epochMillis==null||corroboration.epochMillis==null||!primary.epochMillis.equals(corroboration.epochMillis)){primary=SourcePublication45.unknown(Objects.toString(raw,""),"microdata",url,"article time[itemprop=datePublished]@datetime");conflict="FORBES_PRIMARY_CONFLICT";}break;
            }
            case "BIO_DAY":{
                Elements e=d.select("p.article_date");String raw=e.size()==1?SourceSemantic45.plain(e.get(0),null):"";primary=visibleDay(raw,url);if(primary.precision==UNKNOWN&&!raw.isEmpty())conflict="INVALID";break;
            }
            case "META_PUBLISHED":primary=SourcePublication45.parse(ORIGINAL_PUBLISHED,uniqueRaw(d,"meta[name=article:published_time],meta[property=article:published_time]","content"),"meta",url,"article:published_time",false);break;
            case "HABR_TIME":primary=SourcePublication45.parse(ORIGINAL_PUBLISHED,uniqueRaw(d,"time[datetime]","datetime"),"html",url,"time@datetime",false);break;
            case "VKR_ORIGINAL":{
                Elements roots=d.select(p.selector);String body=roots.size()==1?SourceSemantic45.plain(roots.get(0),null):"";Matcher magazine=Pattern.compile("(?im)^Материал опубликован в журнале «Вокруг света» №\\s*[0-9]{1,3},\\s*(январь|февраль|март|апрель|май|июнь|июль|август|сентябрь|октябрь|ноябрь|декабрь)\\s+([0-9]{4})[^\\n]{0,500}$").matcher(body);List<SourcePublication45.Fact> periods=new ArrayList<>();
                while(magazine.find()){int month=List.of("январь","февраль","март","апрель","май","июнь","июль","август","сентябрь","октябрь","ноябрь","декабрь").indexOf(magazine.group(1).toLowerCase(Locale.ROOT))+1;String period=String.format(Locale.ROOT,"%04d-%02d",Integer.parseInt(magazine.group(2)),month);periods.add(new SourcePublication45.Fact(ORIGINAL_PUBLISHED,MONTH,magazine.group(),period,null,null,"publisher-magazine",url,p.selector+"/magazine-footer","date45-VKR_ORIGINAL"));}
                SourcePublication45.Fact rss=SourcePublication45.parse(REPUBLISHED,item.publishedRaw,"rss",item.url,"item/pubDate",false);String created=uniqueRaw(d,"meta[itemprop=dateCreated]","content"),published=uniqueRaw(d,"meta[itemprop=datePublished]","content");SourcePublication45.Fact page=SourcePublication45.parse(OTHER_CLAIM,published,"microdata",url,"datePublished",false),creation=SourcePublication45.parse(CREATED,created,"microdata",url,"dateCreated",false);
                if(periods.size()==1){primary=periods.get(0);facts.add(rss);}else if(periods.isEmpty()&&rss.epochMillis!=null&&rss.epochMillis.equals(page.epochMillis)&&rss.epochMillis.equals(creation.epochMillis))primary=SourcePublication45.parse(ORIGINAL_PUBLISHED,item.publishedRaw,"rss",item.url,"item/pubDate",false);else{primary=SourcePublication45.unknown("","publisher-magazine",url,"original-publication-rule");facts.add(rss);conflict="UNKNOWN_ORIGINAL";}
                if(created!=null)facts.add(creation);if(published!=null)facts.add(page);String modified=uniqueRaw(d,"meta[itemprop=dateModified]","content");if(modified!=null)facts.add(SourcePublication45.parse(MODIFIED,modified,"microdata",url,"dateModified",false));break;
            }
            default:primary=SourcePublication45.parse(ORIGINAL_PUBLISHED,jsonPublished,"jsonld",url,"Article.datePublished",false);break;
        }
        if(jsonPublished!=null&&!p.dateRule.equals("JSONLD")){SourcePublication45.Fact f=SourcePublication45.parse(OTHER_CLAIM,jsonPublished,"jsonld",url,p.dateRule.equals("FORBES_TIME")?"NewsArticle.datePublished":"Article.datePublished",false);facts.add(f);if(primary.epochMillis!=null&&f.epochMillis!=null&&!primary.epochMillis.equals(f.epochMillis)&&conflict.isEmpty())conflict=p.dateRule.equals("WP_GMT")?"API_CANONICAL_DIFFER":"RSS_CANONICAL_DIFFER";}
        if(jsonModified!=null)facts.add(SourcePublication45.parse(MODIFIED,jsonModified,"jsonld",url,"Article.dateModified",false));
        if(p.dateRule.equals("WP_GMT"))for(String field:List.of("datePublished","dateModified")){String raw=uniqueRaw(d,"time[itemprop="+field+"]","datetime");if(raw!=null)facts.add(SourcePublication45.parse(field.equals("datePublished")?OTHER_CLAIM:MODIFIED,raw,"microdata",url,"time[itemprop="+field+"]@datetime",false));}
        if(p.dateRule.equals("WP_GMT")){String raw=uniqueRaw(d,"meta[name=article:published_time],meta[property=article:published_time]","content");if(raw!=null)facts.add(SourcePublication45.parse(OTHER_CLAIM,raw,"meta",url,"article:published_time",false));}
        if(primary.precision==UNKNOWN&&conflict.isEmpty()&&!primary.raw.isEmpty())conflict="INVALID";
        try{return new SourcePublication45(primary,facts,p.configSha,"date45-"+p.dateRule,conflict,api==null?SourceLimits.hash(response.bytes):api.responseSha,api==null?"":SourceLimits.hash(response.bytes),api==null?SourceSemantic45.linkDigest(d,null):api.linkProvenanceDigest,api==null?MaterialIdentity45.canonical(p,url):api.subjectUrl,api==null?"":api.subjectId);}catch(IllegalArgumentException bad){throw new SourceFailure("PUBLICATION_LIMIT",bad);}
    }
    private static SourcePublication45.Fact visibleDay(String raw,String url){
        Matcher m=Pattern.compile("(?iu)\\b([0-9]{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+([0-9]{4})\\b").matcher(raw);if(!m.find())return SourcePublication45.unknown(raw,"visible-date",url,"p.article_date");String d=m.group(1),mon=m.group(2),y=m.group(3);if(m.find())return SourcePublication45.unknown(raw,"visible-date",url,"p.article_date");
        try{int month=List.of("января","февраля","марта","апреля","мая","июня","июля","августа","сентября","октября","ноября","декабря").indexOf(mon.toLowerCase(Locale.ROOT))+1;return new SourcePublication45.Fact(ORIGINAL_PUBLISHED,DAY,raw,LocalDate.of(Integer.parseInt(y),month,Integer.parseInt(d)).toString(),null,null,"visible-date",url,"p.article_date","date45-BIO_DAY");}catch(IllegalArgumentException|DateTimeException bad){return SourcePublication45.unknown(raw,"visible-date",url,"p.article_date");}
    }
}
