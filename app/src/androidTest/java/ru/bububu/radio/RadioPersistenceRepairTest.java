package ru.bububu.radio;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import ru.bububu.radio.core.*;

/** Actual Android AtomicFile/inert LocalData; failures are deterministic wire injections. No TTS. */
@RunWith(AndroidJUnit4.class)
public class RadioPersistenceRepairTest {
    private Path root;
    private LocalData local;
    @Before public void prepare()throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        root=Files.createTempDirectory(target.getCacheDir().toPath(),"radio-authority-test08-");
        local=new LocalData(new ContextWrapper(target){@Override public File getFilesDir(){return root.toFile();}});
    }
    @After public void cleanup()throws Exception {
        if(root!=null)try(java.util.stream.Stream<Path> entries=Files.walk(root)){
            for(Path path:entries.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.delete(path);
        }
    }
    private PlayerController.SavedState state(boolean started,int sentence) {
        PlayerController.SavedProgress progress=new PlayerController.SavedProgress("s","a","v",sentence,false,Collections.emptyList(),true,started);
        return new PlayerController.SavedState("s",Collections.singletonMap("s","a"),Collections.singletonMap(PlayerController.progressKey("s","a","v"),progress));
    }
    private PlayerController.SavedProgress progress(PlayerController.SavedState state){return state.progress.get(PlayerController.progressKey("s","a","v"));}
    @Test public void actualPublishedJsonThenWireExceptionRevokesOldAuthorityAndRestartAdoptsIt()throws Exception {
        final boolean[] fault={false};
        RadioStateAuthority authority=new RadioStateAuthority(new PlayerController.StateStore(){
            public PlayerController.SavedState load(){return local.load();}
            public void save(PlayerController.SavedState state){local.save(state);if(fault[0])throw new IllegalStateException("Injected post-publication failure");}
        },s->s,s->s);
        assertNull(authority.load());authority.save(state(false,0));byte[] before=Files.readAllBytes(root.resolve("player-state-v1.json"));
        fault[0]=true;assertThrows(IllegalStateException.class,()->authority.save(state(true,1)));assertFalse(authority.known());assertThrows(IllegalStateException.class,authority::persisted);
        byte[] published=Files.readAllBytes(root.resolve("player-state-v1.json"));assertFalse(Arrays.equals(before,published));assertTrue(progress(local.load()).started);
        RadioStateAuthority restart=new RadioStateAuthority(local,s->s,s->s);assertTrue(progress(restart.load()).started);assertEquals(1,progress(restart.persisted()).sentenceIndex);
        assertArrayEquals("Inert restart must not rewrite published files",published,Files.readAllBytes(root.resolve("player-state-v1.json")));
    }
    @Test public void prepublicationFailurePreservesBytesAndVerifiedCheckpointRestoresAuthority()throws Exception {
        final boolean[] fault={false};RadioStateAuthority authority=new RadioStateAuthority(new PlayerController.StateStore(){
            public PlayerController.SavedState load(){return local.load();}
            public void save(PlayerController.SavedState state){if(fault[0])throw new IllegalStateException("Injected before publication");local.save(state);}
        },s->s,s->s);
        authority.load();authority.save(state(false,0));byte[] before=Files.readAllBytes(root.resolve("player-state-v1.json"));long epoch=authority.epoch();
        fault[0]=true;assertThrows(IllegalStateException.class,()->authority.save(state(true,1)));assertFalse(authority.known());assertTrue(authority.epoch()>epoch);assertArrayEquals(before,Files.readAllBytes(root.resolve("player-state-v1.json")));
        fault[0]=false;authority.save(state(true,1));assertTrue(authority.known());assertTrue(progress(authority.persisted()).started);assertEquals(1,progress(local.load()).sentenceIndex);
    }
}
