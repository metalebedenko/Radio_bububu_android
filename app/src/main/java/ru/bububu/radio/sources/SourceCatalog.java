package ru.bububu.radio.sources;

import java.util.*;

/** Built-in choices are independent of the user's durable connection lifetimes. */
public final class SourceCatalog {
    private SourceCatalog() {}

    public static final class Entry {
        public final String name,input,description;
        public final boolean telegram;
        private final Set<String> identities;

        public Entry(String name,String input,boolean telegram,String description,String... aliases) {
            this.name=SourceLimits.value(name,SourceLimits.MAX_SOURCE_NAME,"catalogue name",false);
            if(name.trim().isEmpty())throw new IllegalArgumentException("Empty catalogue name");
            this.input=Objects.requireNonNull(input);this.telegram=telegram;
            this.description=SourceLimits.value(description,1000,"catalogue description",false);
            Set<String> keys=new LinkedHashSet<>();keys.add(identity(input,telegram));
            for(String alias:aliases)keys.add(identity(alias,telegram));
            identities=Collections.unmodifiableSet(keys);
        }
    }

    private static final List<Entry> ENTRIES=closedEntries();
    private static List<Entry> closedEntries(){List<Entry> entries=new ArrayList<>();for(SourceProfiles45.Profile p:SourceProfiles45.all())entries.add(new Entry(p.label,p.endpoint,false,p.description(),p.aliases.toArray(new String[0])));return Collections.unmodifiableList(entries);}
    public static List<Entry> entries(){return ENTRIES;}

    public static SourceRecord connected(Entry entry,List<SourceRecord> sources) {
        for(SourceRecord source:sources) {
            if(entry.telegram!=source.type.equals("telegram_public"))continue;
            if(entry.identities.contains(source.key)||entry.identities.contains(source.url))return source;
            for(String alias:source.aliases)if(entry.identities.contains(alias))return source;
        }
        return null;
    }

    /** The preview can discover a canonical URL not yet known to the static list. */
    public static SourceRecord connected(SourceRecord preview,List<SourceRecord> sources) {
        for(SourceRecord source:sources) {
            if(!source.type.equals(preview.type))continue;
            if(source.key.equals(preview.key)||source.aliases.contains(preview.key)||preview.aliases.contains(source.key))return source;
            for(String alias:source.aliases)if(preview.aliases.contains(alias))return source;
        }
        return null;
    }

    private static String identity(String input,boolean telegram) {
        try{String key=telegram?TelegramPreviewAdapter.normalizeInput(input).sourceKey:SafeHttpTransport.normalizeUrl(input);if(!telegram&&!key.startsWith("https://"))throw new IllegalArgumentException("Catalogue requires HTTPS");return key;}
        catch(SourceFailure invalid){throw new IllegalArgumentException("Invalid catalogue identity",invalid);}
    }
}
