package ru.bububu.radio.sources;

import org.jsoup.nodes.*;
import org.jsoup.select.Elements;
import java.util.*;

final class ClosedArticleSupport {
    final DomBudgetGuard.Parsed parsed;final RequestScope scope;
    int extracted,excluded,unknown;boolean unsupported;final StringBuilder body=new StringBuilder();
    ClosedArticleSupport(DomBudgetGuard.Parsed parsed,RequestScope scope){this.parsed=parsed;this.scope=scope;}
    void take(Node node)throws SourceFailure {extracted+=textCount(node);String text=SemanticTextNormalizer.fromDom(node,scope);if(!text.isEmpty()){if(body.length()>0)body.append("\n\n");body.append(text);}}
    void exclude(Node node){excluded+=textCount(node);}
    void reject(Node node)throws SourceFailure{unsupported=true;unknown+=textCount(node);String text=SemanticTextNormalizer.fromDom(node,scope);if(!text.isEmpty()){if(body.length()>0)body.append("\n\n");body.append(text);}}
    static int textCount(Node root) {int count=0;ArrayDeque<Node> queue=new ArrayDeque<>();queue.push(root);while(!queue.isEmpty()){Node node=queue.pop();if(node instanceof TextNode&&!SemanticTextNormalizer.normalize(((TextNode)node).getWholeText()).isEmpty())count++;for(Node child:node.childNodes())queue.push(child);}return count;}
    static boolean whitespace(Node node){return node instanceof Comment || node instanceof TextNode&&((TextNode)node).isBlank();}
    static boolean textOnly(Element node){for(Node child:node.childNodes())if(!(child instanceof TextNode)&&!(child instanceof Comment))return false;return true;}
    static boolean looseEmpty(Element node){for(Node child:node.childNodes())if(child instanceof TextNode&&!((TextNode)child).isBlank())return false;return true;}
    static boolean inline(Element root,Set<String> tags) {ArrayDeque<Node> queue=new ArrayDeque<>();queue.push(root);while(!queue.isEmpty()){Node node=queue.pop();if(node!=root&&node instanceof Element&&!tags.contains(((Element)node).normalName()))return false;if(!(node instanceof Element)&&!(node instanceof TextNode)&&!(node instanceof Comment))return false;for(Node child:node.childNodes())queue.push(child);}return true;}
    static boolean classes(Element node,String...names){return node.classNames().equals(new HashSet<>(Arrays.asList(names)));}
    static boolean directTags(Element node,String... tags){if(!looseEmpty(node)||node.childrenSize()!=tags.length)return false;for(int i=0;i<tags.length;i++)if(!node.child(i).normalName().equals(tags[i]))return false;return true;}
    boolean outsideLead(Element root,Element title)throws SourceFailure {
        for(Element candidate:parsed.document.select(".article__lead,.article__announce,.article__subtitle,.article__second-title,.lead,[itemprop=description]")) {
            if(candidate==root||candidate.parents().contains(root)||candidate==title)continue;
            String text=SemanticTextNormalizer.fromDom(candidate,scope);if(!text.isEmpty()&&!text.equals(SemanticTextNormalizer.fromDom(title,scope)))return true;
        }
        Element parent=root.parent();if(parent!=null)for(Node sibling:parent.childNodes()) {
            if(sibling==root||sibling==title||whitespace(sibling))continue;
            if(sibling instanceof TextNode)return true;
            if(sibling instanceof Element&&Set.of("p","h2","h3","blockquote","ul","ol").contains(((Element)sibling).normalName())&&!SemanticTextNormalizer.fromDom(sibling,scope).isEmpty())return true;
        }
        return false;
    }
    ExtractionResult finish(String title,String method,String version,Element root,Element headline)throws SourceFailure {
        if(parsed.hasRepairWithin(root)||parsed.hasRepairWithin(headline))unsupported=true;
        boolean lead=outsideLead(root,headline);if(lead)unsupported=true;
        String text=SemanticTextNormalizer.normalize(body.toString());
        // Only an entire, separate first paragraph identical to the proved article headline is a duplicate.
        if(!title.isEmpty()&&text.startsWith(title+"\n\n"))text=text.substring(title.length()+2);
        if(text.length()>SourceLimits.MAX_TEXT)throw new SourceFailure("TEXT_LIMIT");
        String quality=text.isEmpty()?"headline_only":unsupported?"unknown":"full";
        return new ExtractionResult(text,title,SemanticTextNormalizer.displayTitle(title),quality,lead?"UNSUPPORTED_LEAD":unsupported?"UNSUPPORTED_STRUCTURE":"",method,version,extracted,excluded,unknown);
    }
    static ExtractionResult failure(String title,String reason,String method,String version){return new ExtractionResult("",title,SemanticTextNormalizer.displayTitle(title),title.isEmpty()?"unavailable":"headline_only",reason,method,version);}
    static Element unique(Elements elements){return elements.size()==1?elements.first():null;}
    static boolean otherLink(Element link,String current)throws SourceFailure {
        String target=FeedDiscovery.resolve(current,link.attr("href"));return !target.equals(SafeHttpTransport.normalizeUrl(current));
    }
}
