package ru.bububu.radio.models;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;

public class BundledDefaultsTest {
    private static final class Prefs implements BundledDefaults.Store {
        Boolean voice, notes, diskVoice, diskNotes;
        boolean fail, marker;
        int writes;
        public Boolean voice() { return voice; }
        public Boolean notes() { return notes; }
        public boolean commit(boolean v, boolean n) {
            writes++;voice = v;notes = n; // Android may update memory even when disk commit fails.
            if (fail) return false;
            diskVoice = v;diskNotes = n;marker = true;return true;
        }
        Prefs restart() { Prefs p = new Prefs();p.voice = p.diskVoice = diskVoice;p.notes = p.diskNotes = diskNotes;return p; }
    }
    private static final class Active implements BundledDefaults.Selection {
        boolean voice, notes, unsafe, failApply;
        int calls;
        public void check(boolean v, boolean n) throws IOException { if (unsafe) throw new IOException("recording"); }
        public void apply(boolean v, boolean n) throws IOException {
            calls++;if (failApply) throw new IOException("stop unconfirmed");voice = v;notes = n;
        }
    }
    private void fails(Prefs p, Active a) throws IOException {
        try { BundledDefaults.finish(p, a);fail("No setup success"); } catch (IOException expected) {}
    }
    @Test public void freshPairIsConfirmedBeforeBothCachesAreAdopted() throws Exception {
        Prefs p = new Prefs();Active a = new Active();BundledDefaults.finish(p, a);
        assertEquals(Boolean.TRUE, p.diskVoice);assertEquals(Boolean.TRUE, p.diskNotes);assertTrue(p.marker);assertTrue(a.voice && a.notes);assertEquals(1, a.calls);
    }
    @Test public void failedCommitDoesNotAdoptAndRetryCannotTurnFailureIntoExplicitSystemChoice() throws Exception {
        Prefs p = new Prefs();Active a = new Active();p.fail = true;fails(p, a);
        assertEquals(Boolean.TRUE, p.voice);assertEquals(Boolean.TRUE, p.notes);assertNull(p.diskNotes);assertFalse(p.marker);assertEquals(0, a.calls);
        p.fail = false;BundledDefaults.finish(p, a);
        assertTrue(a.voice && a.notes);assertEquals(Boolean.TRUE, p.diskNotes);assertEquals(2, p.writes);
    }
    @Test public void restartAfterFailedCommitStillDefaultsBoth() throws Exception {
        Prefs p = new Prefs();p.fail = true;fails(p, new Active());p = p.restart();Active a = new Active();
        BundledDefaults.finish(p, a);assertTrue(a.voice && a.notes);assertEquals(Boolean.TRUE, p.diskNotes);
    }
    @Test public void explicitSystemSelectionsSurviveSetupAndRepeatedCompletion() throws Exception {
        Prefs p = new Prefs();p.voice = false;p.notes = false;Active a = new Active();
        BundledDefaults.finish(p, a);BundledDefaults.finish(p, a);
        assertFalse(a.voice || a.notes);assertEquals(Boolean.FALSE, p.diskVoice);assertEquals(Boolean.FALSE, p.diskNotes);
    }
    @Test public void mixedExistingChoiceDefaultsOnlyMissingKey() throws Exception {
        Prefs p = new Prefs();p.voice = false;Active a = new Active();BundledDefaults.finish(p, a);
        assertFalse(a.voice);assertTrue(a.notes);assertEquals(Boolean.FALSE, p.diskVoice);
    }
    @Test public void explicitSystemChoiceBetweenFailureAndRetryWins() throws Exception {
        Prefs p = new Prefs();Active a = new Active();p.fail = true;fails(p, a);
        p.notes = false;p.fail = false;BundledDefaults.finish(p, a);
        assertTrue(a.voice);assertFalse(a.notes);assertEquals(Boolean.FALSE, p.diskNotes);
    }
    @Test public void adoptionFailureRetriesAdoptionEvenWithBothKeysAndMarkerPresent() throws Exception {
        Prefs p = new Prefs();Active a = new Active();a.failApply = true;fails(p, a);
        assertTrue(p.marker);assertFalse(a.notes);a.failApply = false;BundledDefaults.finish(p, a);
        assertTrue(a.voice && a.notes);assertEquals(2, a.calls);
    }
    @Test public void unsafeRecordingStateWritesNothing() throws Exception {
        Prefs p = new Prefs();Active a = new Active();a.unsafe = true;fails(p, a);
        assertEquals(0, p.writes);assertNull(p.voice);assertNull(p.notes);assertEquals(0, a.calls);
    }
}
