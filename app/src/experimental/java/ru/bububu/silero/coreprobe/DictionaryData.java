package ru.bububu.silero.coreprobe;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
/** Same accepted47symbol data, SHA5b6a91e4; no text fixture lookup. */
public final class DictionaryData {
    public static Map<Character,Integer> symbols() {
        Map<Character,Integer> m=new HashMap<>();
        m.put('_',0);
        m.put('~',1);
        m.put('|',2);
        m.put('!',3);
        m.put('+',4);
        m.put(',',5);
        m.put('-',6);
        m.put('.',7);
        m.put(':',8);
        m.put(';',9);
        m.put('?',10);
        m.put('а',11);
        m.put('б',12);
        m.put('в',13);
        m.put('г',14);
        m.put('д',15);
        m.put('е',16);
        m.put('ж',17);
        m.put('з',18);
        m.put('и',19);
        m.put('й',20);
        m.put('к',21);
        m.put('л',22);
        m.put('м',23);
        m.put('н',24);
        m.put('о',25);
        m.put('п',26);
        m.put('р',27);
        m.put('с',28);
        m.put('т',29);
        m.put('у',30);
        m.put('ф',31);
        m.put('х',32);
        m.put('ц',33);
        m.put('ч',34);
        m.put('ш',35);
        m.put('щ',36);
        m.put('ъ',37);
        m.put('ы',38);
        m.put('ь',39);
        m.put('э',40);
        m.put('ю',41);
        m.put('я',42);
        m.put('ё',43);
        m.put('–',44);
        m.put('…',45);
        m.put(' ',46);
        return Collections.unmodifiableMap(m);
    }
    private DictionaryData() {}
}
