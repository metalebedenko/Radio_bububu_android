package ru.bububu.radio.sources;

import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.jsoup.nodes.Element;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;

public final class XmlFeedReader {
    private static final String ATOM="http://www.w3.org/2005/Atom",XHTML="http://www.w3.org/1999/xhtml",XML="http://www.w3.org/XML/1998/namespace",CONTENT="http://purl.org/rss/1.0/modules/content/";
    /** The catalogue's observed deviation is never inferred from a custom feed URL. */
    public enum RssPolicy { STRICT_RSS2, RIA_CATALOGUE }
    private static final String RIA_CATALOGUE_URL="https://ria.ru/export/rss2/archive/index.xml";
    private static final class X {
        final String name,ns,base;final Map<String,String> attrs=new HashMap<>();final List<X> children=new ArrayList<>();final List<Object> content=new ArrayList<>();final StringBuilder text=new StringBuilder();boolean large,textLarge,attributeLarge;
        X(String name,String ns,String base){this.name=name;this.ns=ns;this.base=base;}
        X child(String namespace,String local){for(X child:children)if(child.ns.equals(namespace)&&child.name.equals(local))return child;return null;}
        String attr(String ns,String name){return attrs.getOrDefault(ns+"|"+name,"");}
        String value(){StringBuilder out=new StringBuilder();for(Object part:content)out.append(part instanceof X?((X)part).value():(String)part);return out.toString();}
    }
    public FeedDocument parse(SourceTransport.Response response,RequestScope scope)throws SourceFailure {
        return parse(response,scope,RssPolicy.STRICT_RSS2);
    }
    public FeedDocument parse(SourceTransport.Response response,RequestScope scope,RssPolicy policy)throws SourceFailure {
        Objects.requireNonNull(policy,"RSS policy");
        if(response.status!=200)throw new SourceFailure("XML_HTTP_STATUS");
        XmlDeclarationGuard.Checked input=XmlDeclarationGuard.check(response.bytes,response.charset,scope);
        try {
            XmlPullParser parser=Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false);
            if(!parser.getFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES)||parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL))throw new SourceFailure("XML_FEATURES");
            parser.setInput(new StringReader(input.text));
            ArrayDeque<X> stack=new ArrayDeque<>();X root=null;int tokens=0,attributes=0,itemCount=0,skipDepth=-1;boolean limit=false;
            for(int event=parser.getEventType();event!=XmlPullParser.END_DOCUMENT;event=parser.nextToken()) {
                if(++tokens>100_000)throw new SourceFailure("XML_TOKEN_LIMIT");if((tokens&127)==0)scope.check();
                if(event==XmlPullParser.DOCDECL)throw new SourceFailure("XML_DTD");
                if(event==XmlPullParser.START_TAG) {
                    if(parser.getDepth()>64)throw new SourceFailure("XML_DEPTH");
                    attributes+=parser.getAttributeCount();if(attributes>100_000)throw new SourceFailure("XML_ATTRIBUTE_LIMIT");
                    boolean item=parser.getName().equals("item")&&parser.getDepth()==3&&root!=null&&root.name.equals("rss")&&!stack.isEmpty()&&stack.peek().name.equals("channel")&&stack.peek().ns.isEmpty() || parser.getName().equals("entry")&&parser.getDepth()==2&&ATOM.equals(parser.getNamespace())&&root!=null&&root.ns.equals(ATOM)&&root.name.equals("feed");
                    if(item&&++itemCount>SourceLimits.MAX_PARSED_ITEMS){limit=true;if(skipDepth<0)skipDepth=parser.getDepth();}
                    if(skipDepth>=0)continue;
                    String base=stack.isEmpty()?response.finalUrl:stack.peek().base;
                    String xmlBase=parser.getAttributeValue(XML,"base");if(xmlBase!=null)base=FeedDiscovery.resolve(base,xmlBase);
                    X node=new X(parser.getName(),parser.getNamespace()==null?"":parser.getNamespace(),base);
                    for(int i=0;i<parser.getAttributeCount();i++){String value=parser.getAttributeValue(i);if(value.length()>SourceLimits.MAX_TEXT){node.large=true;node.attributeLarge=true;}else node.attrs.put((parser.getAttributeNamespace(i)==null?"":parser.getAttributeNamespace(i))+"|"+parser.getAttributeName(i),value);}
                    if(stack.isEmpty()){if(root!=null)throw new SourceFailure("XML_FORMAT");root=node;}else{stack.peek().children.add(node);stack.peek().content.add(node);}stack.push(node);
                } else if(event==XmlPullParser.END_TAG) {
                    if(skipDepth>=0){if(parser.getDepth()==skipDepth)skipDepth=-1;continue;}
                    if(stack.isEmpty())throw new SourceFailure("XML_FORMAT");stack.pop();
                } else if(event==XmlPullParser.TEXT||event==XmlPullParser.CDSECT||event==XmlPullParser.ENTITY_REF||event==XmlPullParser.IGNORABLE_WHITESPACE) {
                    String text=parser.getText();if(event==XmlPullParser.ENTITY_REF&&text==null)throw new SourceFailure("XML_ENTITY");
                    if(skipDepth<0&&!stack.isEmpty()&&text!=null){X node=stack.peek();if(node.text.length()>SourceLimits.MAX_TEXT-text.length()){node.large=true;node.textLarge=true;}else{node.text.append(text);node.content.add(text);}}
                }
            }
            scope.check();if(root==null||!stack.isEmpty())throw new SourceFailure("XML_FORMAT");
            List<FeedItem> items=new ArrayList<>();String title,type;
            if(root.name.equals("rss")&&root.ns.isEmpty()&&root.attr("","version").equals("2.0")) {
                List<X> channels=new ArrayList<>();for(X child:root.children)if(child.ns.isEmpty()&&child.name.equals("channel"))channels.add(child);
                if(channels.size()!=1)throw new SourceFailure("RSS_CHANNEL");X channel=channels.get(0);
                if(!acceptedRssChannel(channel,response.finalUrl,policy))throw new SourceFailure("RSS_CHANNEL");
                title=value(channel.child("","title"));type="rss";
                for(X item:channel.children)if(item.ns.isEmpty()&&item.name.equals("item"))items.add(rss(item,scope));
            } else if(root.name.equals("feed")&&root.ns.equals(ATOM)) {
                if(root.child(ATOM,"title")==null||root.child(ATOM,"id")==null||root.child(ATOM,"updated")==null)throw new SourceFailure("ATOM_FEED_METADATA");
                title=construct(root.child(ATOM,"title"),scope);type="atom";
                for(X item:root.children)if(item.ns.equals(ATOM)&&item.name.equals("entry"))items.add(atom(item,scope));
            } else throw new SourceFailure("UNSUPPORTED_FEED_FORMAT");
            scope.check();return new FeedDocument(SemanticTextNormalizer.displayTitle(title),response.finalUrl,type,items,limit);
        }catch(XmlPullParserException|IOException failure){if(failure instanceof SourceFailure)throw (SourceFailure)failure;throw new SourceFailure("XML_FORMAT",failure);}
        catch(IllegalArgumentException invalid){throw new SourceFailure("XML_FORMAT",invalid);}
    }
    private static boolean acceptedRssChannel(X channel,String finalUrl,RssPolicy policy) {
        if(channel.child("","title")==null||channel.child("","link")==null||value(channel.child("","title")).trim().isEmpty())return false;
        if(channel.child("","description")!=null)return true;
        if(policy!=RssPolicy.RIA_CATALOGUE||!RIA_CATALOGUE_URL.equals(finalUrl)||channel.large||!channel.text.toString().trim().isEmpty())return false;
        Set<String> metadata=new HashSet<>();
        for(X child:channel.children) {
            if(!child.ns.isEmpty())return false;
            if(child.name.equals("item"))continue;
            if(!Set.of("title","link","language","copyright").contains(child.name)||!metadata.add(child.name)||!child.children.isEmpty()||large(child))return false;
        }
        return metadata.size()==4&&value(channel.child("","link")).trim().equals("https://ria.ru")&&value(channel.child("","language")).trim().equals("ru")&&value(channel.child("","copyright")).trim().equals("RIA Novosti");
    }
    private static FeedItem rss(X item,RequestScope scope)throws SourceFailure {
        String title=SemanticTextNormalizer.normalize(value(item.child("","title"))),id=value(item.child("","guid")),raw=value(item.child("","pubDate"));
        String url=safeLink(item.child("","link"));X content=item.child(CONTENT,"encoded"),description=item.child("","description");
        boolean fallback=oversizedEncodedFallback(item,content,description,url);
        ExtractionResult text;
        if((large(item)&&!fallback)||title.length()>SourceLimits.MAX_PROVIDER_TITLE||id!=null&&id.length()>SourceLimits.MAX_PROVIDER_ID)text=error(title,"ITEM_LIMIT");
        else {
            X selected=fallback?description:content!=null?content:description;
            try {
                RssArticleText article=content!=null&&selected==content?RssArticleText.read(value(content),item.base,url,title,scope):null;
                String body=article!=null?article.body:selected==null?"":html(value(selected),item.base,scope);
                if(fallback) {
                    if("None".equals(body))body="";
                    text=fallbackResult(body,title,body.isEmpty()?"DESCRIPTION_FALLBACK_EMPTY":"OVERSIZED_CONTENT_ENCODED_DESCRIPTION_FALLBACK");
                } else if(content!=null&&selected==content&&(body.isEmpty()||"None".equals(body))) {
                    String reason=body.isEmpty()?"EMPTY_CONTENT_ENCODED_DESCRIPTION_FALLBACK":"NONE_CONTENT_ENCODED_DESCRIPTION_FALLBACK";
                    String summary=description==null?"":html(value(description),item.base,scope);
                    if("None".equals(summary))summary="";
                    text=fallbackResult(summary,title,summary.isEmpty()?reason+"_EMPTY":reason);
                } else text="None".equals(body)?result("",title,"unknown","DESCRIPTION_PLACEHOLDER_NONE","rss_inline"):
                    new ExtractionResult(body,title,SemanticTextNormalizer.displayTitle(title),body.isEmpty()?"headline_only":"unknown",
                            selected==content?"CONTENT_COMPLETENESS_UNKNOWN":"DESCRIPTION_COMPLETENESS_UNKNOWN","rss_inline",
                            article!=null&&article.cleaned?RssArticleText.REVISION:"feed-inline-v1");
            } catch(SourceFailure unsupported) {
                if(unsupported.reason.equals("CANCELLED")||unsupported.reason.equals("TIMEOUT"))throw unsupported;
                text=fallback?fallbackResult("",title,"DESCRIPTION_FALLBACK_REJECTED_"+unsupported.reason):error(title,unsupported.reason);
            }
        }
        return new FeedItem(provider(id),url,SemanticTextNormalizer.displayTitle(title),raw,null,date(raw),null,text);
    }
    private static boolean oversizedEncodedFallback(X item,X encoded,X description,String checkedUrl) {
        if(encoded==null||description==null||checkedUrl==null||!encoded.textLarge||encoded.attributeLarge||
                !encoded.children.isEmpty()||!description.children.isEmpty()||large(description)||value(description).trim().isEmpty())return false;
        int encodedCount=0,descriptionCount=0;
        for(X child:item.children) {
            if(child.ns.equals(CONTENT)&&child.name.equals("encoded"))encodedCount++;
            if(child.ns.isEmpty()&&child.name.equals("description"))descriptionCount++;
        }
        return encodedCount==1&&descriptionCount==1&&!largeExcept(item,encoded);
    }
    private static boolean largeExcept(X node,X rejectedField) {
        if(node==rejectedField)return false;
        if(node.large)return true;
        for(X child:node.children)if(largeExcept(child,rejectedField))return true;
        return false;
    }
    private static ExtractionResult fallbackResult(String body,String title,String reason) {
        return new ExtractionResult(body,title,SemanticTextNormalizer.displayTitle(title),body.isEmpty()?"unavailable":"summary",
            reason,"rss_description_fallback","rss-description-fallback-v1");
    }
    private static FeedItem atom(X item,RequestScope scope)throws SourceFailure {
        String title;String titleError=null;try{title=construct(item.child(ATOM,"title"),scope);}catch(SourceFailure failure){if(!itemConversionFailure(failure))throw failure;title=SemanticTextNormalizer.normalize(value(item.child(ATOM,"title")));titleError=failure.reason;}
        String id=value(item.child(ATOM,"id")),published=value(item.child(ATOM,"published")),updated=value(item.child(ATOM,"updated"));String url=null;
        for(X link:item.children)if(link.ns.equals(ATOM)&&link.name.equals("link")&&(link.attr("","rel").isEmpty()||link.attr("","rel").equals("alternate"))&&(link.attr("","type").isEmpty()||link.attr("","type").equals("text/html"))){url=safeHref(link);if(url!=null)break;}
        X content=item.child(ATOM,"content"),summary=item.child(ATOM,"summary");ExtractionResult text;
        if(large(item)||title.length()>SourceLimits.MAX_PROVIDER_TITLE||id!=null&&id.length()>SourceLimits.MAX_PROVIDER_ID||titleError!=null)text=error(title,titleError!=null?titleError:"ITEM_LIMIT");
        else {
            try {
                String body="",quality="unknown",reason="INLINE_COMPLETENESS_UNKNOWN";
                if(content!=null) {
                    String kind=content.attr("","type");
                    if(!content.attr("","src").isEmpty()||!kind.isEmpty()&&!Set.of("text","html","xhtml").contains(kind)){reason="UNSUPPORTED_ATOM_CONTENT";if(summary!=null){body=construct(summary,scope);quality="summary";}}
                    else try{body=construct(content,scope);}catch(SourceFailure unsupported){if(!itemConversionFailure(unsupported))throw unsupported;if(summary!=null){body=construct(summary,scope);quality="summary";reason=unsupported.reason;}else reason=unsupported.reason;}
                } else if(summary!=null){body=construct(summary,scope);quality="summary";reason="ATOM_SUMMARY";}
                text=result(body,title,quality,reason,"atom_inline");
            } catch(SourceFailure unsupported) {
                if(!itemConversionFailure(unsupported))throw unsupported;
                text=error(title,unsupported.reason);
            }
        }
        return new FeedItem(provider(id),url,SemanticTextNormalizer.displayTitle(title),published,updated,date(published),date(updated),text);
    }
    private static boolean itemConversionFailure(SourceFailure failure) {
        return Set.of("UNSUPPORTED_TEXT_CONSTRUCT","UNSUPPORTED_XHTML","ITEM_LIMIT","TEXT_LIMIT","DOM_LIMIT","HTML_TOKEN_LIMIT").contains(failure.reason);
    }
    private static String construct(X node,RequestScope scope)throws SourceFailure {
        scope.check();if(node==null)return "";if(large(node))throw new SourceFailure("ITEM_LIMIT");String type=node.attr("","type");
        if(type.isEmpty()||type.equals("text")){if(!node.children.isEmpty())throw new SourceFailure("UNSUPPORTED_TEXT_CONSTRUCT");return SemanticTextNormalizer.normalize(node.text.toString());}
        if(type.equals("html")){if(!node.children.isEmpty())throw new SourceFailure("UNSUPPORTED_TEXT_CONSTRUCT");return html(node.text.toString(),node.base,scope);}
        if(type.equals("xhtml")) {
            if(node.children.size()!=1||!node.children.get(0).name.equals("div")||!node.children.get(0).ns.equals(XHTML)||!node.text.toString().trim().isEmpty())throw new SourceFailure("UNSUPPORTED_XHTML");
            StringBuilder markup=new StringBuilder();serialize(node.children.get(0),markup);return html(markup.toString(),node.base,scope);
        }
        throw new SourceFailure("UNSUPPORTED_TEXT_CONSTRUCT");
    }
    private static void serialize(X node,StringBuilder out)throws SourceFailure {
        if(!node.ns.equals(XHTML))throw new SourceFailure("UNSUPPORTED_XHTML");out.append('<').append(node.name).append('>');for(Object part:node.content){if(part instanceof X)serialize((X)part,out);else out.append(escape((String)part));}if(!Set.of("br","img","hr","input","meta","link").contains(node.name))out.append("</").append(node.name).append('>');
    }
    private static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    private static String html(String value,String base,RequestScope scope)throws SourceFailure {return SemanticTextNormalizer.fromDom(DomBudgetGuard.parse(value,base,scope).document.body(),scope);}
    private static String value(X node){return node==null?null:node.value();}
    private static String safeLink(X node){if(node==null||node.value().trim().isEmpty())return null;try{return FeedDiscovery.resolve(node.base,node.value().trim());}catch(SourceFailure invalid){return null;}}
    private static String safeHref(X node){try{return node.attr("","href").isEmpty()?null:FeedDiscovery.resolve(node.base,node.attr("","href"));}catch(SourceFailure invalid){return null;}}
    private static String provider(String id){return id==null||id.isEmpty()?null:id.length()>SourceLimits.MAX_PROVIDER_ID?"unsupported:"+SemanticTextNormalizer.hash(id):id;}
    private static boolean large(X node){if(node.large)return true;for(X child:node.children)if(large(child))return true;return false;}
    private static ExtractionResult error(String title,String reason){return new ExtractionResult("",title,SemanticTextNormalizer.displayTitle(title),"unavailable",reason,"feed_inline","feed-inline-v1");}
    private static ExtractionResult result(String body,String title,String quality,String reason,String method){return new ExtractionResult(body,title,SemanticTextNormalizer.displayTitle(title),body.isEmpty()?"headline_only":quality,reason,method,"feed-inline-v1");}
    private static Long date(String raw){if(raw==null||raw.isEmpty())return null;try{return OffsetDateTime.parse(raw).toInstant().toEpochMilli();}catch(java.time.DateTimeException invalid){try{return ZonedDateTime.parse(raw,DateTimeFormatter.RFC_1123_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)).toInstant().toEpochMilli();}catch(java.time.DateTimeException failure){return null;}}}
}
