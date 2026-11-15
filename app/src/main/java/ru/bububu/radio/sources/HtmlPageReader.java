package ru.bububu.radio.sources;

import java.util.*;
import org.jsoup.nodes.*;

/** Best-effort public HTML only; no scripts, account session, or publisher completeness claim. */
public final class HtmlPageReader {
    public static final String TYPE="html_page", ADAPTER="experimental-html50-v2";
    static final String PREVIOUS_ADAPTER="experimental-html50-v1";
    public static final String WARNING="Экспериментально: текст может быть неполным или содержать лишнее";
    private static final String CONFIG=SourceLimits.sha256(ADAPTER+":semantic-main:public-page:unknown-publication");
    private static final Set<String> SERVICE=Set.of("ad","ads","advert","advertisement","advertising","banner","cookie","cookies","consent","newsletter","subscribe","subscription","share","sharing","social","related","recommendations","menu","navigation","pagination","breadcrumb","breadcrumbs","comments","comment","login","signin");
    public static boolean isPage(SourceRecord source){return source!=null&&TYPE.equals(source.type);}
    public FeedDocument read(SourceTransport.Response response,RequestScope scope)throws SourceFailure {
        if(response.status!=200||!("text/html".equals(response.mime)||"application/xhtml+xml".equals(response.mime)))throw new SourceFailure("HTML_PAGE_UNSUPPORTED");
        return read(response,scope,DomBudgetGuard.parse(response,scope).document);
    }
    FeedDocument read(SourceTransport.Response response,RequestScope scope,Document doc)throws SourceFailure {
        String url=SafeHttpTransport.normalizeUrl(response.finalUrl);
        requirePublicPage(doc);
        Story story=story(doc,scope);
        String fallback=doc.title();Element meta=doc.selectFirst("meta[property=og:title][content]");if(meta!=null&&!meta.attr("content").isBlank())fallback=meta.attr("content");
        Element firstHeading=doc.selectFirst("h1");String heading=firstHeading==null?"":SourceSemantic45.plain(firstHeading,scope);
        removeMetadata(doc,scope);
        doc.select("script,style,noscript,template,svg,canvas,iframe,object,embed,audio,video,form,input,button,select,textarea,nav,footer,header,aside,[hidden],[aria-hidden=true],[role=navigation],[role=banner],[role=contentinfo],[role=dialog]").remove();
        int work=0;for(Element e:new ArrayList<>(doc.getAllElements())){
            if((++work&127)==0&&scope!=null)scope.check();
            String tokens=e.id()+" "+e.className()+" "+e.attr("role");boolean service=false;
            for(String token:tokens.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))if(SERVICE.contains(token)){service=true;break;}
            String style=e.attr("style").toLowerCase(Locale.ROOT).replaceAll("\\s+","");
            if(service||style.contains("display:none")||style.contains("visibility:hidden"))e.remove();
        }
        List<Element> roots=new ArrayList<>();
        if(story!=null)for(Element part:story.bodies)if(part.root()==doc)roots.add(part);
        if(roots.isEmpty()){
            Element root=choose(doc,scope);
            if(root==null)throw new SourceFailure("HTML_PAGE_EMPTY");
            roots.add(root);
        }
        Element titleNode=roots.get(0).selectFirst("h1");String title=story==null?(titleNode==null?"":SourceSemantic45.plain(titleNode,scope)):story.title;
        if(title.isBlank())title=SemanticTextNormalizer.normalize(fallback).replace('\n',' ');
        if(title.isBlank())title=heading;if(title.isBlank())title=java.net.URI.create(url).getHost();
        if(title.length()>SourceLimits.MAX_PROVIDER_TITLE)throw new SourceFailure("TITLE_LIMIT");
        if(story!=null && story.headline.root()==doc && !Collections.disjoint(story.headline.parents(),roots))story.headline.remove();
        else if(titleNode!=null&&title.equals(SourceSemantic45.plain(titleNode,scope)))titleNode.remove();
        StringJoiner paragraphs=new StringJoiner("\n\n");
        for(Element root:roots){
            String part=SourceSemantic45.plain(root,scope);
            if(!part.isBlank())paragraphs.add(part);
            if(paragraphs.length()>SourceLimits.MAX_SOURCE_SPOKEN_UTF16)throw new SourceFailure("TEXT_LIMIT");
        }
        String body=paragraphs.toString();
        if(story!=null && story.lead!=null && story.lead.root()==doc && !body.isBlank()){
            String lead=SourceSemantic45.plain(story.lead,scope);
            if(!lead.isBlank()&&!SourceSemantic45.fold(body).contains(SourceSemantic45.fold(lead)))body=lead+"\n\n"+body;
        }
        if(body.length()>SourceLimits.MAX_SOURCE_SPOKEN_UTF16)throw new SourceFailure("TEXT_LIMIT");
        if(body.isBlank()||body.codePoints().noneMatch(Character::isLetterOrDigit))throw new SourceFailure("HTML_PAGE_EMPTY");
        SourcePublication45.Fact unknown=SourcePublication45.unknown("","experimental_html",url,"page");
        SourcePublication45 publication=new SourcePublication45(unknown,List.of(unknown),CONFIG,ADAPTER,"",SourceLimits.hash(response.bytes),"","",url,"experimental-html-page");
        ExtractionResult text=new ExtractionResult(body,title,SemanticTextNormalizer.displayTitle(title),"unknown","EXPERIMENTAL_HTML_PAGE","experimental_html",ADAPTER).withPublication(publication);
        FeedItem item=new FeedItem("experimental-html-page",url,title,null,null,null,null,text);
        return new FeedDocument(text.displayTitle,url,TYPE,List.of(item),false);
    }
    private static final Set<String> BODY_MARKERS=Set.of("body","text","content","prose");
    private static final Set<String> LEAD_MARKERS=Set.of("lead","subtitle","standfirst","deck","dek");
    private static final class Story {
        final String title;final Element headline,lead;final List<Element> bodies;
        Story(String t,Element h,List<Element> b,Element l){title=t;headline=h;bodies=b;lead=l;}
    }
    private static boolean sameProseRole(Element first,Element next){
        if(first.parent()!=next.parent()||!first.normalName().equals(next.normalName()))return false;
        if(first.attr("itemprop").equals("articleBody")&&next.attr("itemprop").equals("articleBody"))return true;
        for(String name:first.classNames())if(next.hasClass(name)){
            for(String word:MARKER_BREAK.split(name))if(BODY_MARKERS.contains(word.toLowerCase(Locale.ROOT)))return true;
        }
        return false;
    }
    static boolean confirmedArticle(Document doc,RequestScope scope)throws SourceFailure{return story(doc,scope)!=null;}
    /** Bind one document heading to its own prose before general density selection. */
    private static Story story(Document doc,RequestScope scope)throws SourceFailure {
        Element meta=doc.selectFirst("meta[property=og:title][content]");
        String pageTitle=meta==null?doc.title():meta.attr("content");
        String expected=SourceSemantic45.fold(pageTitle);
        Element heading=null;int work=0;
        for(Element h:doc.select("h1,h2,[itemprop=headline]")){
            if((++work&127)==0&&scope!=null)scope.check();
            if(h.closest("nav,aside,footer,[hidden],[aria-hidden=true]")!=null)continue;
            if(h.closest("header")!=null&&h.closest("article")==null)continue;
            String name=SourceSemantic45.plain(h,scope);
            boolean identity=!expected.isEmpty()&&expected.equals(SourceSemantic45.fold(name));
            boolean declared=h.normalName().equals("h1")&&doc.selectFirst("meta[property=og:type][content=article]")!=null;
            if((identity||declared)&&!name.isBlank()){if(heading!=null)return null;heading=h;}
        }
        if(heading==null)return null;
        String title=SourceSemantic45.plain(heading,scope);
        for(Element area=heading.parent();area!=null && !area.normalName().equals("body");area=area.parent()){
            if(scope!=null)scope.check();
            // Never jump across a sibling story container when trying to match the heading.
            if(area.selectFirst("p")==null)continue;
            List<Element> parts=new ArrayList<>();Element owner=heading.closest("article");
            for(Element e:area.select("[itemprop=articleBody],div[class],section[class]")){
                if((++work&127)==0&&scope!=null)scope.check();
                if(e.closest("article")!=owner || e.selectFirst("p")==null || e.closest("aside,nav,footer,[hidden],[aria-hidden=true]")!=null
                        || !e.attr("itemprop").equals("articleBody")&&Collections.disjoint(markers(e),BODY_MARKERS))continue;
                if(e==area || heading.parents().contains(e) || e.selectFirst("h1,h2 a[href],article")!=null)continue;
                // DOM order places nested formatting wrappers after their accepted parent.
                if(!parts.isEmpty()){
                    if(e.parents().contains(parts.get(parts.size()-1)))continue;
                    if(!sameProseRole(parts.get(0),e))return null;
                }
                parts.add(e);
            }
            if(parts.isEmpty() && area.normalName().equals("article") && area.select("article").size()<=1)parts.add(area);
            if(!parts.isEmpty()){
                if(parts.size()>1){
                    // An independent headline between otherwise similar blocks marks a different story.
                    for(Element h:area.select("h1,h2"))if(h!=heading&&h.closest("article")==owner
                            && Collections.disjoint(h.parents(),parts))return null;
                }
                Element lead=null;
                for(Element e:area.select("[class],[itemprop=description]")){
                    if((++work&127)==0&&scope!=null)scope.check();
                    if(parts.contains(e) || !Collections.disjoint(e.parents(),parts) || e.closest("article")!=owner || e.closest("aside,figure,nav,footer")!=null)continue;
                    if(!e.attr("itemprop").equals("description") && Collections.disjoint(markers(e),LEAD_MARKERS))continue;
                    if(lead!=null){lead=null;break;}lead=e;
                }
                return new Story(title,heading,parts,lead);
            }
            if(area.normalName().equals("article")||area.select("h1,h2").size()>1)return null;
        }
        return null;
    }
    static void requirePublicPage(Document doc)throws SourceFailure {
        if(!doc.select(".paywall,.paywall-overlay,.subscription-wall,.captcha,.g-recaptcha,.h-captcha,#cf-challenge-running,[data-paywall=true],[itemprop=isAccessibleForFree][content=false]").isEmpty())throw new SourceFailure("HTML_PAGE_ACCESS_REQUIRED");
    }
    private static final Set<String> META_PROPERTIES=Set.of("author","datePublished","dateModified","articleSection","keywords");
    private static final Set<String> META_LINK=Set.of("author","authors","tag","tags","topic","topics","rubric","rubrics","category","categories","date");
    private static final String PROSE="p,li,article,main,blockquote,figure,figcaption,pre,table,h1,h2,h3,h4,h5,h6";
    private static final java.util.regex.Pattern MARKER_BREAK=java.util.regex.Pattern.compile("(?<=[a-z])(?=[A-Z])|[^A-Za-z0-9]+");

    private static void removeMetadata(Document doc,RequestScope scope) throws SourceFailure {
        int count=0;
        for(Element e:doc.select("[itemprop],a[rel],a[href],[role],[class],[id]")) {
            if((++count&127)==0 && scope!=null)scope.check();
            if(e.parent()==null || e.root()!=doc)continue;
            Set<String> markers=markers(e);
            boolean explicit=hasToken(e.attr("itemprop"),META_PROPERTIES)
                    || e.normalName().equals("a")&&hasToken(e.attr("rel").toLowerCase(Locale.ROOT),Set.of("author","tag"));
            boolean authorLink=e.normalName().equals("a") && (markers.contains("author")||markers.contains("authors"))
                    && directoryLink(e,"authors?");
            boolean metaPanel=markers.contains("meta")||markers.contains("metadata");
            boolean tabPanel=markers.contains("tabs")||e.attr("role").equals("tablist");
            if(!explicit&&!authorLink&&!metaPanel&&!tabPanel)continue;
            // Keep author names, figures, hashtags and semantic links in the editor's prose.
            if(e.closest("p,li,blockquote,figure,figcaption,pre,table,h1,h2,h3,h4,h5,h6")!=null || e.is(PROSE)
                    || e.selectFirst(PROSE)!=null)continue;
            if((explicit||authorLink) && surroundedByProse(e))continue;
            if(explicit||authorLink || auxiliaryPanel(e,metaPanel,tabPanel))e.remove();
        }
    }
    private static boolean surroundedByProse(Element e) {
        for(Element parent=e.parent();parent!=null;parent=parent.parent()){
            if(!parent.ownText().isBlank())return true;
            if(parent.tag().isBlock())break;
        }
        return false;
    }
    private static Set<String> markers(Element e) {
        String[] parts=MARKER_BREAK.split(e.id()+" "+e.className());
        Set<String> found=new HashSet<>();for(String part:parts)found.add(part.toLowerCase(Locale.ROOT));
        return found;
    }
    private static boolean hasToken(String value,Set<String> accepted) {
        for(String token:value.split("\\s+"))if(accepted.contains(token))return true;
        return false;
    }
    private static boolean directoryLink(Element e,String directory) {
        try{
            java.net.URI page=java.net.URI.create(e.baseUri()),url=java.net.URI.create(e.absUrl("href"));
            return page.getHost()!=null && page.getHost().equalsIgnoreCase(url.getHost())
                    && url.getPath()!=null && url.getPath().matches("(?i).*/(?:"+directory+")/[^/]+(?:/.*)?");
        }catch(IllegalArgumentException invalid){return false;}
    }
    private static boolean auxiliaryPanel(Element panel,boolean meta,boolean tabs) {
        if(!panel.ownText().isBlank() || panel.selectFirst("button,[role=tab]")==null)return false;
        // A container can mix article copy with metadata, even on a page without paragraph tags.
        // Remove the container only when every text node is inside a verified widget component.
        for(Element child:panel.getAllElements()) {
            if(child.ownText().isBlank())continue;
            if(child.closest("button,[role=tab],[role=tooltip],time[datetime]")!=null)continue;
            Element link=child.closest("a[href]");
            if(link!=null && (hasToken(link.attr("rel"),Set.of("tag","author"))
                    || !Collections.disjoint(markers(link),META_LINK)))continue;
            boolean hint=false;
            for(Element a=child;a!=null && a!=panel;a=a.parent())if(markers(a).contains("tooltip")){hint=true;break;}
            if(!hint)return false;
        }
        if(meta && (panel.selectFirst("time[datetime]")!=null
                || panel.select("a[href]").stream().anyMatch(link->directoryLink(link,"date") && markers(link).contains("date"))))return true;
        return tabs && panel.select("a[href]").stream().anyMatch(link->hasToken(link.attr("rel"),Set.of("tag"))
                || directoryLink(link,"tags?|topics?|categor(?:y|ies)") && !Collections.disjoint(markers(link),Set.of("tag","tags","topic","topics","category","categories")));
    }
    private static final class Weight {int text,links;long score(){return (long)text-3L*links;}}
    private static Element choose(Document doc,RequestScope scope)throws SourceFailure {
        List<Element> elements=new ArrayList<>(doc.getAllElements());Map<Node,Weight> weights=new IdentityHashMap<>();
        for(int i=elements.size()-1;i>=0;i--){if((i&127)==0&&scope!=null)scope.check();Element e=elements.get(i);Weight w=new Weight();
            for(Node child:e.childNodes())if(child instanceof TextNode)w.text+=((TextNode)child).getWholeText().strip().length();else {Weight nested=weights.get(child);if(nested!=null){w.text+=nested.text;w.links+=nested.links;}}
            if(e.normalName().equals("a"))w.links=w.text;weights.put(e,w);
        }
        for(String selector:List.of("[itemprop=articleBody]","article","main,[role=main]","div,section,body")){
            Element best=null;long score=0;List<Element> candidates=new ArrayList<>(doc.select(selector));Collections.reverse(candidates);
            for(Element e:candidates){Weight w=weights.get(e);if(w!=null&&w.score()>score){best=e;score=w.score();}}
            if(best!=null)return best;
        }
        return null;
    }
}
