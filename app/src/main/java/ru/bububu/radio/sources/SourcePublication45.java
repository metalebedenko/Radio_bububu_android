package ru.bububu.radio.sources;

import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Version-bound publication facts. Capture evidence is stored but excluded from identity. */
public final class SourcePublication45 {
    public static final String CODEC="publication45-v1", CONTEXT_POLICY="publication-calendar45-v1";
    public enum Role { ORIGINAL_PUBLISHED, REPUBLISHED, MODIFIED, CREATED, OTHER_CLAIM }
    public enum Precision { UNKNOWN, YEAR, MONTH, DAY, INSTANT }
    public enum Context { DATED, UNKNOWN_MANUAL, INVALID_MANUAL, FUTURE_MANUAL, CLOCK_MANUAL }
    public static final class Fact {
        public final Role role;public final Precision precision;
        public final String raw,value,authority,evidenceUrl,selector,rule;
        public final Long epochMillis;public final Integer offsetSeconds;
        public Fact(Role role,Precision precision,String raw,String value,Long epochMillis,Integer offsetSeconds,String authority,String evidenceUrl,String selector,String rule){
            this.role=Objects.requireNonNull(role);this.precision=Objects.requireNonNull(precision);
            this.raw=v(raw,2000);this.value=v(value,2000);this.authority=v(authority,128);this.evidenceUrl=v(evidenceUrl,4096);this.selector=v(selector,512);this.rule=v(rule,128);
            this.epochMillis=epochMillis;this.offsetSeconds=offsetSeconds;
            if(precision==Precision.INSTANT){if(epochMillis==null||offsetSeconds==null)throw new IllegalArgumentException("Instant needs proven zone");OffsetDateTime d=Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.ofTotalSeconds(offsetSeconds)),v=OffsetDateTime.parse(value);if(d.getYear()<1||d.getYear()>9999||!d.toInstant().equals(v.toInstant())||v.getOffset().getTotalSeconds()!=offsetSeconds)throw new IllegalArgumentException("Instant identity");}
            else{if(epochMillis!=null||offsetSeconds!=null)throw new IllegalArgumentException("Partial date has no instant/zone");switch(precision){case YEAR:if(!value.matches("[0-9]{4}")||Integer.parseInt(value)<1)throw new IllegalArgumentException("Year");break;case MONTH:YearMonth.parse(value);break;case DAY:LocalDate.parse(value);break;case UNKNOWN:if(!value.isEmpty())throw new IllegalArgumentException("Unknown date value");break;default:throw new IllegalArgumentException("Precision");}}
            if(precision==Precision.MONTH||precision==Precision.DAY){int y=precision==Precision.MONTH?YearMonth.parse(value).getYear():LocalDate.parse(value).getYear();if(y<1||y>9999)throw new IllegalArgumentException("Publication year range");}
        }
        public String identity(){return SourceLimits.snapshotVersion(role.name(),precision.name(),raw,value,Objects.toString(epochMillis,""),Objects.toString(offsetSeconds,""),authority,evidenceUrl,selector,rule);}
        public String spoken(){switch(precision){case YEAR:return SourceWords45.year(Integer.parseInt(value));case MONTH:YearMonth m=YearMonth.parse(value);return SourceWords45.month(m.getYear(),m.getMonthValue());case DAY:return SourceWords45.day(LocalDate.parse(value));case INSTANT:return SourceWords45.day(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.ofTotalSeconds(offsetSeconds)).toLocalDate());default:return "";}}
    }
    public final Fact primary;public final List<Fact> facts;
    public final String profileConfigSha,ruleRevision,conflict,responseSha,canonicalResponseSha,linkProvenanceDigest,subjectUrl,subjectId;
    public SourcePublication45(Fact primary,List<Fact> evidence,String config,String rule,String conflict,String responseSha){
        this(primary,evidence,config,rule,conflict,responseSha,"","");
    }
    public SourcePublication45(Fact primary,List<Fact> evidence,String config,String rule,String conflict,String responseSha,String canonicalResponseSha,String linkDigest){
        this(primary,evidence,config,rule,conflict,responseSha,canonicalResponseSha,linkDigest,"","");
    }
    public SourcePublication45(Fact primary,List<Fact> evidence,String config,String rule,String conflict,String responseSha,String canonicalResponseSha,String linkDigest,String subjectUrl,String subjectId){
        this.primary=Objects.requireNonNull(primary);if(primary.role!=Role.ORIGINAL_PUBLISHED)throw new IllegalArgumentException("Primary role");
        if(evidence.size()>8)throw new IllegalArgumentException("Publication fact count");
        this.profileConfigSha=v(config,64);if(!config.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Profile fingerprint");
        this.ruleRevision=v(rule,128);this.conflict=v(conflict,128);this.responseSha=v(responseSha,64);if(!this.responseSha.isEmpty()&&!this.responseSha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Response SHA");
        this.canonicalResponseSha=v(canonicalResponseSha,64);this.linkProvenanceDigest=v(linkDigest,64);for(String hash:List.of(this.canonicalResponseSha,this.linkProvenanceDigest))if(!hash.isEmpty()&&!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Capture provenance digest");
        this.subjectUrl=v(subjectUrl,4096);this.subjectId=v(subjectId,4096);
        List<Fact> sorted=new ArrayList<>(evidence);sorted.sort(Comparator.comparing((Fact f)->f.role.ordinal()).thenComparing(f->f.authority).thenComparing(f->f.evidenceUrl).thenComparing(f->f.selector).thenComparing(f->f.raw).thenComparing(f->f.precision.ordinal()).thenComparing(f->f.value));facts=Collections.unmodifiableList(sorted);
        if(encode().length>16384)throw new IllegalArgumentException("Publication extension limit");
    }
    private static String v(String s,int n){return SourceLimits.value(s==null?"":s,n,"publication field",false);}
    public String identityDigest(){List<String> values=new ArrayList<>(Arrays.asList(CODEC,profileConfigSha,ruleRevision,conflict,subjectUrl,subjectId,primary.identity()));for(Fact f:facts)values.add(f.identity());return SourceLimits.snapshotVersion(values.toArray(new String[0]));}
    public String context(Context mode){
        if(mode==Context.DATED&&primary.precision==Precision.UNKNOWN)throw new IllegalArgumentException("Unknown dated frame");
        StringBuilder out=new StringBuilder();switch(mode){
            case UNKNOWN_MANUAL:out.append("Предупреждение: дата исходной публикации не подтверждена. ");break;
            case INVALID_MANUAL:out.append("Предупреждение: дата исходной публикации некорректна и требует проверки. ");break;
            case FUTURE_MANUAL:out.append("Предупреждение: при ручном добавлении дата исходной публикации была в будущем и требует проверки. ");break;
            case CLOCK_MANUAL:out.append("Предупреждение: время проверки ненадёжно, актуальность даты не подтверждена. ");break;
            default:break;
        }
        if(primary.precision!=Precision.UNKNOWN)out.append("Оригинальная публикация: ").append(primary.spoken()).append(". ");
        Set<String> seen=new HashSet<>();for(Fact f:facts)if(f.role==Role.REPUBLISHED&&f.precision!=Precision.UNKNOWN&&seen.add(f.precision+":"+f.value))out.append("Повторная публикация: ").append(f.spoken()).append(". ");
        String result=out.toString().trim()+"\n\n";if(result.trim().isEmpty()||result.length()>SourceLimits.MAX_PUBLICATION_CONTEXT)throw new IllegalArgumentException("Publication context limit");return result;
    }
    public String label(Context mode){return context(mode).trim();}
    public byte[] encode(){try{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream o=new DataOutputStream(b);o.writeInt(1);write(o,profileConfigSha);write(o,ruleRevision);write(o,conflict);write(o,responseSha);write(o,canonicalResponseSha);write(o,linkProvenanceDigest);write(o,subjectUrl);write(o,subjectId);writeFact(o,primary);o.writeInt(facts.size());for(Fact f:facts)writeFact(o,f);o.flush();return b.toByteArray();}catch(IOException impossible){throw new AssertionError(impossible);}}
    public static SourcePublication45 decode(byte[] bytes)throws IOException{
        if(bytes.length>16384)throw new IOException("Publication extension limit");try{DataInputStream i=new DataInputStream(new ByteArrayInputStream(bytes));if(i.readInt()!=1)throw new IOException("Publication codec");String c=read(i,64),r=read(i,128),conflict=read(i,128),response=read(i,64),canonical=read(i,64),links=read(i,64),subject=read(i,4096),subjectId=read(i,4096);Fact primary=readFact(i);int n=i.readInt();if(n<0||n>8)throw new IOException("Fact count");List<Fact> facts=new ArrayList<>();for(int z=0;z<n;z++)facts.add(readFact(i));if(i.available()!=0)throw new IOException("Publication trailing bytes");SourcePublication45 p=new SourcePublication45(primary,facts,c,r,conflict,response,canonical,links,subject,subjectId);if(!Arrays.equals(bytes,p.encode()))throw new IOException("Noncanonical publication");return p;}catch(IllegalArgumentException bad){throw new IOException("Invalid publication",bad);}
    }
    private static void write(DataOutputStream o,String s)throws IOException{o.writeInt(s.length());for(int n=0;n<s.length();n++)o.writeChar(s.charAt(n));}
    private static String read(DataInputStream i,int cap)throws IOException{int n=i.readInt();if(n<0||n>cap)throw new IOException("Publication string limit");StringBuilder s=new StringBuilder(n);for(int z=0;z<n;z++)s.append(i.readChar());return s.toString();}
    private static void writeFact(DataOutputStream o,Fact f)throws IOException{o.writeByte(f.role.ordinal());o.writeByte(f.precision.ordinal());write(o,f.raw);write(o,f.value);o.writeBoolean(f.epochMillis!=null);if(f.epochMillis!=null){o.writeLong(f.epochMillis);o.writeInt(f.offsetSeconds);}write(o,f.authority);write(o,f.evidenceUrl);write(o,f.selector);write(o,f.rule);}
    private static Fact readFact(DataInputStream i)throws IOException{int r=i.readUnsignedByte(),p=i.readUnsignedByte();if(r>=Role.values().length||p>=Precision.values().length)throw new IOException("Publication enums");String raw=read(i,2000),value=read(i,2000);boolean instant=i.readBoolean();Long epoch=instant?i.readLong():null;Integer offset=instant?i.readInt():null;return new Fact(Role.values()[r],Precision.values()[p],raw,value,epoch,offset,read(i,128),read(i,4096),read(i,512),read(i,128));}
    public static Fact unknown(String raw,String authority,String url,String selector){return new Fact(Role.ORIGINAL_PUBLISHED,Precision.UNKNOWN,raw,"",null,null,authority,url,selector,"date45-v1");}
    private static boolean basicIsoOffset(String raw){
        return raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,9})?)?[+-][0-9]{4}");
    }
    // Only explicit batch refresh may fetch a new snapshot for this formerly unsupported date.
    static boolean needsDateRefresh(SourceVersionRecord version){
        if(version.publication==null)return false;Fact old=version.publication.primary;
        return old.precision==Precision.UNKNOWN&&basicIsoOffset(old.raw.trim())
                &&parse(old.role,old.raw,old.authority,old.evidenceUrl,old.selector,false).precision==Precision.INSTANT;
    }
    public static Fact parse(Role role,String raw,String authority,String url,String selector,boolean explicitGmt){
        if(raw==null||raw.trim().isEmpty())return new Fact(role,Precision.UNKNOWN,"","",null,null,authority,url,selector,"date45-v1");
        try{String x=raw.trim();if(x.matches("[0-9]{4}"))return new Fact(role,Precision.YEAR,raw,x,null,null,authority,url,selector,"date45-v1");if(x.matches("[0-9]{4}-[0-9]{2}"))return new Fact(role,Precision.MONTH,raw,YearMonth.parse(x).toString(),null,null,authority,url,selector,"date45-v1");if(x.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))return new Fact(role,Precision.DAY,raw,LocalDate.parse(x).toString(),null,null,authority,url,selector,"date45-v1");boolean basic=basicIsoOffset(x);if(basic)x=x.substring(0,x.length()-2)+":"+x.substring(x.length()-2);OffsetDateTime d;try{d=OffsetDateTime.parse(x);}catch(DateTimeException bad){if(explicitGmt)d=LocalDateTime.parse(x).atOffset(ZoneOffset.UTC);else d=ZonedDateTime.parse(x,DateTimeFormatter.RFC_1123_DATE_TIME).toOffsetDateTime();}return new Fact(role,Precision.INSTANT,raw,d.toString(),d.toInstant().toEpochMilli(),d.getOffset().getTotalSeconds(),authority,url,selector,basic?"date45-iso-basic-offset-v1":"date45-v1");}
        catch(DateTimeException|IllegalArgumentException invalid){return new Fact(role,Precision.UNKNOWN,raw,"",null,null,authority,url,selector,"date45-v1");}
    }
}
