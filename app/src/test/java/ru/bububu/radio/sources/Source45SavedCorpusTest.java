package ru.bububu.radio.sources;

import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.junit.Assume;
import static org.junit.Assert.*;

/** Private accepted captures are opt-in inputs, never checked into source or fetched. */
public class Source45SavedCorpusTest {
    static final class Corpus {
        final Map<String,Map<String,Object>> cases=new LinkedHashMap<>(),windows=new LinkedHashMap<>();
        Corpus()throws Exception{
            String root=System.getenv("BUBUBU_CORPUS45"),proof=System.getenv("BUBUBU_DISCOVERY45");Assume.assumeTrue("private45 corpus supplied explicitly",root!=null&&proof!=null);
            Map<String,Object> packet=object(pinned(Path.of(root,"packet-03.json"),"9dc2de2213886f0a559ea15d5718dfef8492a3b8a5cdf35518dbc6065ef51f95"));
            Map<String,Object> fix=object(pinned(Path.of(root,"packet-03-fix1.json"),"e4ab7341269ce338aabef26f4d5788c7deb77b50ddcf31814c3efc06f6314dd5"));
            for(Object c:BoundedJson45.array(packet.get("cases"))){Map<String,Object> m=BoundedJson45.object(c);cases.put(str(m,"case_id"),m);}for(Object c:BoundedJson45.array(fix.get("cases"))){Map<String,Object> m=BoundedJson45.object(c);assertTrue(cases.containsKey(str(m,"case_id")));cases.put(str(m,"case_id"),m);}
            Map<String,Object> discovery=object(pinned(Path.of(proof),"8294f0a52f7893d48d48d93d2e5682c6df49be50362d68b841f0e9cb78a445e7"));for(Object c:BoundedJson45.array(discovery.get("channels"))){Map<String,Object> m=BoundedJson45.object(c);windows.put(str(m,"channel_id"),m);}
            assertEquals(66,cases.size());assertEquals(22,windows.size());
        }
        FeedDocument feed(SourceProfiles45.Profile p)throws Exception{Map<String,Object> w=windows.get(p.id);byte[] data=pinned(Path.of(str(w,"saved_path")),str(w,"saved_sha256"));String mime=p.route.equals("J")?"application/json":p.route.equals("R")&&!p.id.equals("profile-news-briefs")?"text/xml":"text/html";return SourceReaders45.discover(p,new SourceTransport.Response(data,p.endpoint,200,mime,"UTF-8",null,null),new RequestScope(1,1));}
    }
    static String str(Map<String,Object> m,String key)throws SourceFailure{return BoundedJson45.text(m.get(key));}
    static Map<String,Object> object(byte[] bytes)throws SourceFailure{return BoundedJson45.object(BoundedJson45.parse(bytes));}
    static byte[] pinned(Path path,String sha)throws Exception{byte[] bytes=Files.readAllBytes(path);assertEquals("saved input SHA: "+path.getFileName(),sha,SourceLimits.hash(bytes));return bytes;}
    static String failure(Throwable t){return t instanceof SourceFailure?((SourceFailure)t).reason:t.getClass().getSimpleName()+":"+t.getMessage();}
    static void actual(String id,String text)throws Exception{String job=System.getenv("BUBUBU_JOB45");if(job==null)return;if(!id.matches("[a-z0-9_-]+"))throw new IllegalArgumentException("case id");Path dir=Path.of(job,"reader-actual");Files.createDirectories(dir);Files.write(dir.resolve(id+".txt"),text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    @Test public void accepted22DiscoveryWindowsContainAll66MappedCases()throws Exception{
        Corpus c=new Corpus();List<String> errors=new ArrayList<>();int passed=0;for(SourceProfiles45.Profile p:SourceProfiles45.all())try{
            FeedDocument feed=c.feed(p);Map<String,Object> w=c.windows.get(p.id);for(Object v:BoundedJson45.array(w.get("matched_cases"))){Map<String,Object> item=BoundedJson45.object(v);String url=str(item,"discovery_url");long count=feed.items.stream().filter(x->url.equals(x.url)).count();if(count!=1)throw new AssertionError(str(item,"case_id")+": candidates="+count);passed++;}
        }catch(Exception|AssertionError bad){errors.add(p.id+":"+failure(bad));}
        assertEquals("discovery errors="+errors,66,passed);assertTrue(errors.toString(),errors.isEmpty());
    }
    @Test public void accepted66BodiesAndPublicationContractsHaveExactSavedParity()throws Exception{
        Corpus c=new Corpus();Map<String,FeedDocument> feeds=new LinkedHashMap<>();List<String> errors=new ArrayList<>();int passed=0;
        for(Map<String,Object> row:c.cases.values()){
            String id=str(row,"case_id"),cid=str(row,"channel_id");SourceProfiles45.Profile p=SourceProfiles45.byId(cid);
            try{
                FeedDocument feed=feeds.get(cid);if(feed==null){feed=c.feed(p);feeds.put(cid,feed);}String target=str(row,"discovery_url");FeedItem item=null;for(FeedItem i:feed.items)if(target.equals(i.url)){item=i;break;}if(item==null)throw new AssertionError("case missing from bounded discovery");
                byte[] raw=pinned(Path.of(str(row,"source_html_path")),str(row,"source_html_sha256"));String mime=str(row,"mime").split(";",2)[0].strip();SourceTransport.Response response=new SourceTransport.Response(raw,str(row,"url"),200,mime,str(row,"charset"),null,null);
                ExtractionResult value=SourceReaders45.extract(p,response,item,new RequestScope(1,1));actual(id,value.body);
                byte[] expected=pinned(Path.of(str(row,"text_path")),str(row,"text_sha256"));String expectedText=new String(expected,java.nio.charset.StandardCharsets.UTF_8);if(!expectedText.equals(value.body)){int first=0;while(first<Math.min(expectedText.length(),value.body.length())&&expectedText.charAt(first)==value.body.charAt(first))first++;throw new AssertionError("body SHA mismatch actual="+value.bodyHash+" expected="+str(row,"text_sha256")+" chars="+value.body.length()+"/"+expectedText.length()+" firstUTF16="+first);}
                assertEquals("full",value.completeness);assertNotNull(value.publication);SourcePublication45.Fact primary=value.publication.primary;
                if(cid.equals("biomolecula-articles"))assertEquals(SourcePublication45.Precision.DAY,primary.precision);
                if(cid.equals("vokrugsveta-stories")&&id.endsWith("01")){assertEquals(SourcePublication45.Precision.MONTH,primary.precision);assertEquals("2011-02",primary.value);assertNull(primary.epochMillis);assertTrue(value.publication.facts.stream().anyMatch(f->f.role==SourcePublication45.Role.REPUBLISHED));}
                if(cid.equals("forbes-open-articles")){assertEquals("microdata",primary.authority);assertEquals("article time[itemprop=datePublished]@datetime",primary.selector);assertEquals(str(row,"published_raw"),primary.raw);}
                if(cid.equals("if24-interviews")){assertEquals("wp-api",primary.authority);assertEquals(java.time.Instant.parse(str(row,"published_raw")).toEpochMilli(),primary.epochMillis.longValue());assertEquals("API_CANONICAL_DIFFER",value.publication.conflict);}
                if(cid.equals("techinsider-briefs")){assertEquals("jsonld",primary.authority);assertTrue(primary.raw.contains("T"));}
                passed++;
            }catch(Exception|AssertionError bad){errors.add(id+":"+failure(bad));}
        }
        String job=System.getenv("BUBUBU_JOB45");if(job!=null)Files.write(Path.of(job,"logs","reader-parity-summary.txt"),("matched="+passed+"/66\n"+String.join("\n",errors)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("private body/date parity errors="+errors,66,passed);assertTrue(errors.toString(),errors.isEmpty());
    }
}
