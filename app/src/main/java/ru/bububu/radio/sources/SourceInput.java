package ru.bububu.radio.sources;

import java.net.URI;

/** User-entry convenience only. Every resulting URL still goes through the existing guards. */
public final class SourceInput {
    public final String value,fetchUrl;public final boolean telegram;
    private SourceInput(String value,String fetchUrl,boolean telegram){this.value=value;this.fetchUrl=fetchUrl;this.telegram=telegram;}
    public static SourceInput normalize(String input,boolean telegramRequested)throws SourceFailure{
        if(input==null)throw new SourceFailure("URL_SYNTAX");String value=input.trim();
        if(value.startsWith("@"))return telegram(value);
        if(value.matches("(?s)^[A-Za-z][A-Za-z0-9+.-]*:.*")){
            if(!value.matches("(?is)^https?://.*"))throw new SourceFailure("URL_SYNTAX");
        }else value="https://"+value;
        String canonical=SafeHttpTransport.normalizeUrl(value);
        if(telegramRequested||"t.me".equals(URI.create(canonical).getHost()))return telegram(value);
        return new SourceInput(canonical,canonical,false);
    }
    private static SourceInput telegram(String value)throws SourceFailure{
        TelegramPreviewAdapter.Target target=TelegramPreviewAdapter.normalizeInput(value);
        return new SourceInput("@"+target.username,target.fetchUrl,true);
    }
}
