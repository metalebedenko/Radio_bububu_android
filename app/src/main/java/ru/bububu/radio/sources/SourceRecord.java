package ru.bububu.radio.sources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Immutable connection settings; station name and ID do not follow publisher refresh changes. */
public final class SourceRecord {
    public final String id,stationId,type,key,name,url,adapterId,adapterVersion,etag,lastModified,lastError;
    public final List<String> aliases;
    public final boolean enabled,catalog;
    public final long lastSuccess;
    public ChannelProfile.Kind kind(){return ChannelProfile.kind(adapterVersion);}
    public String channelReference(){return ChannelProfile.reference(adapterVersion);}
    public SourceRecord(String id,String stationId,String type,String key,String name,String url,String adapterId,
                        String adapterVersion,List<String> aliases,boolean enabled,boolean catalog,
                        String etag,String lastModified,long lastSuccess,String lastError) {
        if(!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("source UUID");
        this.id=id; this.stationId=SourceLimits.id(stationId,"stationId");
        if(stationId.equals("A")||stationId.equals("B")||stationId.equals("C")||stationId.equals("MY_TEXTS")) throw new IllegalArgumentException("Reserved station");
        this.type=SourceLimits.value(type,64,"type",false); this.key=SourceLimits.value(key,4096,"key",false);
        this.name=SourceLimits.value(name,SourceLimits.MAX_SOURCE_NAME,"name",false);
        if(type.trim().isEmpty()||key.trim().isEmpty()||name.trim().isEmpty()) throw new IllegalArgumentException("Empty source");
        this.url=SourceLimits.value(url,4096,"url",false); this.adapterId=SourceLimits.value(adapterId,128,"adapterId",false);
        this.adapterVersion=SourceLimits.value(adapterVersion,128,"adapterVersion",false);
        if(aliases.size()>16) throw new IllegalArgumentException("source aliases");
        ArrayList<String> copy=new ArrayList<>(); for(String alias:aliases) copy.add(SourceLimits.value(alias,4096,"alias",false));
        this.aliases=Collections.unmodifiableList(copy); this.enabled=enabled; this.catalog=catalog;
        this.etag=SourceLimits.value(etag,2000,"etag",true); this.lastModified=SourceLimits.value(lastModified,2000,"lastModified",true);
        if(lastSuccess<0) throw new IllegalArgumentException("lastSuccess"); this.lastSuccess=lastSuccess;
        this.lastError=SourceLimits.value(lastError,2000,"lastError",true);
    }
    public static SourceRecord create(String type,String key,String name,String url,String adapterId,boolean catalog) {
        String id=UUID.randomUUID().toString();
        return new SourceRecord(id,"SRC:"+id,type,key,name,url,adapterId,adapterId,Collections.singletonList(key),true,catalog,null,null,0,null);
    }
    public SourceRecord withState(boolean enabled,String etag,String lastModified,long success,String error) {
        return new SourceRecord(id,stationId,type,key,name,url,adapterId,adapterVersion,aliases,enabled,catalog,etag,lastModified,success,error);
    }
}
