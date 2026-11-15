package ru.bububu.radio.sources;

import java.time.Month;
import java.time.Year;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit day ranges and the written adjective "-летний". Never rewrites the canonical text. */
final class SourceDatesAndAges {
    private static final String S = "[ \\u00a0\\u202f]";
    private static final String LEFT = "(?<![\\p{L}\\p{M}\\p{N}_+#@./:$€₽=−‐‑–—-])";
    private static final String RIGHT = "(?![\\p{L}\\p{M}\\p{N}_/#:+$€₽=−‐‑–—-])(?![.,][0-9])";
    private static final String DAY = "([1-9][0-9]?)", MONTH = "(" + SourceNumbers45.MONTH + ")";
    private static final int RU = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern DATE_RANGE = Pattern.compile(LEFT + "(?:с|со)" + S + "+" + DAY
            + "(?:" + S + "+" + MONTH + ")?" + S + "+по" + S + "+" + DAY + S + "+" + MONTH + RIGHT, RU);
    private static final Pattern NEXT_YEAR = Pattern.compile(S + "+([1-9][0-9]{3})(?![\\p{L}\\p{N}_/:-])(?![.,][0-9])");
    private static final Pattern AGE = Pattern.compile(LEFT + "([1-9][0-9]?)[-‐‑](" + SourceNumbers45.AGE_END + ")" + RIGHT, RU);
    private static final List<String> MONTHS=Arrays.asList(SourceNumbers45.MONTH.split(Pattern.quote("|")));
    private SourceDatesAndAges() { }

    static void add(String text,List<ContextualSpeech.Reading> readings) {
        Matcher dates=DATE_RANGE.matcher(text);
        while(dates.find()){
            if(SourceNewsNumbers.codeLabelBefore(text,dates.start()))continue;
            int start=Integer.parseInt(dates.group(1)),end=Integer.parseInt(dates.group(3));
            Month last=month(dates.group(4)),first=dates.group(2)==null?last:month(dates.group(2));
            if(start>first.maxLength() || end>last.maxLength() || first==last && start>end)continue;
            // If this expression actually supplies a year, do not validate 29 February as a leap day in every year.
            Matcher year=NEXT_YEAR.matcher(text).region(dates.end(),text.length());
            if(year.lookingAt()){
                int lastYear=Integer.parseInt(year.group(1));
                if(last==Month.FEBRUARY && end==29 && !Year.isLeap(lastYear))continue;
                int firstYear=first.compareTo(last)<=0 ? lastYear : lastYear-1;
                if(first==Month.FEBRUARY && start==29 && !Year.isLeap(firstYear))continue;
            }
            readings.add(new ContextualSpeech.Reading(dates.start(1),dates.end(1),dates.start(),dates.end(),
                    SourceWords45.ordinal(start),"calendar-day-range-start"));
            readings.add(new ContextualSpeech.Reading(dates.start(3),dates.end(3),dates.start(),dates.end(),
                    SourceWords45.neuterDay(end),"calendar-day-range-end"));
        }
        Matcher ages=AGE.matcher(text);
        while(ages.find()){
            if(SourceNewsNumbers.codeLabelBefore(text,ages.start()) || numericPrefix(text,ages.start()))continue;
            readings.add(new ContextualSpeech.Reading(ages.start(),ages.end(),ages.start(),ages.end(),
                    SourceNumbers45.compoundAge(Integer.parseInt(ages.group(1)),ages.group(2)),"age-compound"));
        }
    }
    private static Month month(String raw) {
        return Month.of(1+MONTHS.indexOf(raw.toLowerCase(Locale.ROOT)));
    }
    private static boolean numericPrefix(String text,int start) {
        int cursor=start;
        while(cursor>0 && Character.isSpaceChar(text.charAt(cursor-1)))cursor--;
        if(cursor==0)return false;
        char previous=text.charAt(cursor-1);
        if(Character.isDigit(previous) || "+−-/№#$=".indexOf(previous)>=0)return true;
        if("–—,.".indexOf(previous)<0)return false;
        int before=cursor-1;
        while(before>0 && Character.isSpaceChar(text.charAt(before-1)))before--;
        return before>0 && Character.isDigit(text.charAt(before-1));
    }
}
