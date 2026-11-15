package ru.bububu.radio.sources;

import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.Assume;
import ru.bububu.radio.core.*;
import static org.junit.Assert.*;

public class Source45SpeechPlanTest {
    @Test public void wholeNinePrivateBodiesCoverEveryCanonicalSentenceOrReportExplicitUnsupported()throws Exception{
        wholeNine(SourceSpeechPlan45::boundedSpoken,"whole-nine-speech-coverage.tsv");
    }
    public static void wholeNine(java.util.function.Consumer<String> frontend,String outputName)throws Exception{
        wholeBodies(frontend,outputName,Set.of("biomolecula-articles","elementy-science-explainers","poisknews-biographies"),9);
    }
    public static void wholeBodies(java.util.function.Consumer<String> frontend,String outputName,Set<String> selected,int expectedBodies)throws Exception{
        wholeBodies(frontend,outputName,selected,expectedBodies,false);
    }
    public static void wholeBodies(java.util.function.Consumer<String> frontend,String outputName,Set<String> selected,int expectedBodies,boolean publisherHeaders)throws Exception{
        Source45SavedCorpusTest.Corpus corpus=new Source45SavedCorpusTest.Corpus();
        List<String> output=new ArrayList<>();int bodies=0,total=0,passed=0,unsupported=0;
        for(Map<String,Object> row:corpus.cases.values()){
            String cid=Source45SavedCorpusTest.str(row,"channel_id");
            if(!selected.contains(cid))continue;
            String id=Source45SavedCorpusTest.str(row,"case_id");SourceProfiles45.Profile profile=SourceProfiles45.byId(cid);
            FeedItem item=corpus.feed(profile).items.stream().filter(x->x.url.equals(row.get("discovery_url"))).findFirst().get();
            byte[] capture=Source45SavedCorpusTest.pinned(Path.of(Source45SavedCorpusTest.str(row,"source_html_path")),Source45SavedCorpusTest.str(row,"source_html_sha256"));
            SourceTransport.Response response=new SourceTransport.Response(capture,Source45SavedCorpusTest.str(row,"url"),200,Source45SavedCorpusTest.str(row,"mime").split(";",2)[0].trim(),Source45SavedCorpusTest.str(row,"charset"),null,null);
            ExtractionResult extracted=SourceReaders45.extract(profile,response,item,new RequestScope(1,1));
            assertEquals(Source45SavedCorpusTest.str(row,"text_sha256"),extracted.bodyHash);
            SourceVersionRecord version;
            if(publisherHeaders){SourceRecord source=profile.seed();SourceItem sourceItem=SourceItem.create(source.id,id,item.providerId,item.url,item.title,item.publishedAtMillis==null?-1:item.publishedAtMillis,item.updatedAtMillis==null?-1:item.updatedAtMillis,1,item.publishedRaw,item.updatedRaw);version=SourceVersionRecord.source45(source,sourceItem,extracted,extracted.publication,SourcePublication45.Context.DATED,1);}
            else version=Source45PublicationTest.version(extracted.body,extracted.publication,SourcePublication45.Context.DATED,ChannelProfile.Kind.ARTICLE_ANALYSIS);
            Article article=version.asArticle();int good=0,bad=0,goodChars=0,badChars=0,partsCount=0;long checks=0;
            output.add("BODY\t"+id+"\t"+extracted.bodyHash+"\tcanonicalSHA="+SourceLimits.sha256(article.text)+"\tversion="+version.versionKey+"\tUTF16="+article.text.length());
            for(int index=0;index<article.sentences.size();index++){
                Article.Sentence canonical=article.sentences.get(index);PlayerController.SpeechSentence sentence=new PlayerController.SpeechSentence("whole-nine",article,null,index);total++;
                try{
                    SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.sentence(sentence);SourceSpeechPlan45.Parts parts=plan.parts(512,frontend);
                    assertEquals(parts.raw,TextSegments.checkedFragments(canonical.text,parts.raw,512));checks+=parts.candidateChecks;
                    for(int n=0;n<parts.raw.size();n++){
                        PlayerController.SpeechUnit unit=new PlayerController.SpeechUnit(sentence,parts.raw,n);
                        assertEquals(parts.spoken.get(n),SourceSpeechPlan45.project(unit));SourceSpeechPlan45.boundedSpoken(SourceSpeechPlan45.project(unit));
                        assertEquals(SourceSpeechPlan45.hash(unit),SourceSpeechPlan45.hash(unit));assertEquals(64,SourceSpeechPlan45.hash(unit).length());
                    }
                    good++;passed++;goodChars+=canonical.text.length();partsCount+=parts.raw.size();
                }catch(IllegalArgumentException explicit){
                    String reason=String.valueOf(explicit.getMessage());
                    assertTrue("unexpected projection failure "+id+" sentence "+index+": "+reason,reason.startsWith("UNSUPPORTED")||reason.startsWith("SOURCE45_UNSUPPORTED")||reason.equals("ACCENTOR_TOKEN_BOUND")||reason.equals("NGRAM_WORD_QUADRATIC_BOUND")||reason.equals("WORDPIECE_WORD_BOUND"));
                    bad++;unsupported++;badChars+=canonical.text.length();
                    for(SourceSpeechPlan45.Atom atom:diagnosticAtoms(sentence))try{frontend.accept(atom.spoken);if(atom.spoken.length()>256||atom.end-atom.start>512)output.add("ATOM_BOUND\t"+id+"\tsentence="+index+"\traw="+canonical.text.substring(atom.start,atom.end)+"\tspokenLength="+atom.spoken.length());}catch(IllegalArgumentException rejected){output.add("ATOM_REJECTED\t"+id+"\tsentence="+index+"\tspan="+atom.start+":"+atom.end+"\traw="+canonical.text.substring(atom.start,atom.end).replace("\n"," ")+"\tspoken="+atom.spoken+"\treason="+rejected.getMessage());}
                    output.add("UNSUPPORTED\t"+id+"\tsentence="+index+"\tspan="+canonical.start+":"+canonical.end+"\trawSHA="+SourceLimits.sha256(canonical.text)+"\treason="+reason);
                }
            }
            assertEquals(article.sentences.size(),good+bad);bodies++;
            output.add("COVERAGE\t"+id+"\tsentences="+(good+bad)+"\tsupported="+good+"\tunsupported="+bad+"\tsupportedUTF16="+goodChars+"\tunsupportedUTF16="+badChars+"\tparts="+partsCount+"\tplanningCandidateChecks="+checks);
        }
        assertEquals(expectedBodies,bodies);assertEquals(total,passed+unsupported);assertTrue(total>9);
        output.add("TOTAL\tbodies="+bodies+"\tsentences="+total+"\tsupported="+passed+"\tunsupported="+unsupported+"\tnative=TTS=NOT_RUN\tlinear_claim=planning_only");
        String job=System.getenv("BUBUBU_JOB45");if(job!=null)Files.write(Path.of(job,"logs",outputName),output,StandardCharsets.UTF_8);
    }
    private static List<SourceSpeechPlan45.Atom> diagnosticAtoms(PlayerController.SpeechSentence sentence){try{return SourceSpeechPlan45.sentence(sentence).atoms;}catch(IllegalArgumentException unsupported){return List.of();}}
    @Test public void boundedCitationRangeAtomsKeepBothEndpointsAndAllSurroundingProse(){
        Map<String,String> expected=new LinkedHashMap<>();expected.put("[1–3]","примечания с одного по три");expected.put("[3–6]","примечания с трёх по шесть");expected.put("[10–14]","примечания с десяти по четырнадцать");expected.put("[30–32]","примечания с тридцати по тридцать два");expected.put("[1–1]","примечания с одного по один");
        for(Map.Entry<String,String> e:expected.entrySet()){
            String text="До ссылки"+e.getKey()+", после ссылки.";SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.compile(text);SourceSpeechPlan45.Atom atom=plan.atoms.stream().filter(a->a.rule.equals("citation-range-v1")).findFirst().get();assertEquals(e.getKey(),text.substring(atom.start,atom.end));assertEquals(e.getValue(),atom.spoken);assertEquals("До ссылки "+e.getValue()+", после ссылки.",plan.project(0,text.length()));
            for(int cut=atom.start+1;cut<atom.end;cut++){final int boundary=cut;assertThrows(IllegalArgumentException.class,()->plan.project(atom.start,boundary));}
            SourceSpeechPlan45.Parts parts=plan.parts(32,SourceSpeechPlan45::boundedSpoken);assertEquals(parts.raw,TextSegments.checkedFragments(text,parts.raw,32));assertEquals(1,parts.raw.stream().filter(s->s.contains(e.getKey())).count());
        }
        assertTrue(SourceSpeechPlan45.compile("[9999–9999]").project(0,11).length()<256);
        for(String unsupported:List.of("[3–1]","[0]","[01–3]","[1-3]","[1—3]","[1 – 3]","[1,3]","[[1–3]]","[1–10000]","x^2","\\frac{1}{2}"))assertThrows(unsupported,IllegalArgumentException.class,()->SourceSpeechPlan45.compile(unsupported));
    }
    @Test public void literalUrlPreservesEveryCharacterAndNeverProjectsPartialPathIndependently(){
        String url="https://doi.org/10.1073/pnas.2613108123.",text="До "+url+" после.";SourceSpeechPlan45.Plan p=SourceSpeechPlan45.compile(text);SourceSpeechPlan45.Atom a=p.tokens.stream().filter(x->x.rule.equals("literal-url-v1")).findFirst().get();assertEquals(url,text.substring(a.start,a.end));assertTrue(a.spoken.startsWith("эйч ти ти пи эс двоеточие слеш слеш"));assertTrue(a.spoken.contains("точка"));assertTrue(a.spoken.contains("слеш"));assertTrue(a.spoken.contains("два шесть один три один ноль восемь один два три точка"));
        assertTrue(p.project(a.start+8,a.end).contains("два шесть один три один ноль восемь один два три точка"));assertThrows(IllegalArgumentException.class,()->SourceSpeechPlan45.compile(url.substring(8)));SourceSpeechPlan45.Parts parts=p.parts(512,SourceSpeechPlan45::boundedSpoken);assertEquals(parts.raw,TextSegments.checkedFragments(text,parts.raw,512));assertTrue(parts.spoken.stream().allMatch(s->s.length()<=256));
    }
    @Test public void longSingleSentencePlannerHasBoundedLinearCandidateWorkAndExactCoverage(){
        String raw=("короткое слово ").repeat(6000);SourceSpeechPlan45.Plan p=SourceSpeechPlan45.compile(raw);SourceSpeechPlan45.Parts parts=p.parts(512,SourceSpeechPlan45::boundedSpoken);assertTrue(raw.length()>20000&&raw.length()<100000);assertEquals(parts.raw,TextSegments.checkedFragments(raw,parts.raw,512));assertTrue(parts.candidateChecks<raw.length()*4L);assertTrue(parts.raw.stream().allMatch(s->s.length()<=512));assertTrue(parts.spoken.stream().allMatch(s->s.length()<=256));
    }
    @Test public void unitProjectionHashBindsExactV2VersionAndLegacyProjectionIsUnchanged()throws Exception{
        SourceVersionRecord a=Source45PublicationTest.version("Ссылка [1–3].",Source45PublicationTest.publication("2026-10-25T10:00:00Z"),SourcePublication45.Context.DATED,ChannelProfile.Kind.ARTICLE_ANALYSIS),b=Source45PublicationTest.version("Ссылка [1–3].",Source45PublicationTest.publication("2026-10-25T11:00:00Z"),SourcePublication45.Context.DATED,ChannelProfile.Kind.ARTICLE_ANALYSIS);
        PlayerController.SpeechSentence sa=new PlayerController.SpeechSentence("station",a.asArticle(),null,0),sb=new PlayerController.SpeechSentence("station",b.asArticle(),null,0);List<String> fa=SourceSpeechPlan45.sentence(sa).parts(512,SourceSpeechPlan45::boundedSpoken).raw,fb=SourceSpeechPlan45.sentence(sb).parts(512,SourceSpeechPlan45::boundedSpoken).raw;PlayerController.SpeechUnit ua=new PlayerController.SpeechUnit(sa,fa,0),ub=new PlayerController.SpeechUnit(sb,fb,0);assertEquals(SourceSpeechPlan45.project(ua),SourceSpeechPlan45.project(ub));assertNotEquals(SourceSpeechPlan45.hash(ua),SourceSpeechPlan45.hash(ub));
        Article old=new Article("old","version","Тема","Текст 12%.");PlayerController.SpeechUnit legacy=new PlayerController.SpeechUnit(new PlayerController.SpeechSentence("station",old,null,0),List.of(old.text),0);assertEquals(ru.bububu.radio.models.SileroSpeechInput.project(old.text),SourceSpeechPlan45.project(legacy));assertEquals(SourceLimits.sha256(ru.bububu.radio.models.SileroSpeechInput.project(old.text)),SourceSpeechPlan45.hash(legacy));
    }
    @Test public void fourObservedBioSpansAndThreeDoiUrlsUseExactPrivateAcceptedBodies()throws Exception{
        String root=System.getenv("BUBUBU_CORPUS45");Assume.assumeTrue(root!=null);Map<String,Object> packet=Source45SavedCorpusTest.object(Source45SavedCorpusTest.pinned(Path.of(root,"packet-03.json"),"9dc2de2213886f0a559ea15d5718dfef8492a3b8a5cdf35518dbc6065ef51f95"));int ranges=0,urls=0;
        Map<String,List<int[]>> spans=Map.of("biomolecula-catalog-02",List.of(new int[]{1568,1573}),"biomolecula-catalog-03",List.of(new int[]{2772,2777},new int[]{9389,9396},new int[]{28039,28046}));
        for(Object row:BoundedJson45.array(packet.get("cases"))){Map<String,Object> c=BoundedJson45.object(row);String id=Source45SavedCorpusTest.str(c,"case_id"),channel=Source45SavedCorpusTest.str(c,"channel_id");if(!channel.equals("biomolecula-articles")&&!channel.equals("elementy-science-explainers")&&!channel.equals("poisknews-biographies"))continue;String body=new String(Source45SavedCorpusTest.pinned(Path.of(Source45SavedCorpusTest.str(c,"text_path")),Source45SavedCorpusTest.str(c,"text_sha256")),StandardCharsets.UTF_8);
            for(int[] span:spans.getOrDefault(id,List.of())){String surrounding=body.substring(Math.max(0,span[0]-64),Math.min(body.length(),span[1]+64));SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.compile(surrounding);String raw=body.substring(span[0],span[1]);assertEquals(1,plan.atoms.stream().filter(a->a.rule.equals("citation-range-v1")&&surrounding.substring(a.start,a.end).equals(raw)).count());SourceSpeechPlan45.Parts parts=plan.parts(512,SourceSpeechPlan45::boundedSpoken);assertEquals(parts.raw,TextSegments.checkedFragments(surrounding,parts.raw,512));ranges++;}
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("https?://\\S+").matcher(body);while(m.find()){String raw=m.group();SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.compile("До "+raw+" после.");SourceSpeechPlan45.Atom atom=plan.tokens.stream().filter(a->a.rule.equals("literal-url-v1")).findFirst().get();assertTrue(atom.spoken.length()<=256);SourceSpeechPlan45.Parts parts=plan.parts(512,SourceSpeechPlan45::boundedSpoken);assertEquals(parts.raw,TextSegments.checkedFragments(plan.canonical,parts.raw,512));urls++;}
        }assertEquals(4,ranges);assertEquals(3,urls);
    }
}
