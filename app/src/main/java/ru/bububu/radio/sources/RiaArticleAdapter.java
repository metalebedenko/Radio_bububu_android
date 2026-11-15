package ru.bububu.radio.sources;

import org.jsoup.nodes.*;
import okhttp3.HttpUrl;
import java.util.*;

public final class RiaArticleAdapter {
    public static final String VERSION="ria-news-v1";
    public ExtractionResult extract(SourceTransport.Response response,RequestScope scope)throws SourceFailure {
        if(!HttpUrl.get(response.finalUrl).host().equals("ria.ru"))return ClosedArticleSupport.failure("","UNSUPPORTED_HOST","publisher_html",VERSION);
        DomBudgetGuard.Parsed parsed=DomBudgetGuard.parse(response,scope);
        Element root=ClosedArticleSupport.unique(parsed.document.select(".article__body")),headline=ClosedArticleSupport.unique(parsed.document.select(".article__title"));
        String title=headline==null?"":SemanticTextNormalizer.fromDom(headline,scope);
        if(root==null||headline==null||!ClosedArticleSupport.textOnly(headline)||title.length()>SourceLimits.MAX_PROVIDER_TITLE)return ClosedArticleSupport.failure(title,"UNSUPPORTED_STRUCTURE","publisher_html",VERSION);
        ClosedArticleSupport gate=new ClosedArticleSupport(parsed,scope);
        for(Node node:root.childNodes()) {
            scope.check();if(ClosedArticleSupport.whitespace(node))continue;
            if(node instanceof Element) {
                Element child=(Element)node;
                if(child.hasClass("article__block")&&child.attr("data-type").equals("text")&&ClosedArticleSupport.directTags(child,"div")&&child.child(0).hasClass("article__text")&&ClosedArticleSupport.inline(child.child(0),Set.of("strong","a"))){gate.take(child);continue;}
                boolean verified=false;
                try{verified=excluded(child,response.finalUrl);}catch(SourceFailure changedShape){if(changedShape.reason.equals("CANCELLED")||changedShape.reason.equals("TIMEOUT"))throw changedShape;}
                if(verified){gate.exclude(child);continue;}
                // A changed AI summary invalidates completeness but never becomes the original body.
                if(child.hasClass("article__summary")){gate.unsupported=true;gate.unknown+=ClosedArticleSupport.textCount(child);continue;}
            }
            gate.reject(node);
        }
        return gate.finish(title,"publisher_html",VERSION,root,headline);
    }
    private static boolean excluded(Element e,String current)throws SourceFailure {
        if(e.hasClass("article__anchor"))return e.childrenSize()==0&&e.text().isEmpty();
        if(e.hasClass("article__summary"))return summary(e);
        if(!e.hasClass("article__block"))return false;
        if(e.attr("data-type").equals("banner"))return banner(e);
        if(!e.attr("data-type").equals("article")||!e.attr("data-nosnippet").equals("true")||e.attr("data-id").isEmpty()||e.hasAttr("data-ciad-suggested")&&!e.attr("data-ciad-suggested").equals("1"))return false;
        if(!ClosedArticleSupport.directTags(e,"div"))return false;
        Element card=e.child(0);if(!card.hasClass("article__article"))return false;
        Element title=ClosedArticleSupport.unique(card.select("a.article__article-title[href]")),overlay=ClosedArticleSupport.unique(card.select("a.article__article-link[href]")),date=ClosedArticleSupport.unique(card.select(".article__block-date")),share=ClosedArticleSupport.unique(card.select("span.share[data-url][data-id]"));
        if(title==null||overlay==null||date==null||share==null||title.text().isEmpty()||date.text().isEmpty()||!share.attr("data-id").matches("[0-9]+")||!share.attr("data-nosnippet").equals("true"))return false;
        String target=FeedDiscovery.resolve(current,title.attr("href")),other=FeedDiscovery.resolve(current,overlay.attr("href")),canonical=FeedDiscovery.resolve(current,share.attr("data-url"));
        HttpUrl first=HttpUrl.get(target),second=HttpUrl.get(other),shared=HttpUrl.get(canonical);
        if(!first.host().equals("ria.ru")||!second.host().equals("ria.ru")||!shared.host().equals("ria.ru")||!first.encodedPath().equals(shared.encodedPath())||!second.encodedPath().equals(shared.encodedPath())||shared.encodedPath().equals(HttpUrl.get(current).encodedPath())||!shared.encodedPath().endsWith("-"+share.attr("data-id")+".html"))return false;
        Set<Element> meaningful=Collections.newSetFromMap(new IdentityHashMap<>());meaningful.add(title);meaningful.add(date);
        for(Element n:card.getAllElements()) {
            String tag=n.normalName();if(!Set.of("div","img","span","a","script").contains(tag))return false;
            if(n==card||n==title||n==date)continue;
            if(tag.equals("script")){if(!n.attr("type").equals("application/ld+json"))return false;continue;}
            if(n==share||n==overlay){if(!n.text().isEmpty())return false;continue;}
            if(tag.equals("a"))return false;
            if(tag.equals("div")&&!Set.of("article__article-image","article__article-info").stream().anyMatch(n::hasClass))return false;
            if(tag.equals("span")&&!n.hasClass("article__article-desc"))return false;
            for(TextNode text:n.textNodes())if(!text.isBlank())return false;
        }
        return ClosedArticleSupport.textOnly(title)&&ClosedArticleSupport.textOnly(date);
    }
    private static boolean banner(Element e) {
        String position=e.attr("data-position");if(!position.equals("desktop-1")&&!position.equals("mobile-1")||!e.text().isEmpty()||!ClosedArticleSupport.directTags(e,"aside"))return false;
        Element aside=e.child(0);if(!ClosedArticleSupport.directTags(aside,"div","div"))return false;
        Element main=aside.child(0),hidden=aside.child(1);String form=position.startsWith("desktop")?"desktop":"mobile";
        return ClosedArticleSupport.classes(main,"banner","m-article-body","m-article-"+form)&&main.attr("data-position").equals("article_"+form+"_content-1")&&main.attr("data-changed-id").matches("adfox_article_"+form+"_content-1_[0-9]+")&&ClosedArticleSupport.directTags(main,"div")&&ClosedArticleSupport.classes(main.child(0),"banner__content")&&main.child(0).childNodeSize()==0&&ClosedArticleSupport.classes(hidden,"banner__hidden")&&ClosedArticleSupport.directTags(hidden,"a")&&ClosedArticleSupport.classes(hidden.child(0),"banner__hidden-button")&&hidden.child(0).childNodeSize()==0;
    }
    private static boolean summary(Element e) {
        if(!ClosedArticleSupport.directTags(e,"div","span","ul"))return false;Element bg=e.child(0),title=e.child(1),list=e.child(2);
        if(!ClosedArticleSupport.classes(bg,"article__summary-bg","color-bg")||!bg.text().isEmpty()||bg.childrenSize()!=0||!ClosedArticleSupport.classes(title,"article__summary-title","color-font-hover","color-svg-hover")||!title.ownText().equals("Краткий пересказ от РИА ИИ")||!ClosedArticleSupport.classes(list,"article__summary-list")||!ClosedArticleSupport.directTags(list,"li","li"))return false;
        for(Element li:list.children())if(!ClosedArticleSupport.textOnly(li))return false;
        Map<String,Integer> counts=new HashMap<>();for(Element node:title.getAllElements())counts.merge(node.normalName(),1,Integer::sum);
        if(!counts.equals(Map.of("span",2,"i",1,"svg",2,"use",2)))return false;
        Set<String> icons=new HashSet<>();for(Element use:title.select("use"))icons.add(use.attr("xlink:href"));
        if(!icons.equals(Set.of("#icon-up_single","#icon-down_single")))return false;
        for(Element node:title.getAllElements())if(node!=title&&!node.ownText().isEmpty())return false;
        return true;
    }
}
