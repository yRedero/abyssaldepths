package redero.abyssaldepths.artifact.reprise;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Reprise's change log forgets exactly what fell out of its window, and nothing after it. */
class LevelLogTest {
    @Test
    void forgetsOnlyWhatIsOlder() {
        final LevelLog log = new LevelLog();
        for (long t = 0; t < 100; t++) {
            log.changes.add(new LevelLog.Change(t, t, null, null));
        }
        log.forgetBefore(40);
        assertEquals(60, log.changes.size());
        assertEquals(40, log.changes.peekFirst().time());
        log.forgetBefore(40);
        assertEquals(60, log.changes.size(), "forgetting twice changes nothing");
        log.forgetBefore(1000);
        assertEquals(0, log.changes.size());
    }
}
