package ru.bububu.radio.sources;

import org.jsoup.nodes.*;
import org.jsoup.parser.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;

public final class DomBudgetGuard {
    private static final int ERROR_BUDGET=100;
    private static final Set<String> VOID=Set.of("br","hr","img","meta","link","input","wbr","source","track","area","base","embed","param","col");
    public static final class Parsed {
        public final Document document;
        public final String source;
        private final List<ParseError> errors;
        Parsed(Document document,String source,List<ParseError> errors){this.document=document;this.source=source;this.errors=errors;}
        public boolean hasRepairWithin(Element root) {
            if(root==null||errors.size()>=ERROR_BUDGET||!explicit(root))return true;
            int begin=root.sourceRange().startPos(),end=root.endSourceRange().endPos();
            for(ParseError error:errors)if(error.getPosition()>=begin&&error.getPosition()<=end)return true;
            ArrayDeque<Node> pending=new ArrayDeque<>();pending.add(root);
            while(!pending.isEmpty()){Node node=pending.removeLast();if(node instanceof Element&&!explicit((Element)node))return true;for(Node child:node.childNodes())pending.add(child);}
            return false;
        }
    }
    private DomBudgetGuard() { }
    public static Parsed parse(SourceTransport.Response response,RequestScope scope)throws SourceFailure {
        if(response.bytes.length>SourceLimits.HTML_BYTES)throw new SourceFailure("HTML_BYTES_LIMIT");
        return parse(decode(response.bytes,response.charset),response.finalUrl,scope);
    }
    public static Parsed parse(String html,String base,RequestScope scope)throws SourceFailure {
        if(scope!=null)scope.check();
        if(html.length()>SourceLimits.HTML_BYTES)throw new SourceFailure("HTML_BYTES_LIMIT");int marks=0;
        for(int i=0;i<html.length();i++){if(html.charAt(i)=='<'&&++marks>50_000)throw new SourceFailure("HTML_TOKEN_LIMIT");if((i&4095)==0&&scope!=null)scope.check();}
        Parser parser=Parser.htmlParser().setTrackErrors(ERROR_BUDGET).setTrackPosition(true).setMaxDepth(html.length()+1);
        Document document=parser.parseInput(html,base);
        ArrayDeque<NodeDepth> pending=new ArrayDeque<>();pending.push(new NodeDepth(document,0));int nodes=0;
        while(!pending.isEmpty()) {
            NodeDepth next=pending.pop();if(++nodes>100_000||next.depth>128)throw new SourceFailure("DOM_LIMIT");
            if((nodes&127)==0&&scope!=null)scope.check();
            for(Node child:next.node.childNodes())pending.push(new NodeDepth(child,next.depth+1));
        }
        if(scope!=null)scope.check();return new Parsed(document,html,new ArrayList<>(parser.getErrors()));
    }
    private static final class NodeDepth {final Node node;final int depth;NodeDepth(Node node,int depth){this.node=node;this.depth=depth;}}
    private static boolean explicit(Element e) {
        Range start=e.sourceRange();if(!start.isTracked()||start.isImplicit())return false;
        if(VOID.contains(e.normalName()))return true;
        Range end=e.endSourceRange();return end.isTracked()&&!end.isImplicit()&&end.startPos()>=start.endPos();
    }
    static String decode(byte[] bytes,String declared)throws SourceFailure {
        try {
            int offset=0;String bom=null;
            if(bytes.length>=3&&(bytes[0]&255)==239&&(bytes[1]&255)==187&&(bytes[2]&255)==191){bom="UTF-8";offset=3;}
            else if(bytes.length>=2&&(bytes[0]&255)==255&&(bytes[1]&255)==254){bom="UTF-16LE";offset=2;}
            else if(bytes.length>=2&&(bytes[0]&255)==254&&(bytes[1]&255)==255){bom="UTF-16BE";offset=2;}
            String meta=null;
            String header=new String(bytes,0,Math.min(bytes.length,8192),StandardCharsets.ISO_8859_1);
            Matcher m=Pattern.compile("(?i)<meta\\s+[^>]*charset\\s*=\\s*[\"']?([A-Za-z0-9._-]+)").matcher(header);
            if(m.find())meta=m.group(1);
            Charset charset=Charset.forName(declared!=null?declared:bom!=null?bom:meta!=null?meta:"UTF-8");
            if(bom!=null&&!equivalent(charset,Charset.forName(bom)) || declared!=null&&meta!=null&&!equivalent(charset,Charset.forName(meta)))throw new SourceFailure("CHARSET_CONFLICT");
            if(bom!=null&&charset.name().equalsIgnoreCase("UTF-16"))charset=Charset.forName(bom);
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,offset,bytes.length-offset)).toString();
        }catch(IllegalArgumentException|CharacterCodingException invalid){throw new SourceFailure("CHARSET_ERROR",invalid);}
    }
    static boolean equivalent(Charset a,Charset b){return a.equals(b)||a.name().equals("UTF-16")&&b.name().startsWith("UTF-16")||b.name().equals("UTF-16")&&a.name().startsWith("UTF-16");}
}
