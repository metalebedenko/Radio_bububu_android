package ru.bububu.radio.sources;

import java.time.*;
import java.util.Objects;

/** One run's wall/monotonic anchor. Read/resume of admitted data never calls this gate. */
public final class SourceAdmission45 {
    public static final long BRIEF_AGE_MILLIS=604800000L, MAX_DRIFT_MILLIS=60000L;
    private static final long MAX_WALL_MILLIS=253402300799999L;
    private final SourceClock clock;private final long wall,mono;
    public SourceAdmission45(SourceClock clock){this.clock=Objects.requireNonNull(clock);wall=clock.wallMillis();mono=clock.monotonicMillis();}
    public Long now(){long m=clock.monotonicMillis(),w=clock.wallMillis();if(wall<0||wall>MAX_WALL_MILLIS||m<mono)return null;long elapsed=m-mono;if(elapsed>Long.MAX_VALUE-wall)return null;long n=wall+elapsed;if(n>MAX_WALL_MILLIS||w<0||w>MAX_WALL_MILLIS||Math.abs((double)w-n)>MAX_DRIFT_MILLIS)return null;return n;}
    public SourcePublication45.Context context(SourcePublication45 p,boolean individualManual)throws SourceFailure{
        SourcePublication45.Fact f=p.primary;SourcePublication45.Context mode;
        if(f.precision==SourcePublication45.Precision.UNKNOWN)mode=p.conflict.equals("INVALID")?SourcePublication45.Context.INVALID_MANUAL:SourcePublication45.Context.UNKNOWN_MANUAL;
        else{Long n=now();mode=n==null?SourcePublication45.Context.CLOCK_MANUAL:future(f,n)?SourcePublication45.Context.FUTURE_MANUAL:SourcePublication45.Context.DATED;}
        if(!individualManual&&mode!=SourcePublication45.Context.DATED)throw new SourceFailure("PUBLICATION_AUTOMATIC_REFUSED");return mode;
    }
    private boolean future(SourcePublication45.Fact f,long n){LocalDate d=Instant.ofEpochMilli(n).atOffset(ZoneOffset.UTC).toLocalDate();switch(f.precision){case INSTANT:return f.epochMillis>n;case DAY:return LocalDate.parse(f.value).compareTo(d)>0;case MONTH:return YearMonth.parse(f.value).compareTo(YearMonth.from(d))>0;case YEAR:return Integer.parseInt(f.value)>d.getYear();default:return true;}}
    public void require(SourceVersionRecord v,boolean individualManual)throws SourceFailure{
        if(v.publication==null){if(v.kind()==ChannelProfile.Kind.NEWS_BRIEF||!individualManual)throw new SourceFailure("PUBLICATION_UNKNOWN");return;}
        SourcePublication45 p=v.publication;Long n=now();
        if(v.kind()==ChannelProfile.Kind.NEWS_BRIEF){SourcePublication45.Fact f=p.primary;if(n==null||f.precision!=SourcePublication45.Precision.INSTANT||f.epochMillis>n||n-f.epochMillis>BRIEF_AGE_MILLIS||v.contextClass!=SourcePublication45.Context.DATED)throw new SourceFailure("BRIEF_PUBLICATION_NOT_ELIGIBLE");return;}
        if(v.kind()!=ChannelProfile.Kind.ARTICLE_ANALYSIS)throw new SourceFailure("PUBLICATION_UNTRUSTED_KIND");
        SourcePublication45.Context expected=context(p,individualManual);if(v.contextClass==SourcePublication45.Context.DATED&&expected!=SourcePublication45.Context.DATED)throw new SourceFailure("PUBLICATION_REFRAME_REQUIRED");if(!individualManual&&v.contextClass!=SourcePublication45.Context.DATED)throw new SourceFailure("PUBLICATION_AUTOMATIC_REFUSED");
    }
}
