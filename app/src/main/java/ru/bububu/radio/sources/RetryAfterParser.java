package ru.bububu.radio.sources;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RetryAfterParser {
    public enum Kind { ABSENT, DELAY, INVALID, EXCESSIVE }
    public static final class Result {
        public final Kind kind; public final long delayMillis; public final boolean malformed;
        private Result(Kind kind,long delay,boolean malformed){this.kind=kind;delayMillis=delay;this.malformed=malformed;}
    }
    private static final Pattern IMF=Pattern.compile("[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT");
    private static final Pattern OLD=Pattern.compile("([A-Z][a-z]+), ([0-9]{2})-([A-Z][a-z]{2})-([0-9]{2}) ([0-9]{2}:[0-9]{2}:[0-9]{2}) GMT");
    private static final Pattern ASC=Pattern.compile("([A-Z][a-z]{2}) ([A-Z][a-z]{2}) ([ 0-9][0-9]) ([0-9]{2}:[0-9]{2}:[0-9]{2}) ([0-9]{4})");
    private RetryAfterParser() { }
    public static Result parse(List<String> values,long nowWall,long nowMono) {
        if(values==null || values.isEmpty())return new Result(Kind.ABSENT,0,false);
        long max=0; boolean valid=false,invalid=false;
        for(String raw:values) {
            if(raw==null){invalid=true;continue;}
            if(raw.length()>4096)return new Result(Kind.EXCESSIVE,Long.MAX_VALUE,true);
            String value=trimOws(raw);
            long delay;
            if(!value.isEmpty() && asciiDigits(value)) {
                long seconds=0;
                for(int i=0;i<value.length();i++) {
                    int digit=value.charAt(i)-'0';
                    if(seconds>(Long.MAX_VALUE-digit)/10)return new Result(Kind.EXCESSIVE,Long.MAX_VALUE,invalid);
                    seconds=seconds*10+digit;
                }
                if(seconds>Long.MAX_VALUE/1000)return new Result(Kind.EXCESSIVE,Long.MAX_VALUE,invalid);
                delay=seconds*1000;
            } else {
                Long date=parseHttpDate(value,nowWall);
                if(date==null){invalid=true;continue;}
                if(date<=nowWall)delay=0;
                else { try{delay=Math.subtractExact(date,nowWall);}catch(ArithmeticException excessive){return new Result(Kind.EXCESSIVE,Long.MAX_VALUE,invalid);} }
            }
            if(nowWall>Long.MAX_VALUE-delay || nowMono>Long.MAX_VALUE-delay)return new Result(Kind.EXCESSIVE,Long.MAX_VALUE,invalid);
            valid=true; max=Math.max(max,delay);
        }
        return new Result(valid?Kind.DELAY:Kind.INVALID,max,invalid);
    }
    private static boolean asciiDigits(String value){for(int i=0;i<value.length();i++)if(value.charAt(i)<'0'||value.charAt(i)>'9')return false;return true;}
    private static String trimOws(String value){int start=0,end=value.length();while(start<end&&(value.charAt(start)==' '||value.charAt(start)=='\t'))start++;while(end>start&&(value.charAt(end-1)==' '||value.charAt(end-1)=='\t'))end--;return value.substring(start,end);}
    public static Long parseHttpDate(String value,long nowWall) {
        try {
            if(IMF.matcher(value).matches())return ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)).toInstant().toEpochMilli();
            Matcher old=OLD.matcher(value);
            if(old.matches()) {
                int year=Instant.ofEpochMilli(nowWall).atZone(ZoneOffset.UTC).getYear();
                int candidate=year/100*100+Integer.parseInt(old.group(4));
                if(candidate>year+50)candidate-=100;
                String day=old.group(1).substring(0,3);
                String imf=day+", "+old.group(2)+" "+old.group(3)+" "+candidate+" "+old.group(5)+" GMT";
                Long result=ZonedDateTime.parse(imf,DateTimeFormatter.RFC_1123_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)).toInstant().toEpochMilli();
                String expected=Instant.ofEpochMilli(result).atZone(ZoneOffset.UTC).getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL,Locale.US);
                return expected.equals(old.group(1))?result:null;
            }
            Matcher asc=ASC.matcher(value);
            if(asc.matches()) {
                String day=asc.group(3).trim(); if(day.length()==1)day="0"+day;
                return ZonedDateTime.parse(asc.group(1)+", "+day+" "+asc.group(2)+" "+asc.group(5)+" "+asc.group(4)+" GMT",DateTimeFormatter.RFC_1123_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)).toInstant().toEpochMilli();
            }
        } catch(DateTimeParseException | ArithmeticException | IndexOutOfBoundsException invalid) { return null; }
        return null;
    }
}
