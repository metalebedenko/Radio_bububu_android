package ru.bububu.radio;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.system.Os;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import ru.bububu.radio.sources.*;
import static org.junit.Assert.*;

/** ONE real preview only with an explicit runner argument. No connect/text fetch/fixture/reset/audio. */
@RunWith(AndroidJUnit4.class)
public final class DwNetworkDiagnosticsNativeTest {
    @Test public void oneExplicitDwPreviewRetainsSafeDiagnosticsWithoutConnecting()throws Exception {
        assertEquals("New root/QA hostname opt-in required before any Activity or network action","ONE_EXACT_DW_HOSTNAME_PREVIEW_V25",
                InstrumentationRegistry.getArguments().getString("dw_hostname_one"));
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        assertEquals("Actual installed diagnostic25 APK required before Activity or network action",25,
                context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionCode);
        AtomicReference<RadioActivity> activity=new AtomicReference<>();
        try(ActivityScenario<RadioActivity> scenario=ActivityScenario.launch(RadioActivity.class)) {
            scenario.onActivity(activity::set);long until=SystemClock.elapsedRealtime()+10000;
            while(main(instrumentation,()->activity.get().serviceForNotes()==null)&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);
            RadioService service=main(instrumentation,()->activity.get().serviceForNotes());assertNotNull(service);
            SourceCoordinator coordinator=main(instrumentation,service::sources);assertNotNull(coordinator);
            assertTrue(field(coordinator,"transport") instanceof SafeHttpTransport);
            assertFalse(main(instrumentation,()->service.player().snapshot().playing));assertFalse(main(instrumentation,coordinator::batchRunning));
            assertTrue(main(instrumentation,()->((Map<?,?>)field(coordinator,"operations")).isEmpty()));
            String before=main(instrumentation,()->bookHash(service));int sources=main(instrumentation,()->service.notesBook().sources().size());
            File directory=Files.createTempDirectory(context.getCacheDir().toPath(),"qa-dw-hostname25-").toFile();Os.chmod(directory.getPath(),0700);
            File target=new File(directory,"dw-hostname-safe.json");SourceClock clock=(SourceClock)field(coordinator,"clock");
            DwNetworkDiagnostics diagnostic=new DwNetworkDiagnostics("dw-"+Long.toHexString(SystemClock.elapsedRealtimeNanos()),clock,bytes->{
                if(bytes.length>DwNetworkDiagnostics.MAX_BYTES)throw new java.io.IOException("DIAGNOSTIC_BOUND");
                File partial=new File(directory,"dw-hostname-safe.json.partial");if(!partial.createNewFile()||target.exists())throw new java.io.IOException("DIAGNOSTIC_EXISTS");
                try(FileOutputStream stream=new FileOutputStream(partial)){stream.write(bytes);stream.getFD().sync();}
                try{Os.chmod(partial.getPath(),0400);}catch(android.system.ErrnoException failure){throw new java.io.IOException("DIAGNOSTIC_MODE");}
                Files.move(partial.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
            });
            AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> result=new AtomicReference<>();CountDownLatch complete=new CountDownLatch(1);
            AtomicReference<RequestScope> owned=new AtomicReference<>();
            AutoCloseable arm=main(instrumentation,()->coordinator.armDwDiagnosticsForTest(diagnostic));
            try {
                main(instrumentation,()->{coordinator.preview(DwNetworkDiagnostics.INPUT,false,"DW на русском",value->{result.set(value);complete.countDown();});
                    owned.set((RequestScope)((Map<?,?>)field(coordinator,"operations")).get("preview"));return null;});
                assertTrue("One bounded preview must return; no retry",complete.await(40,TimeUnit.SECONDS));instrumentation.waitForIdleSync();
                assertNotNull(result.get());assertEquals("COMMITTED",diagnostic.captureState());assertTrue(target.isFile());assertTrue(target.length()<=DwNetworkDiagnostics.MAX_BYTES);
                JSONObject safe=new JSONObject(new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8));assertEquals("dw-russian",safe.getString("source_id"));
                assertTrue(safe.getJSONArray("events").length()<=DwNetworkDiagnostics.MAX_EVENTS);
                assertFalse("Truncated capture cannot establish a stage",safe.getBoolean("truncated"));
                assertTrue("Local elapsed clock must be valid",safe.getBoolean("clock_valid"));
                assertEquals(before,main(instrumentation,()->bookHash(service)));assertEquals(sources,(int)main(instrumentation,()->service.notesBook().sources().size()));
                assertFalse(main(instrumentation,()->service.player().snapshot().playing));
                System.out.println("DW_DIAGNOSTIC safe_file="+directory.getName()+"/dw-hostname-safe.json; capture="+diagnostic.captureState()+"; worker_result="+(result.get().successful()?"SUCCESS":"FAILURE"));
            } finally {
                arm.close();if(result.get()==null&&owned.get()!=null)owned.get().cancel();
                java.util.concurrent.ThreadPoolExecutor workers=(java.util.concurrent.ThreadPoolExecutor)field(coordinator,"workers");
                long drain=SystemClock.elapsedRealtime()+10000;
                while((workers.getActiveCount()!=0||!workers.getQueue().isEmpty())&&SystemClock.elapsedRealtime()<drain)SystemClock.sleep(50);
                assertEquals("Owned worker must drain before test exit",0,workers.getActiveCount());assertTrue(workers.getQueue().isEmpty());
            }
        }
    }
    @Test public void oneExplicitDwSessionSummaryRetainsSanitizedFactsWithoutConnecting()throws Exception {
        assertEquals("New root/QA hostname opt-in required before any Activity or network action","ONE_EXACT_DW_SESSION_SUMMARY_PREVIEW_V27",
                InstrumentationRegistry.getArguments().getString("dw_session_one"));
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        assertEquals("Actual installed diagnostic27 APK required before Activity or network action",27,
                context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionCode);
        AtomicReference<RadioActivity> activity=new AtomicReference<>();
        try(ActivityScenario<RadioActivity> scenario=ActivityScenario.launch(RadioActivity.class)) {
            scenario.onActivity(activity::set);long until=SystemClock.elapsedRealtime()+10000;
            while(main(instrumentation,()->activity.get().serviceForNotes()==null)&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);
            RadioService service=main(instrumentation,()->activity.get().serviceForNotes());assertNotNull(service);
            SourceCoordinator coordinator=main(instrumentation,service::sources);assertNotNull(coordinator);
            assertTrue(field(coordinator,"transport") instanceof SafeHttpTransport);
            assertFalse(main(instrumentation,()->service.player().snapshot().playing));assertFalse(main(instrumentation,coordinator::batchRunning));
            assertTrue(main(instrumentation,()->((Map<?,?>)field(coordinator,"operations")).isEmpty()));
            String before=main(instrumentation,()->bookHash(service));int sources=main(instrumentation,()->service.notesBook().sources().size());
            File directory=Files.createTempDirectory(context.getCacheDir().toPath(),"qa-dw-session27-").toFile();Os.chmod(directory.getPath(),0700);
            File target=new File(directory,"dw-session-safe.json");SourceClock clock=(SourceClock)field(coordinator,"clock");
            DwNetworkDiagnostics diagnostic=DwNetworkDiagnostics.withSessionSummary("dw-"+Long.toHexString(SystemClock.elapsedRealtimeNanos()),clock,bytes->{
                if(bytes.length>DwNetworkDiagnostics.MAX_BYTES)throw new java.io.IOException("DIAGNOSTIC_BOUND");
                File partial=new File(directory,"dw-session-safe.json.partial");if(!partial.createNewFile()||target.exists())throw new java.io.IOException("DIAGNOSTIC_EXISTS");
                try(FileOutputStream stream=new FileOutputStream(partial)){stream.write(bytes);stream.getFD().sync();}
                try{Os.chmod(partial.getPath(),0400);}catch(android.system.ErrnoException failure){throw new java.io.IOException("DIAGNOSTIC_MODE");}
                Files.move(partial.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
            });
            AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> result=new AtomicReference<>();CountDownLatch complete=new CountDownLatch(1);
            AtomicReference<RequestScope> owned=new AtomicReference<>();
            AutoCloseable arm=main(instrumentation,()->coordinator.armDwDiagnosticsForTest(diagnostic));
            try {
                main(instrumentation,()->{coordinator.preview(DwNetworkDiagnostics.INPUT,false,"DW на русском",value->{result.set(value);complete.countDown();});
                    owned.set((RequestScope)((Map<?,?>)field(coordinator,"operations")).get("preview"));return null;});
                assertTrue("One bounded preview must return; no retry",complete.await(40,TimeUnit.SECONDS));instrumentation.waitForIdleSync();
                assertNotNull(result.get());assertEquals("COMMITTED",diagnostic.captureState());assertTrue(target.isFile());assertTrue(target.length()<=DwNetworkDiagnostics.MAX_BYTES);
                JSONObject safe=new JSONObject(new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8));assertEquals("dw-russian",safe.getString("source_id"));
                assertTrue(safe.getJSONArray("events").length()<=DwNetworkDiagnostics.MAX_EVENTS);
                assertFalse("Truncated capture cannot establish a stage",safe.getBoolean("truncated"));
                assertTrue("Local elapsed clock must be valid",safe.getBoolean("clock_valid"));
                JSONObject summary=safe.getJSONObject("session_summary");String summaryState=summary.getString("status");
                assertTrue("Typed summary status required",java.util.Arrays.asList("NOT_OBSERVED","AVAILABLE","UNAVAILABLE").contains(summaryState));
                assertTrue(summary.getInt("chain_length")<=8);assertTrue(summary.getInt("san_entries")<=32);
                assertTrue(summary.getInt("dns_entries")+summary.getInt("ip_entries")<=summary.getInt("san_entries"));
                assertEquals(before,main(instrumentation,()->bookHash(service)));assertEquals(sources,(int)main(instrumentation,()->service.notesBook().sources().size()));
                assertFalse(main(instrumentation,()->service.player().snapshot().playing));
                System.out.println("DW_DIAGNOSTIC safe_file="+directory.getName()+"/dw-session-safe.json; capture="+diagnostic.captureState()+"; worker_result="+(result.get().successful()?"SUCCESS":"FAILURE"));
            } finally {
                arm.close();if(result.get()==null&&owned.get()!=null)owned.get().cancel();
                java.util.concurrent.ThreadPoolExecutor workers=(java.util.concurrent.ThreadPoolExecutor)field(coordinator,"workers");
                long drain=SystemClock.elapsedRealtime()+10000;
                while((workers.getActiveCount()!=0||!workers.getQueue().isEmpty())&&SystemClock.elapsedRealtime()<drain)SystemClock.sleep(50);
                assertEquals("Owned worker must drain before test exit",0,workers.getActiveCount());assertTrue(workers.getQueue().isEmpty());
            }
        }
    }
    private static Object field(Object owner,String name){try{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(Exception failure){throw new AssertionError("FIELD_ACCESS");}}
    private static String bookHash(RadioService service){try{byte[] bytes=service.noteStorage().load();return bytes==null?"absent":hex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception error){throw new AssertionError("BOOK_HASH");}}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
    private static <T>T main(Instrumentation instrumentation,Supplier<T> action){AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        instrumentation.runOnMainSync(()->{try{result.set(action.get());}catch(Throwable failure){error.set(failure);}});if(error.get()!=null)throw new AssertionError("MAIN_ACTION",error.get());return result.get();}
}
