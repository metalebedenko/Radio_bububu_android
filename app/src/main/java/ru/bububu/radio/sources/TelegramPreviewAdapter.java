package ru.bububu.radio.sources;

import org.jsoup.nodes.*;
import okhttp3.HttpUrl;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.*;

public final class TelegramPreviewAdapter {
    public static final String VERSION="telegram-public-v1";
    public static final class Target {
        public final String username,sourceKey,fetchUrl;
        Target(String username){this.username=username;sourceKey="telegram_public:"+username;fetchUrl="https://t.me/s/"+username;}
    }
    public static Target normalizeInput(String input)throws SourceFailure {
        if(input==null)throw new SourceFailure("TELEGRAM_INPUT");String value=input.trim(),name;
        if(value.startsWith("@"))name=value.substring(1);
        else try {
            URI uri=new URI(value);HttpUrl url=HttpUrl.get(value);
            if(!url.scheme().equals("https")||!url.host().equals("t.me")||url.port()!=443||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new SourceFailure("TELEGRAM_INPUT");
            String path=uri.getRawPath();if(path.matches("/[A-Za-z0-9_]+"))name=path.substring(1);else if(path.matches("/s/[A-Za-z0-9_]+"))name=path.substring(3);else throw new SourceFailure("TELEGRAM_INPUT");
        }catch(java.net.URISyntaxException|IllegalArgumentException invalid){throw new SourceFailure("TELEGRAM_INPUT",invalid);}
        if(!name.matches("[A-Za-z0-9_]{1,64}")||Set.of("joinchat","c","s").contains(name.toLowerCase(Locale.ROOT)))throw new SourceFailure("TELEGRAM_INPUT");return new Target(name.toLowerCase(Locale.ROOT));
    }
    public FeedDocument parse(SourceTransport.Response response,String expectedUsername,RequestScope scope)throws SourceFailure {
        Target target=normalizeInput("@"+expectedUsername);
        if(!HttpUrl.get(response.finalUrl).host().equals("t.me"))throw new SourceFailure("TELEGRAM_CHANNEL_MISMATCH");
        DomBudgetGuard.Parsed parsed=DomBudgetGuard.parse(response,scope);Document document=parsed.document;
        Element channel=ClosedArticleSupport.unique(document.select(".tgme_channel_info_header_username"));
        if(channel==null||!channel.text().trim().equalsIgnoreCase("@"+target.username))throw new SourceFailure("TELEGRAM_CHANNEL_MISMATCH");
        Element header=ClosedArticleSupport.unique(document.select(".tgme_channel_info_header_title"));String title=header==null?"@"+target.username:SemanticTextNormalizer.displayTitle(header.text());
        // t.me/s renders older messages first. The source preview exposes only the first
        // three items, so keep the newest IDs even if the page exceeds the item budget.
        // IDs are canonical positive decimals within this channel and may exceed a long.
        Comparator<String> newestFirst = Comparator.comparingInt(String::length)
                .thenComparing(Comparator.naturalOrder()).reversed();
        NavigableMap<String, FeedItem> items = new TreeMap<>(newestFirst);
        Map<String, String> seenBodies = new HashMap<>();
        boolean limit=false;
        List<Element> messages=document.select(".tgme_widget_message[data-post]");if(messages.isEmpty())throw new SourceFailure("TELEGRAM_PREVIEW_STRUCTURE");
        for(Element container:messages) {
            scope.check();String key=container.attr("data-post");int slash=key.indexOf('/');
            if(slash<=0||!key.substring(0,slash).equalsIgnoreCase(target.username)||!key.substring(slash+1).matches("[1-9][0-9]{0,19}"))throw new SourceFailure("TELEGRAM_CHANNEL_MISMATCH");
            String postId=key.substring(slash+1),permalink="https://t.me/"+target.username+"/"+postId;
            List<Element> own=new ArrayList<>();for(Element candidate:container.select(".tgme_widget_message_text.js-message_text"))if(candidate.closest(".tgme_widget_message")==container&&!inReply(candidate,container))own.add(candidate);
            if(own.size()>1)throw new SourceFailure("TELEGRAM_MULTIPLE_OWN_BODY");
            String body="",reason;String quality;
            if(own.isEmpty()){quality="unavailable";reason=container.select(".message_media_not_supported_label").isEmpty()?"NO_OWN_TEXT":"unsupported_web_preview";}
            else {
                Element copy=own.get(0).clone();for(Element child:new ArrayList<>(copy.getAllElements()))if(child!=copy&&reply(child))child.remove();
                try{body=SemanticTextNormalizer.fromDom(copy,scope);quality=body.isEmpty()?"unavailable":"unknown";reason=body.isEmpty()?"NO_OWN_TEXT":"PUBLIC_PREVIEW_COMPLETENESS_UNKNOWN";}
                catch(SourceFailure limitError){if(limitError.reason.equals("CANCELLED")||limitError.reason.equals("TIMEOUT"))throw limitError;quality="unavailable";reason=limitError.reason;}
            }
            String display=body.isEmpty()?"Пост "+postId:SemanticTextNormalizer.displayTitle(body);
            ExtractionResult extraction=new ExtractionResult(body,"",display,quality,reason,"public_preview_text",VERSION);
            Element time=ClosedArticleSupport.unique(container.select(".tgme_widget_message_date time[datetime]"));String raw=time==null?null:time.attr("datetime");Long published=null;
            if(raw!=null)try{published=OffsetDateTime.parse(raw).toInstant().toEpochMilli();}catch(java.time.DateTimeException invalid){/* raw date is retained */}
            FeedItem item=new FeedItem(postId,permalink,display,raw,null,published,null,extraction,target.username,postId);
            String signature = extraction.bodyHash + ":" + extraction.reason;
            String previous = seenBodies.putIfAbsent(postId, signature);
            if (previous != null) {
                if (!previous.equals(signature)) throw new SourceFailure("TELEGRAM_CONFLICTING_POST");
                continue;
            }
            items.put(postId,item);
            if(items.size()>SourceLimits.MAX_PARSED_ITEMS){items.pollLastEntry();limit=true;}
        }
        scope.check();return new FeedDocument(title,response.finalUrl,"telegram",new ArrayList<>(items.values()),limit);
    }
    private static boolean inReply(Element node,Element container){for(Element parent=node;parent!=null&&parent!=container;parent=parent.parent())if(reply(parent))return true;return false;}
    private static boolean reply(Element node){if(node.hasClass("js-message_reply_text"))return true;for(String name:node.classNames())if(name.startsWith("tgme_widget_message_reply"))return true;return false;}
}
