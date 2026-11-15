package ru.bububu.radio.sources;

import java.net.URI;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/** Structural, source-bound cleanup of the rich article in an RSS content:encoded field. */
final class RssArticleText {
    static final String REVISION="feed-inline-article-v2";
    final String body;
    final boolean cleaned;

    private RssArticleText(String body,boolean cleaned){this.body=body;this.cleaned=cleaned;}

    static RssArticleText read(String markup,String base,String article,String title,RequestScope scope)throws SourceFailure {
        DomBudgetGuard.Parsed parsed=DomBudgetGuard.parse(markup,base,scope);
        Element body=parsed.document.body();
        boolean changed=false;
        for(Element figure:body.select("figure")){
            scope.check();
            if(!parsed.hasRepairWithin(figure)&&removeDuplicateCaption(figure))changed=true;
        }
        Element tail=lastParagraph(body);
        if(tail!=null && !parsed.hasRepairWithin(tail) && isPublicationCredit(tail,base,article,title)
                && body.select("p").stream().anyMatch(p->p!=tail&&!p.text().isBlank())){
            tail.remove();
            changed=true;
        }
        return new RssArticleText(SemanticTextNormalizer.fromDom(body,scope),changed);
    }

    private static final Pattern IMAGE_DESCRIPTION=Pattern.compile("(?i)(?:image|photo)[_-]?description");
    private static final Pattern CREDIT=Pattern.compile("\\s+/\\s*©\\s*\\S.*");
    private static boolean removeDuplicateCaption(Element figure){
        if(figure.selectFirst("img")==null)return false;
        Element caption=null,description=null;
        for(Element e:figure.children()){
            if(e.normalName().equals("figcaption")){
                if(caption!=null)return false;
                caption=e;
            }else if(e.classNames().stream().anyMatch(token->IMAGE_DESCRIPTION.matcher(token).matches())){
                if(description!=null || !e.normalName().equals("div") || e.childrenSize()!=0)return false;
                description=e;
            }
        }
        if(description==null || caption==null)return false;
        String copy=description.text(),full=caption.text();
        if(copy.isBlank() || !full.startsWith(copy))return false;
        String extra=full.substring(copy.length());
        if(!extra.isEmpty()&&!CREDIT.matcher(extra).matches())return false;
        description.remove();
        return true;
    }

    /** Follow the actual end of the fragment; quoted, captioned or trailing editorial text wins. */
    private static Element lastParagraph(Element root){
        Element current=root;
        while(Set.of("body","div","article","section").contains(current.normalName())){
            Node last=current.lastChild();
            while(last instanceof Comment || last instanceof TextNode && ((TextNode)last).isBlank())last=last.previousSibling();
            if(!(last instanceof Element))return null;
            current=(Element)last;
        }
        return current.normalName().equals("p")?current:null;
    }

    private static boolean isPublicationCredit(Element p,String base,String article,String title){
        if(title==null||title.isBlank()||article==null||p.childrenSize()!=2)return false;
        Element self=p.child(0),publisher=p.child(1);
        if(!self.is("a[href]")||!publisher.is("a[href]")||self.childrenSize()!=0||publisher.childrenSize()!=0
                ||!self.text().equals(title)||publisher.text().isBlank())return false;
        String marker="«"+title+"»",site=publisher.text()+".";
        if(!p.text().equals("Материал "+marker+" был впервые опубликован на сайте "+site)
                &&!p.text().equals("Статья "+marker+" была впервые опубликована на сайте "+site)
                &&!p.text().equals("The post "+title+" appeared first on "+site))return false;
        try{
            URI target=URI.create(SafeHttpTransport.normalizeUrl(article)),feed=URI.create(SafeHttpTransport.normalizeUrl(base));
            String first=self.absUrl("href"),home=publisher.absUrl("href");
            return !first.contains("#")&&!home.contains("#") && target.toString().equals(SafeHttpTransport.normalizeUrl(first))
                    && !target.getPath().equals("/") && target.resolve("/").equals(feed.resolve("/"))
                    && target.resolve("/").toString().equals(SafeHttpTransport.normalizeUrl(home));
        }catch(SourceFailure|IllegalArgumentException invalid){return false;}
    }
}
