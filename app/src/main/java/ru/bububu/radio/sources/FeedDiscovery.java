package ru.bububu.radio.sources;

import org.jsoup.nodes.Element;
import java.net.URI;
import java.util.*;

public final class FeedDiscovery {
    public static final class Candidate {
        public final String title,url,mime;
        public Candidate(String title,String url,String mime){this.title=title;this.url=url;this.mime=mime;}
    }
    public List<Candidate> discover(SourceTransport.Response response,RequestScope scope)throws SourceFailure {
        return discover(response,scope,DomBudgetGuard.parse(response,scope).document);
    }
    List<Candidate> discover(SourceTransport.Response response,RequestScope scope,org.jsoup.nodes.Document doc)throws SourceFailure {
        String base=SafeHttpTransport.normalizeUrl(response.finalUrl);List<Element> bases=doc.head().select("base[href]");
        if(!bases.isEmpty()) {
            String first=resolve(base,bases.get(0).attr("href"));
            for(Element e:bases)if(!resolve(base,e.attr("href")).equals(first))throw new SourceFailure("CONFLICTING_BASE");
            base=first;
        }
        List<Candidate> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Element link:doc.head().select("link[href][rel][type]")) {
            scope.check();boolean alternate=Arrays.asList(link.attr("rel").toLowerCase(Locale.ROOT).split("\\s+")).contains("alternate");
            String mime=link.attr("type").trim().toLowerCase(Locale.ROOT);if(!alternate||!mime.equals("application/rss+xml")&&!mime.equals("application/atom+xml"))continue;
            String url=resolve(base,link.attr("href"));if(seen.add(url)){if(out.size()>=8)throw new SourceFailure("DISCOVERY_LIMIT");out.add(new Candidate(SemanticTextNormalizer.displayTitle(link.attr("title")),url,mime));}
        }
        return Collections.unmodifiableList(out);
    }
    static String resolve(String base,String relative)throws SourceFailure {try{return SafeHttpTransport.normalizeUrl(new URI(base).resolve(relative).toString());}catch(java.net.URISyntaxException|IllegalArgumentException invalid){throw new SourceFailure("URL_SYNTAX",invalid);}}
}
