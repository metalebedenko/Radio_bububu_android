package ru.bububu.radio.sources;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Set;

/** One versioned representation for reader, hashes and spoken snapshots. */
public final class SemanticTextNormalizer {
    public static final String VERSION = "semantic-v1";
    private static final Set<String> EXCLUDED = Set.of("script","style","nav","button","noscript","template","svg","iframe","footer");
    private static final Set<String> BLOCKS = Set.of("p","div","section","article","blockquote","ul","ol","li","h1","h2","h3","h4","h5","h6","pre");
    private SemanticTextNormalizer() { }
    public static String normalize(String value) {
        if (value == null) return "";
        String[] lines = value.replace("\r\n", "\n").replace('\r','\n').replace('\u00a0',' ').split("\n",-1);
        StringBuilder out=new StringBuilder();
        for (String line:lines) { if(out.length()>0)out.append('\n'); out.append(line.replaceAll("[\\p{Zs}\\t\\f\\x0B]+", " ").trim()); }
        return out.toString().replaceAll("\n{3,}","\n\n").trim();
    }
    public static String displayTitle(String value) {
        String title=normalize(value).replace('\n',' ');
        if(title.length()<=SourceLimits.MAX_DISPLAY_TITLE)return title;
        int end=SourceLimits.MAX_DISPLAY_TITLE;
        if(Character.isHighSurrogate(title.charAt(end-1)))end--;
        return title.substring(0,end);
    }
    private static final class Visit {
        final Node node; final boolean end;
        Visit(Node node,boolean end){this.node=node;this.end=end;}
    }
    public static String fromDom(Node root, RequestScope scope) throws SourceFailure {
        ArrayDeque<Visit> stack=new ArrayDeque<>(); stack.push(new Visit(root,false));
        StringBuilder out=new StringBuilder(); int count=0;
        while(!stack.isEmpty()) {
            if((++count&127)==0 && scope!=null)scope.check();
            Visit visit=stack.pop(); Node node=visit.node;
            if(node instanceof TextNode) { if(!visit.end)out.append(((TextNode)node).getWholeText()); continue; }
            String tag=node instanceof Element?((Element)node).normalName():"";
            if(EXCLUDED.contains(tag))continue;
            if(visit.end){if(BLOCKS.contains(tag))out.append("\n\n");continue;}
            if(tag.equals("br")){out.append('\n');continue;}
            if(BLOCKS.contains(tag))out.append("\n\n");
            stack.push(new Visit(node,true));
            for(int i=node.childNodeSize()-1;i>=0;i--)stack.push(new Visit(node.childNode(i),false));
        }
        String text=normalize(out.toString());
        if(text.length()>SourceLimits.MAX_TEXT)throw new SourceFailure("TEXT_LIMIT");
        if(scope!=null)scope.check(); return text;
    }
    public static String hash(String value) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result=new StringBuilder(64); for(byte b:bytes)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return result.toString();
        } catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
