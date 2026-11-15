package ru.bububu.radio.sources;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.regex.*;

public final class XmlDeclarationGuard {
    public static final class Checked { public final String text,encoding; Checked(String text,String encoding){this.text=text;this.encoding=encoding;} }
    private XmlDeclarationGuard() { }
    public static Checked check(byte[] bytes,String httpCharset,RequestScope scope)throws SourceFailure {
        if(bytes.length>SourceLimits.XML_BYTES)throw new SourceFailure("XML_BYTES_LIMIT");
        try {
            int offset=0;String bom=null,signature=null;
            if(bytes.length>=3&&(bytes[0]&255)==239&&(bytes[1]&255)==187&&(bytes[2]&255)==191){bom="UTF-8";offset=3;}
            else if(bytes.length>=2&&(bytes[0]&255)==255&&(bytes[1]&255)==254){bom="UTF-16LE";offset=2;}
            else if(bytes.length>=2&&(bytes[0]&255)==254&&(bytes[1]&255)==255){bom="UTF-16BE";offset=2;}
            else if(bytes.length>=4&&bytes[0]==0&&bytes[1]=='<'&&bytes[2]==0&&bytes[3]=='?')signature="UTF-16BE";
            else if(bytes.length>=4&&bytes[0]=='<'&&bytes[1]==0&&bytes[2]=='?'&&bytes[3]==0)signature="UTF-16LE";
            String guess=bom!=null?bom:signature!=null?signature:httpCharset!=null?httpCharset:"UTF-8";
            Charset guessed=Charset.forName(guess);
            String prefix=new String(bytes,offset,Math.min(bytes.length-offset,2048),guessed);
            Matcher declaration=Pattern.compile("^<\\?xml\\s+[^?]*encoding\\s*=\\s*(['\"])([A-Za-z][A-Za-z0-9._-]*)\\1[^?]*\\?>").matcher(prefix);
            String declared=declaration.find()?declaration.group(2):null;
            Charset chosen=Charset.forName(declared!=null?declared:guess);
            if(httpCharset!=null&&!DomBudgetGuard.equivalent(chosen,Charset.forName(httpCharset)) || bom!=null&&!DomBudgetGuard.equivalent(chosen,Charset.forName(bom)) || signature!=null&&!DomBudgetGuard.equivalent(chosen,Charset.forName(signature)))throw new SourceFailure("CHARSET_CONFLICT");
            if(chosen.name().equals("UTF-16")&&(bom!=null||signature!=null))chosen=Charset.forName(bom!=null?bom:signature);
            String text=chosen.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,offset,bytes.length-offset)).toString();
            for(int i=0;i<text.length();) {
                if((i&1023)==0&&scope!=null)scope.check();
                if(text.startsWith("<!--",i)){int end=text.indexOf("-->",i+4);if(end<0)throw new SourceFailure("XML_FORMAT");i=end+3;}
                else if(text.startsWith("<![CDATA[",i)){int end=text.indexOf("]]>",i+9);if(end<0)throw new SourceFailure("XML_FORMAT");i=end+3;}
                else if(text.startsWith("<?",i)){int end=text.indexOf("?>",i+2);if(end<0)throw new SourceFailure("XML_FORMAT");i=end+2;}
                else if(text.startsWith("<!DOCTYPE",i))throw new SourceFailure("XML_DTD");
                else i++;
            }
            if(scope!=null)scope.check();return new Checked(text,chosen.name());
        }catch(IllegalArgumentException|CharacterCodingException invalid){throw new SourceFailure("CHARSET_ERROR",invalid);}
    }
}
