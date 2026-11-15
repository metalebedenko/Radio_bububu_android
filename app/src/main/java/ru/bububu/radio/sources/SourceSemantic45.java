package ru.bububu.radio.sources;

import org.jsoup.nodes.*;
import org.jsoup.parser.Parser;
import java.util.*;
import java.util.regex.Pattern;

/** Source-only semantic serializer; substantive footer/table/list/caption text is retained. */
public final class SourceSemantic45 {
    // Unicode White_Space, exactly the JVM (?U)\s set, without an Android-unsupported flag.
    private static final Pattern WHITE_SPACE=Pattern.compile("[\\u0009-\\u000D\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+");
    private static final Set<String> BLOCK=Set.of("p","div","section","article","h1","h2","h3","h4","h5","h6","ul","ol","li","blockquote","pre","table","tr","figure","figcaption","dl","dt","dd");
    private static final Set<String> SKIP=Set.of("script","style","noscript","svg","iframe","form","button","audio","video");
    private SourceSemantic45(){}
    private static final class Visit{final Node node;final boolean end;Visit(Node n,boolean end){node=n;this.end=end;}}
    public static String plain(Node root,RequestScope scope)throws SourceFailure{
        StringBuilder out=new StringBuilder();ArrayDeque<Visit> stack=new ArrayDeque<>();stack.push(new Visit(root,false));int work=0;
        while(!stack.isEmpty()){
            if((++work&127)==0&&scope!=null)scope.check();if(work>200000)throw new SourceFailure("DOM_LIMIT");Visit v=stack.pop();Node n=v.node;
            if(n instanceof TextNode){if(!v.end)out.append(WHITE_SPACE.matcher(((TextNode)n).getWholeText()).replaceAll(" "));continue;}
            String tag=n instanceof Element?((Element)n).normalName():"";if(SKIP.contains(tag))continue;
            if(v.end){if(BLOCK.contains(tag))out.append("\n\n");continue;}
            if(BLOCK.contains(tag))out.append("\n\n");if(tag.equals("br"))out.append('\n');if(tag.equals("li"))out.append("- ");
            if(out.length()>1000000)throw new SourceFailure("SOURCE_MARKUP_LIMIT");stack.push(new Visit(n,true));for(int i=n.childNodeSize()-1;i>=0;i--)stack.push(new Visit(n.childNode(i),false));
        }
        String text=out.toString().replace('\u00a0',' ').replaceAll("[ \\t]+"," ").replaceAll(" *\\n *","\n").replaceAll("\\n{3,}","\n\n").strip();
        if(text.length()>SourceLimits.MAX_SOURCE_SPOKEN_UTF16)throw new SourceFailure("TEXT_LIMIT");return text;
    }
    public static String fragment(String html,RequestScope scope)throws SourceFailure{return plain(DomBudgetGuard.parse(html,"https://invalid.invalid/",scope).document,scope);}
    public static String jsonBody(String body)throws SourceFailure{
        String decoded=Parser.unescapeEntities(body,false);List<String> lines=new ArrayList<>();for(String line:decoded.split("\\R")){String s=trim(line.replaceAll("[ \\t\\r\\f\\u000b]+"," "));if(!s.isEmpty())lines.add(s);}String text=String.join("\n",lines);
        if(text.contains("<p")||text.contains("<div"))throw new SourceFailure("JSONLD_BODY_HTML_UNSUPPORTED");if(text.length()>SourceLimits.MAX_SOURCE_SPOKEN_UTF16)throw new SourceFailure("TEXT_LIMIT");return text;
    }
    public static String trim(String s){int a=0,b=s.length();while(a<b&&space(s.charAt(a)))a++;while(b>a&&space(s.charAt(b-1)))b--;return s.substring(a,b);}
    private static boolean space(char c){return Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0x85;}
    public static String fold(String s){return WHITE_SPACE.matcher(s).replaceAll("");}
    /** Ordered anchor spans refer to the immutable captured HTML's UTF16 coordinates. */
    public static String linkDigest(Node root,RequestScope scope)throws SourceFailure{
        List<String> fields=new ArrayList<>();fields.add("source45-ordered-capture-html-utf16-anchors-v1");ArrayDeque<Node> nodes=new ArrayDeque<>();nodes.push(root);int ordinal=0;
        while(!nodes.isEmpty()){Node n=nodes.pop();if(n instanceof Element){Element e=(Element)n;if(SKIP.contains(e.normalName()))continue;if(e.normalName().equals("a")&&e.hasAttr("href")){fields.add(Integer.toString(ordinal++));fields.add(e.attr("href"));fields.add(plain(e,scope));fields.add(e.sourceRange().toString());fields.add(e.endSourceRange().toString());}}for(int i=n.childNodeSize()-1;i>=0;i--)nodes.push(n.childNode(i));}
        return SourceLimits.snapshotVersion(fields.toArray(new String[0]));
    }
}
