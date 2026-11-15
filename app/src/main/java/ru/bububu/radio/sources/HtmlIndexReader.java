package ru.bububu.radio.sources;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Public publisher index: links are discovered here, article bodies require a separate checked GET. */
public final class HtmlIndexReader {
    public static final String TYPE="html_index", ADAPTER="public-html-index-v1";
    public static final String WARNING="Раздел сайта · полнота статей и дата публикации не подтверждены";
    private static final int MAX_HEADLINES=80;
    private static final String HEADINGS="h2,h3,h4,[itemprop=headline]";
    private static final String CHROME="nav,header,footer,aside,form,button,[role=navigation],[role=dialog],[hidden],[aria-hidden=true]";
    private static final Pattern NON_ARTICLE=Pattern.compile("(?i)(?:^|/)(?:login|auth|register|account|subscribe|privacy|cookies|terms|contact|search|tag|tags|category|categories|authors?|advertising)(?:/|$)|\\.(?:pdf|zip|jpe?g|png|gif|webp|svg|mp3|mp4|xml|json)$");
    private static final Set<String> CHROME_MARKERS=Set.of("nav","menu","footer","sidebar","related","recommended","recommendations","breadcrumb","breadcrumbs","popular","pagination","advert","advertisement","promo","share","social","tag","tags");
    private static final Set<String> TITLE_MARKERS=Set.of("headline","title");
    private static final Set<String> CARD_MARKERS=Set.of("story","post","article","card","teaser","newsitem");
    private static final Pattern MARKER_BREAK=Pattern.compile("(?<=[a-z])(?=[A-Z])|[^A-Za-z0-9]+");
    private HtmlIndexReader(){}
    public static boolean isIndex(SourceRecord s){return s!=null && TYPE.equals(s.type);}
    private static boolean marker(Element e,Set<String> wanted){
        for(String part:MARKER_BREAK.split(e.className()+" "+e.id()))if(wanted.contains(part.toLowerCase(Locale.ROOT)))return true;
        return false;
    }
    private static boolean excluded(Element e,Document doc){
        if(e.closest(CHROME)!=null)return true;
        for(Element n=e;n!=null&&n!=doc;n=n.parent()) {
            if(marker(n,CHROME_MARKERS))return true;
            String style=n.attr("style").toLowerCase(Locale.ROOT).replace(" ","");
            if(style.contains("display:none")||style.contains("visibility:hidden"))return true;
        }
        return false;
    }
    private static int port(URI u){return u.getPort()>=0?u.getPort():"https".equalsIgnoreCase(u.getScheme())?443:80;}
    private static boolean publisherOrigin(URI a,URI b){
        if(a.getHost()==null||b.getHost()==null||!a.getScheme().equalsIgnoreCase(b.getScheme())||port(a)!=port(b))return false;
        String left=a.getHost().toLowerCase(Locale.ROOT),right=b.getHost().toLowerCase(Locale.ROOT);
        return left.equals(right)||!left.startsWith("www.")&&right.equals("www."+left)||!right.startsWith("www.")&&left.equals("www."+right);
    }
    private static final Set<String> CAMPAIGN=Set.of("utm_source","utm_medium","utm_campaign","utm_term","utm_content");
    private static String articleIdentity(String normalized){
        okhttp3.HttpUrl url=okhttp3.HttpUrl.get(normalized);
        okhttp3.HttpUrl.Builder builder=url.newBuilder();
        for(String name:url.queryParameterNames())if(CAMPAIGN.contains(name.toLowerCase(Locale.ROOT)))builder.removeAllQueryParameters(name);
        return builder.build().toString();
    }
    private static String path(URI u){String p=u.getRawPath();return p!=null&&p.endsWith("/")?p.substring(0,p.length()-1):p;}
    static boolean sameTarget(String selected,String actual){
        try{
            URI a=URI.create(articleIdentity(SafeHttpTransport.normalizeUrl(selected))),b=URI.create(articleIdentity(SafeHttpTransport.normalizeUrl(actual)));
            return publisherOrigin(a,b) && Objects.equals(path(a),path(b)) && Objects.equals(a.getRawQuery(),b.getRawQuery());
        }catch(SourceFailure|IllegalArgumentException invalid){return false;}
    }
    static boolean articleUrl(String section,String article){
        try{
            URI base=URI.create(SafeHttpTransport.normalizeUrl(section)),url=URI.create(SafeHttpTransport.normalizeUrl(article));
            String path=url.getPath();
            return publisherOrigin(base,url)&&!sameTarget(section,article) && path!=null&&!path.isEmpty()
                    && !NON_ARTICLE.matcher(path).find();
        }catch(SourceFailure|IllegalArgumentException invalid){return false;}
    }
    private static boolean singleArticle(Document doc){
        if(!doc.select("[itemprop=articleBody],meta[property=og:type][content=article],meta[property=article:published_time]").isEmpty())return true;
        Element article=doc.selectFirst("article");
        return article!=null && doc.select("article").size()==1 && article.selectFirst("h1")!=null && article.selectFirst("p")!=null;
    }
    private static Element headline(Element link) {
        Element titled=link.selectFirst(HEADINGS);
        if(titled!=null)return titled;
        if(link.closest(HEADINGS)!=null || marker(link,TITLE_MARKERS) || link.attr("itemprop").equals("headline"))return link;
        Element card=link.closest("article,[itemtype*=NewsArticle],[itemtype*=BlogPosting],li");
        if(card==null && link.parent()!=null && marker(link.parent(),CARD_MARKERS))card=link.parent();
        if(card!=null && (link.hasAttr("rel")&&Arrays.asList(link.attr("rel").split("\\s+")).contains("bookmark")
                || card.normalName().equals("article") && card.select("a[href]").size()==1
                || card.selectFirst("time[datetime]")!=null && card.select("a[href]").size()==1))return link;
        return null;
    }
    /** Initial recognition needs multiple independent article targets (or an explicit ItemList). */
    static FeedDocument inspect(SourceTransport.Response response,Document doc,RequestScope scope,boolean connected)throws SourceFailure {
        if(response.status!=200||!("text/html".equals(response.mime)||"application/xhtml+xml".equals(response.mime)))throw new SourceFailure("HTML_INDEX_UNREADABLE");
        HtmlPageReader.requirePublicPage(doc);
        String base=SafeHttpTransport.normalizeUrl(response.finalUrl);
        if(singleArticle(doc)||HtmlPageReader.confirmedArticle(doc,scope)){if(connected)throw new SourceFailure("HTML_INDEX_UNREADABLE");return null;}
        LinkedHashMap<String,FeedItem> items=new LinkedHashMap<>();boolean capped=false;
        for(Element link:doc.select("a[href]")){
            scope.check();if(excluded(link,doc))continue;
            Element titleNode=headline(link);if(titleNode==null)continue;
            String title=SourceSemantic45.plain(titleNode,scope).replace('\n',' ').trim();
            if(title.length()<8 || title.length()>SourceLimits.MAX_PROVIDER_TITLE)continue;
            String url;
            try{url=articleIdentity(SafeHttpTransport.normalizeUrl(link.absUrl("href")));}catch(SourceFailure invalid){continue;}
            if(!articleUrl(base,url)||items.containsKey(url))continue;
            if(items.size()==MAX_HEADLINES){capped=true;break;}
            // Use only a date actually attached to this card; the index order is the fallback.
            Element card=link.closest("article,li,[itemtype*=NewsArticle],[itemtype*=BlogPosting]");
            org.jsoup.select.Elements dates=card==null?null:card.select("time[datetime]");
            Element date=dates!=null&&dates.size()==1?dates.first():null;
            String raw=date==null?null:date.attr("datetime");Long millis=null;
            if(raw!=null&&raw.length()>64)raw=null;
            if(raw!=null)try{millis=OffsetDateTime.parse(raw).toInstant().toEpochMilli();}catch(DateTimeParseException|ArithmeticException ignored){}
            items.put(url,new FeedItem(null,url,title,raw,null,millis,null,null));
        }
        int minimum=connected||doc.selectFirst("[itemtype$=ItemList]")!=null?1:2;
        if(items.size()<minimum){if(connected)throw new SourceFailure("HTML_INDEX_UNREADABLE");return null;}
        Element heading=doc.selectFirst("main h1,h1");String name=heading==null?doc.title():heading.text();
        if(genericName(name)){
            Element site=doc.selectFirst("meta[property=og:site_name][content]");
            String siteName=site==null?"":site.attr("content").strip(),host=URI.create(base).getHost();
            name=!genericName(siteName)&&!siteName.equalsIgnoreCase(host)?siteName:"";
            if(name.isEmpty())for(String part:doc.title().split("\\s+(?:[-–—|])\\s+")){
                if(!genericName(part)){name=part.strip();break;}
            }
            if(name.isEmpty())name=genericName(siteName)?host:siteName;
        }
        return new FeedDocument(SemanticTextNormalizer.displayTitle(name),base,TYPE,new ArrayList<>(items.values()),capped);
    }
    private static boolean genericName(String name){
        return name==null||name.isBlank()||Set.of("главная","главная страница","новости","последние новости","home","news","homepage")
                .contains(name.strip().toLowerCase(Locale.ROOT));
    }
    static ExtractionResult article(String section,String selected,SourceTransport.Response response,RequestScope scope)throws SourceFailure {
        if(!articleUrl(section,selected)||!sameTarget(selected,response.finalUrl))throw new SourceFailure("HTML_INDEX_ARTICLE_IDENTITY");
        Document doc=DomBudgetGuard.parse(response,scope).document;
        for(Element canon:doc.head().select("link[rel][href]")){
            if(Arrays.asList(canon.attr("rel").toLowerCase(Locale.ROOT).split("\\s+")).contains("canonical")&&!sameTarget(selected,canon.absUrl("href")))throw new SourceFailure("HTML_INDEX_ARTICLE_IDENTITY");
        }
        if(inspect(response,doc,scope,false)!=null)throw new SourceFailure("HTML_INDEX_ARTICLE_IDENTITY");
        return new HtmlPageReader().read(response,scope,doc).items.get(0).inline;
    }
}
