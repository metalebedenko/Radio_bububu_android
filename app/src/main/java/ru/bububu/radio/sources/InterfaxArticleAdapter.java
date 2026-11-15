package ru.bububu.radio.sources;

import org.jsoup.nodes.*;
import okhttp3.HttpUrl;
import java.util.*;

public final class InterfaxArticleAdapter {
    public static final String VERSION="interfax-news-v1";
    public ExtractionResult extract(SourceTransport.Response response,RequestScope scope)throws SourceFailure {
        if(!HttpUrl.get(response.finalUrl).host().equals("www.interfax.ru"))return ClosedArticleSupport.failure("","UNSUPPORTED_HOST","publisher_html",VERSION);
        DomBudgetGuard.Parsed parsed=DomBudgetGuard.parse(response,scope);
        Element root=ClosedArticleSupport.unique(parsed.document.select("article[itemprop=articleBody]")),headline=ClosedArticleSupport.unique(parsed.document.select("h1[itemprop=headline]"));
        String title=headline==null?"":SemanticTextNormalizer.fromDom(headline,scope);
        if(root==null||headline==null||!ClosedArticleSupport.textOnly(headline)||title.length()>SourceLimits.MAX_PROVIDER_TITLE)return ClosedArticleSupport.failure(title,"UNSUPPORTED_STRUCTURE","publisher_html",VERSION);
        ClosedArticleSupport gate=new ClosedArticleSupport(parsed,scope);
        for(Node node:root.childNodes()) {
            scope.check();if(ClosedArticleSupport.whitespace(node))continue;
            if(node==headline){gate.exclude(node);continue;}
            if(node instanceof Element) {
                Element child=(Element)node;
                if(child.normalName().equals("p")&&ClosedArticleSupport.inline(child,Set.of("a","br"))){gate.take(child);continue;}
                boolean verified=false;
                try{verified=excluded(child,response.finalUrl);}catch(SourceFailure changedShape){if(changedShape.reason.equals("CANCELLED")||changedShape.reason.equals("TIMEOUT"))throw changedShape;}
                if(verified){gate.exclude(child);continue;}
            }
            gate.reject(node);
        }
        return gate.finish(title,"publisher_html",VERSION,root,headline);
    }
    private static boolean excluded(Element e,String current)throws SourceFailure {
        String tag=e.normalName();
        if(tag.equals("meta"))return e.childNodeSize()==0;
        if(tag.equals("link"))return e.attr("itemprop").equals("image")&&e.childNodeSize()==0;
        if(tag.equals("div")&&ClosedArticleSupport.classes(e,"h")&&Set.of("author","publisher").contains(e.attr("itemprop"))) {
            if(!ClosedArticleSupport.looseEmpty(e))return false;
            if(e.attr("itemprop").equals("publisher")&&publisherWithLogo(e))return true;
            for(Element child:e.children())if(!(child.normalName().equals("meta")||child.normalName().equals("link"))||child.childNodeSize()!=0)return false;
            return true;
        }
        if(tag.equals("figure")&&ClosedArticleSupport.classes(e,"inner")&&ClosedArticleSupport.directTags(e,"img","figcaption")) {
            Element image=e.child(0),caption=e.child(1);
            return image.attr("itemprop").equals("image")&&image.childNodeSize()==0&&ClosedArticleSupport.classes(caption,"innerTable")&&ClosedArticleSupport.directTags(caption,"div")&&ClosedArticleSupport.classes(caption.child(0),"author")&&ClosedArticleSupport.textOnly(caption.child(0))&&!caption.child(0).text().isEmpty();
        }
        if(tag.equals("aside")&&ClosedArticleSupport.classes(e,"textMUpdate")&&ClosedArticleSupport.directTags(e,"div","div")) {
            Element first=e.child(0),second=e.child(1);
            if(!first.ownText().matches("Есть обновление от [0-9]{2}:[0-9]{2}")||first.childrenSize()!=1||!first.child(0).normalName().equals("span")||!ClosedArticleSupport.textOnly(first.child(0))||!first.child(0).text().equals("→")||!ClosedArticleSupport.directTags(second,"a"))return false;
            Element link=second.child(0);return ClosedArticleSupport.textOnly(link)&&!link.text().isEmpty()&&ClosedArticleSupport.otherLink(link,current)&&HttpUrl.get(FeedDiscovery.resolve(current,link.attr("href"))).host().equals("www.interfax.ru");
        }
        if(tag.equals("div")&&ClosedArticleSupport.classes(e,"wg_news","at_e","left")&&e.attr("data-id").matches("[0-9]+")&&ClosedArticleSupport.directTags(e,"a","time","a","span")) {
            Element category=e.child(0),time=e.child(1),link=e.child(2),summary=e.child(3);
            if(!ClosedArticleSupport.classes(category,"e_sect")||!ClosedArticleSupport.classes(link,"e_text")||!time.hasAttr("datetime")||!ClosedArticleSupport.textOnly(category)||!ClosedArticleSupport.textOnly(time)||!ClosedArticleSupport.textOnly(link)||!ClosedArticleSupport.textOnly(summary))return false;
            try{java.time.LocalDateTime.parse(time.attr("datetime"));}catch(java.time.DateTimeException invalid){return false;}
            String target=FeedDiscovery.resolve(current,link.attr("href"));HttpUrl url=HttpUrl.get(target);
            return url.host().equals("www.interfax.ru")&&url.encodedPath().endsWith("/"+e.attr("data-id"))&&ClosedArticleSupport.otherLink(link,current)&&!link.text().isEmpty();
        }
        return false;
    }
    /** Exact empty publisher/logo metadata shape present in the accepted block inventory. */
    private static boolean publisherWithLogo(Element publisher) {
        if(!publisher.hasAttr("itemscope")||!publisher.attr("itemtype").equals("https://schema.org/Organization")||!ClosedArticleSupport.directTags(publisher,"div","meta","link"))return false;
        Element logo=publisher.child(0),name=publisher.child(1),url=publisher.child(2);
        if(!logo.attr("itemprop").equals("logo")||!logo.hasAttr("itemscope")||!logo.attr("itemtype").equals("https://schema.org/ImageObject")||!logo.classNames().isEmpty()||!ClosedArticleSupport.directTags(logo,"link","link","meta","meta"))return false;
        Element image=logo.child(0),thumbnail=logo.child(1),width=logo.child(2),height=logo.child(3);
        for(Element leaf:List.of(image,thumbnail,width,height,name,url))if(leaf.childNodeSize()!=0)return false;
        return image.attr("itemprop").equals("url")&&image.attr("rel").equals("url")&&image.attr("href").equals("https://www.interfax.ru/img/logo200.png")
            &&thumbnail.attr("itemprop").equals("thumbnail")&&thumbnail.attr("rel").equals("thumbnail")&&thumbnail.attr("href").equals(image.attr("href"))
            &&width.attr("itemprop").equals("width")&&width.attr("content").equals("200")&&height.attr("itemprop").equals("height")&&height.attr("content").equals("60")
            &&name.attr("itemprop").equals("name")&&name.attr("content").equals("Интерфакс")&&url.attr("itemprop").equals("url")&&url.attr("rel").equals("url")&&url.attr("href").equals("https://www.interfax.ru");
    }
}
