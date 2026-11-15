package ru.bububu.radio;

import android.content.Context;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import okhttp3.Headers;
import org.junit.Test;
import org.junit.runner.RunWith;
import ru.bububu.radio.sources.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Actual Android controls on the main thread; no Activity, HTTP, microphone or device setup. */
@RunWith(AndroidJUnit4.class)
public class SourcesFailureUiTest {
    private static final String HOST="synthetic-private-host.test",URL="https://synthetic-private-host.test/private-path",RAW="RAW_HEADER_SENTINEL",LABEL="Повторить";
    private static final class Clock implements SourceClock {
        long mono=1000,wall=1_700_000_000_000L;
        public long monotonicMillis(){return mono;}public long wallMillis(){return wall;}
        void advance(long millis){mono+=millis;wall+=millis;}
    }
    private static SourceFailureInfo failure(int status,List<String> values,Clock clock) {
        Headers.Builder headers=new Headers.Builder().add("Location",URL).add("X-Diagnostic",RAW);for(String value:values)headers.add("Retry-After",value);
        RetryAfterParser.Result retry=RetryAfterParser.parse(values,clock.wall,clock.mono);
        return SourceFailureInfo.from(new HttpStatusFailure(status,headers.build(),HOST,new RequestScope(1,1,clock,90_000),retry),clock);
    }
    private static void safeLabel(Button button) {String text=button.getText().toString();assertFalse(text.contains(URL));assertFalse(text.contains(HOST));assertFalse(text.contains(RAW));assertFalse(text.contains("Retry-After"));}
    @Test public void finiteCooldownDisablesActualButtonUntilManualRefreshAndNeverRequestsAutomatically() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();Clock clock=new Clock();SourceFailureInfo failure=failure(503,List.of("5"),clock);
            Button button=new Button(context);AtomicInteger manualRequests=new AtomicInteger();button.setOnClickListener(view->manualRequests.incrementAndGet());
            SourceRetryUi ui=SourceRetryUi.bind(button,LABEL,()->failure,clock);
            assertFalse(button.isAttachedToWindow());assertFalse(button.isEnabled());assertTrue(button.getText().toString().contains("5 сек."));safeLabel(button);assertEquals(0,manualRequests.get());
            clock.advance(4999);ui.refresh();assertFalse(button.isEnabled());assertTrue(button.getText().toString().contains("1 сек."));safeLabel(button);assertEquals(0,manualRequests.get());
            clock.advance(1);assertFalse(button.isEnabled());assertEquals(0,manualRequests.get());
            ui.refresh();assertTrue(button.isEnabled());assertEquals(LABEL,button.getText().toString());assertEquals(0,manualRequests.get());
            button.performClick();assertEquals(1,manualRequests.get());
        });
    }
    @Test public void excessiveCooldownKeepsActualButtonDisabledAndExplainsUnrepresentableTimeSafely() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();Clock clock=new Clock();SourceFailureInfo failure=failure(429,List.of("9223372036854775808"),clock);Button button=new Button(context);AtomicInteger requests=new AtomicInteger();button.setOnClickListener(view->requests.incrementAndGet());
            SourceRetryUi ui=SourceRetryUi.bind(button,LABEL,()->failure,clock);assertFalse(button.isEnabled());assertTrue(button.getText().toString().contains("невозможно безопасно представить"));safeLabel(button);
            clock.advance(90_000);ui.refresh();assertFalse(button.isEnabled());safeLabel(button);assertEquals(0,requests.get());
        });
    }
    @Test public void invalidAbsentAndExpiredRetryAfterLeaveManualButtonEnabledWithoutLeakingHeaders() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();Clock clock=new Clock();
            for(List<String> values:List.of(List.of("invalid"),List.<String>of(),List.of("0"))) {
                SourceFailureInfo failure=failure(503,values,clock);Button button=new Button(context);AtomicInteger requests=new AtomicInteger();button.setOnClickListener(view->requests.incrementAndGet());SourceRetryUi.bind(button,LABEL,()->failure,clock);
                assertTrue(button.isEnabled());assertEquals(LABEL,button.getText().toString());safeLabel(button);assertEquals(0,requests.get());
            }
        });
    }
}
