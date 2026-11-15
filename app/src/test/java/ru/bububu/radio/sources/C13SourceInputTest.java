package ru.bububu.radio.sources;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class C13SourceInputTest {
    @Test public void bareSiteGetsHttpsWhileExplicitHttpQueryAndPathKeepTheirMeaning()throws Exception{
        SourceInput bare=SourceInput.normalize("  Example.org/Read?key=One&order=2#part  ",false);assertFalse(bare.telegram);assertEquals("https://example.org/Read?key=One&order=2",bare.value);assertEquals(bare.value,bare.fetchUrl);
        for(String url:List.of("http://example.org/a?key=One&key=Two","https://example.org/a?order=2"))assertEquals(url,SourceInput.normalize(url,false).value);
    }
    @Test public void atAndBareTmeShareTheOriginalPublicTelegramIdentity()throws Exception{
        for(String input:List.of("@Example","t.me/Example","t.me/s/Example","https://t.me/Example")){
            SourceInput value=SourceInput.normalize(input,false);assertTrue(input,value.telegram);assertEquals("@example",value.value);assertEquals("https://t.me/s/example",value.fetchUrl);assertEquals("telegram_public:example",TelegramPreviewAdapter.normalizeInput(value.value).sourceKey);
        }
    }
    @Test public void telegramInvitesPostsQueriesAndFragmentsStayRejected()throws Exception{
        for(String input:List.of("t.me/+invite","t.me/joinchat/invite","t.me/c/123/4","t.me/channel/12","t.me/channel?start=1","t.me/channel#part","http://t.me/channel","https://t.me:8443/channel","@bad/name"))assertThrows(input,SourceFailure.class,()->SourceInput.normalize(input,false));
        assertThrows(SourceFailure.class,()->SourceInput.normalize("example.org",true));assertFalse(SourceInput.normalize("t.me.example.org/channel",false).telegram);
    }
    @Test public void normalizerCannotHideCredentialsPrivateTargetsOrNonWebSchemes()throws Exception{
        for(String input:List.of("localhost/","127.0.0.1/","192.168.1.3/","[::1]/","https://u:p@example.org/","u:p@example.org/","ftp://example.org/","javascript:alert(1)","file:///private/path","https:example.org","example.org\\@127.0.0.1/","example.org/a b"))assertThrows(input,SourceFailure.class,()->SourceInput.normalize(input,false));
    }
}
