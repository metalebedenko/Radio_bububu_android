package ru.bububu.radio.sources;

import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.ext.LexicalHandler;
import javax.xml.parsers.SAXParserFactory;
import java.io.StringReader;
import java.util.*;

/** Bounded RSS discovery/content parser for the closed45 profiles; no external entities. */
final class ClosedRss45 {
    static final String CONTENT="http://purl.org/rss/1.0/modules/content/";
    static final class Node {final String ns,name;final Map<String,String> attrs=new LinkedHashMap<>();final List<Node> children=new ArrayList<>();final StringBuilder text=new StringBuilder();Node(String ns,String name){this.ns=ns;this.name=name;}Node child(String ns,String name){for(Node n:children)if(n.ns.equals(ns)&&n.name.equals(name))return n;return null;}String value(String name){Node n=child("",name);return n==null?null:SourceSemantic45.trim(n.text.toString());}}
    static Node parse(SourceTransport.Response response,RequestScope scope)throws SourceFailure{
        if(response.status!=200)throw new SourceFailure("XML_HTTP_STATUS");String xml=XmlDeclarationGuard.check(response.bytes,response.charset,scope).text;
        try{
            XMLReader reader;Handler h=new Handler(scope);
            try{
                SAXParserFactory f=SAXParserFactory.newInstance();f.setNamespaceAware(true);f.setValidating(false);reader=f.newSAXParser().getXMLReader();
                reader.setFeature("http://xml.org/sax/features/namespaces",true);reader.setFeature("http://xml.org/sax/features/validation",false);
                for(String feature:List.of("http://xml.org/sax/features/external-general-entities","http://xml.org/sax/features/external-parameter-entities")){reader.setFeature(feature,false);if(reader.getFeature(feature))throw new SAXException("XML_EXTERNAL_ENTITIES_ENABLED");}
                if(!reader.getFeature("http://xml.org/sax/features/namespaces"))throw new SAXException("XML_NAMESPACES_DISABLED");
                reader.setProperty("http://xml.org/sax/properties/lexical-handler",h);reader.setEntityResolver((publicId,systemId)->{throw new SAXException("XML_EXTERNAL_ENTITY");});
            }catch(Exception setup){throw new SourceFailure("XML_PARSER_SETUP",setup);}
            reader.setContentHandler(h);reader.setErrorHandler(h);reader.parse(new InputSource(new StringReader(xml)));if(h.root==null||!h.stack.isEmpty())throw new SourceFailure("XML_FORMAT");return h.root;
        }catch(SourceFailure error){throw error;}catch(Exception invalid){Throwable cause=invalid.getCause();if(cause instanceof SourceFailure)throw (SourceFailure)cause;throw new SourceFailure("XML_FORMAT",invalid);}
    }
    private static final class Handler extends DefaultHandler implements LexicalHandler {
        final RequestScope scope;final ArrayDeque<Node> stack=new ArrayDeque<>();Node root;int nodes,attrs;
        Handler(RequestScope s){scope=s;}
        @Override public void startDTD(String name,String publicId,String systemId)throws SAXException{throw new SAXException("XML_DTD");}
        @Override public void endDTD(){} @Override public void startEntity(String name){} @Override public void endEntity(String name){} @Override public void startCDATA(){} @Override public void endCDATA(){} @Override public void comment(char[] c,int start,int length){}
        @Override public void skippedEntity(String name)throws SAXException{throw new SAXException("XML_EXTERNAL_ENTITY");}
        private void check()throws SAXException{try{if(scope!=null)scope.check();}catch(SourceFailure fail){throw new SAXException(fail);}}
        @Override public void startElement(String ns,String local,String q,Attributes a)throws SAXException{
            check();if(++nodes>100000||stack.size()>=64||a.getLength()>64||(attrs+=a.getLength())>200000)throw new SAXException("XML_LIMIT");Node n=new Node(ns,local);
            for(int i=0;i<a.getLength();i++){String value=a.getValue(i);if(value.length()>SourceLimits.MAX_TEXT)throw new SAXException("XML_ATTRIBUTE_LIMIT");n.attrs.put(a.getLocalName(i),value);}
            if(stack.isEmpty()){if(root!=null)throw new SAXException("XML_ROOT");root=n;}else stack.peek().children.add(n);stack.push(n);
        }
        @Override public void endElement(String ns,String local,String q)throws SAXException{check();if(stack.isEmpty()||!stack.peek().name.equals(local)||!stack.peek().ns.equals(ns))throw new SAXException("XML_FORMAT");stack.pop();}
        @Override public void characters(char[] c,int start,int len)throws SAXException{check();if(stack.isEmpty())return;Node n=stack.peek();int cap=n.ns.equals(CONTENT)&&n.name.equals("encoded")?1000000:SourceLimits.MAX_TEXT;if(n.text.length()>cap-len)throw new SAXException("XML_FIELD_LIMIT");n.text.append(c,start,len);}
        @Override public void error(SAXParseException error)throws SAXException{throw error;}
        @Override public void fatalError(SAXParseException error)throws SAXException{throw error;}
    }
}
