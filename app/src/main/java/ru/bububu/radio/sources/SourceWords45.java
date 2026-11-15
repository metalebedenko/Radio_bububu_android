package ru.bububu.radio.sources;

import java.time.LocalDate;

/** Pure source45 wording. Never changes the legacy/Notes projection. */
public final class SourceWords45 {
    private SourceWords45(){}
    private static final String[] SMALL={"ноль","один","два","три","четыре","пять","шесть","семь","восемь","девять","десять","одиннадцать","двенадцать","тринадцать","четырнадцать","пятнадцать","шестнадцать","семнадцать","восемнадцать","девятнадцать"};
    private static final String[] TENS={"","","двадцать","тридцать","сорок","пятьдесят","шестьдесят","семьдесят","восемьдесят","девяносто"};
    private static final String[] HUND={"","сто","двести","триста","четыреста","пятьсот","шестьсот","семьсот","восемьсот","девятьсот"};
    private static final String[] GEN={"нуля","одного","двух","трёх","четырёх","пяти","шести","семи","восьми","девяти","десяти","одиннадцати","двенадцати","тринадцати","четырнадцати","пятнадцати","шестнадцати","семнадцати","восемнадцати","девятнадцати"};
    private static final String[] TGEN={"","","двадцати","тридцати","сорока","пятидесяти","шестидесяти","семидесяти","восьмидесяти","девяноста"};
    private static final String[] HGEN={"","ста","двухсот","трёхсот","четырёхсот","пятисот","шестисот","семисот","восьмисот","девятисот"};
    private static final String[] ORD={"","первого","второго","третьего","четвёртого","пятого","шестого","седьмого","восьмого","девятого","десятого","одиннадцатого","двенадцатого","тринадцатого","четырнадцатого","пятнадцатого","шестнадцатого","семнадцатого","восемнадцатого","девятнадцатого"};
    private static final String[] TORD={"","","двадцатого","тридцатого","сорокового","пятидесятого","шестидесятого","семидесятого","восьмидесятого","девяностого"};
    private static final String[] HORD={"","сотого","двухсотого","трёхсотого","четырёхсотого","пятисотого","шестисотого","семисотого","восьмисотого","девятисотого"};
    private static final String[] KORD={"","тысячного","двухтысячного","трёхтысячного","четырёхтысячного","пятитысячного","шеститысячного","семитысячного","восьмитысячного","девятитысячного"};
    private static final String[] MONTH={"","январь","февраль","март","апрель","май","июнь","июль","август","сентябрь","октябрь","ноябрь","декабрь"};
    private static final String[] MGEN={"","января","февраля","марта","апреля","мая","июня","июля","августа","сентября","октября","ноября","декабря"};
    private static void bound(int n){if(n<0||n>9999)throw new IllegalArgumentException("SOURCE_NUMERAL_RANGE");}
    public static String cardinal(int n){bound(n);if(n<20)return SMALL[n];if(n<100)return TENS[n/10]+(n%10==0?"":" "+SMALL[n%10]);if(n<1000)return HUND[n/100]+(n%100==0?"":" "+cardinal(n%100));int k=n/1000;return (k==1?"одна тысяча":k==2?"две тысячи":SMALL[k]+(k<5?" тысячи":" тысяч"))+(n%1000==0?"":" "+cardinal(n%1000));}
    public static String genitive(int n){bound(n);if(n<20)return GEN[n];if(n<100)return TGEN[n/10]+(n%10==0?"":" "+GEN[n%10]);if(n<1000)return HGEN[n/100]+(n%100==0?"":" "+genitive(n%100));int k=n/1000;return (k==1?"одной тысячи":GEN[k]+" тысяч")+(n%1000==0?"":" "+genitive(n%1000));}
    private static final String[] DAT={"нулю","одному","двум","трём","четырём","пяти","шести","семи","восьми","девяти","десяти","одиннадцати","двенадцати","тринадцати","четырнадцати","пятнадцати","шестнадцати","семнадцати","восемнадцати","девятнадцати"};
    private static final String[] HDAT={"","ста","двумстам","трёмстам","четырёмстам","пятистам","шестистам","семистам","восьмистам","девятистам"};
    public static String dative(int n) {
        bound(n);
        if(n<20)return DAT[n];
        if(n<100)return TGEN[n/10]+(n%10==0?"":" "+DAT[n%10]);
        if(n<1000)return HDAT[n/100]+(n%100==0?"":" "+dative(n%100));
        int thousands=n/1000;
        return (thousands==1?"одной тысяче":DAT[thousands]+" тысячам")+(n%1000==0?"":" "+dative(n%1000));
    }
    static String ordinal(int n){if(n<1||n>9999)throw new IllegalArgumentException("SOURCE_YEAR_RANGE");if(n<20)return ORD[n];if(n<100)return n%10==0?TORD[n/10]:TENS[n/10]+" "+ORD[n%10];if(n<1000)return n%100==0?HORD[n/100]:HUND[n/100]+" "+ordinal(n%100);if(n%1000==0)return KORD[n/1000];String k=cardinal(n/1000*1000);if(n/1000==1)k="тысяча";return k+" "+ordinal(n%1000);}
    public static String year(int n){return ordinal(n)+" года";}
    public static String month(int y,int m){if(m<1||m>12)throw new IllegalArgumentException("SOURCE_MONTH");return MONTH[m]+" "+year(y);}
    static String neuterDay(int day){String ord=ordinal(day);return ord.substring(0,ord.length()-3)+(ord.endsWith("его")?"е":"ое");}
    public static String day(LocalDate d){return neuterDay(d.getDayOfMonth())+" "+MGEN[d.getMonthValue()]+" "+year(d.getYear());}
}
