package ru.bububu.radio.sources;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Closed-source JSON decoder: strict UTF-8, duplicate-key refusal and bounded work. */
public final class BoundedJson45 {
    public static final int MAX_TOKENS=100000;
    private final String input; private int at,tokens;
    private BoundedJson45(String text){input=text;}
    public static Object parse(byte[] bytes)throws SourceFailure{
        if(bytes.length>SourceLimits.JSON_BYTES)throw new SourceFailure("JSON_BYTE_LIMIT");
        try{return parse(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());}
        catch(CharacterCodingException bad){throw new SourceFailure("JSON_CHARSET");}
    }
    public static Object parse(String text)throws SourceFailure{
        if(text==null||text.length()>SourceLimits.JSON_BYTES)throw new SourceFailure("JSON_BYTE_LIMIT");
        BoundedJson45 p=new BoundedJson45(text);if(!text.isEmpty()&&text.charAt(0)=='\ufeff')p.at++;
        Object result=p.value(0);p.space();if(p.at!=text.length())throw p.bad();return result;
    }
    private SourceFailure bad(){return new SourceFailure("JSON_INVALID_OR_LIMIT");}
    private void space(){while(at<input.length()&&" \r\n\t".indexOf(input.charAt(at))>=0)at++;}
    private boolean eat(char c){space();if(at<input.length()&&input.charAt(at)==c){at++;return true;}return false;}
    private Object value(int depth)throws SourceFailure{
        if(depth>64||++tokens>MAX_TOKENS)throw bad();space();if(at>=input.length())throw bad();char c=input.charAt(at);
        if(c=='"')return string();
        if(c=='{'){at++;Map<String,Object> map=new LinkedHashMap<>();if(eat('}'))return map;do{space();if(++tokens>MAX_TOKENS||at>=input.length()||input.charAt(at)!='"')throw bad();String key=string();if(map.containsKey(key)||!eat(':'))throw bad();map.put(key,value(depth+1));}while(eat(','));if(!eat('}'))throw bad();return map;}
        if(c=='['){at++;List<Object> list=new ArrayList<>();if(eat(']'))return list;do{list.add(value(depth+1));}while(eat(','));if(!eat(']'))throw bad();return list;}
        for(String literal:new String[]{"true","false","null"})if(input.startsWith(literal,at)){at+=literal.length();return literal.equals("null")?null:literal.equals("true");}
        int start=at;if(c=='-')at++;if(at>=input.length())throw bad();if(input.charAt(at)=='0')at++;else{if(input.charAt(at)<'1'||input.charAt(at)>'9')throw bad();while(at<input.length()&&digit(input.charAt(at)))at++;}
        if(at<input.length()&&input.charAt(at)=='.'){at++;int pos=at;while(at<input.length()&&digit(input.charAt(at)))at++;if(pos==at)throw bad();}
        if(at<input.length()&&(input.charAt(at)=='e'||input.charAt(at)=='E')){at++;if(at<input.length()&&(input.charAt(at)=='+'||input.charAt(at)=='-'))at++;int pos=at;while(at<input.length()&&digit(input.charAt(at)))at++;if(pos==at)throw bad();}
        if(at-start>128)throw bad();try{return new BigDecimal(input.substring(start,at));}catch(NumberFormatException bad){throw bad();}
    }
    private static boolean digit(char c){return c>='0'&&c<='9';}
    private String string()throws SourceFailure{
        at++;StringBuilder out=new StringBuilder();boolean closed=false;
        while(at<input.length()){
            char c=input.charAt(at++);if(c=='"'){closed=true;break;}if(c<32)throw bad();
            if(c=='\\'){if(at>=input.length())throw bad();c=input.charAt(at++);switch(c){case '"':case '\\':case '/':break;case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;case 'u':if(at+4>input.length())throw bad();try{c=(char)Integer.parseInt(input.substring(at,at+4),16);}catch(NumberFormatException e){throw bad();}at+=4;break;default:throw bad();}}
            out.append(c);if(out.length()>1000000)throw bad();
        }
        if(!closed)throw bad();for(int i=0;i<out.length();i++){char c=out.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=out.length()||!Character.isLowSurrogate(out.charAt(i)))throw bad();}else if(Character.isLowSurrogate(c))throw bad();}return out.toString();
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object value)throws SourceFailure{if(!(value instanceof Map))throw new SourceFailure("JSON_OBJECT_REQUIRED");return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") public static List<Object> array(Object value)throws SourceFailure{if(!(value instanceof List))throw new SourceFailure("JSON_ARRAY_REQUIRED");return (List<Object>)value;}
    public static String text(Object value)throws SourceFailure{if(!(value instanceof String))throw new SourceFailure("JSON_STRING_REQUIRED");return (String)value;}
    public static String optional(Map<String,Object> object,String key)throws SourceFailure{return object.get(key)==null?null:text(object.get(key));}
}
