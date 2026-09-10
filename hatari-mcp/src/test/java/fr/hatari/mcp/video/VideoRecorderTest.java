package fr.hatari.mcp.video;

import fr.hatari.mcp.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VideoRecorderTest {

    /** Sink de test : compte les images, se souvient de la fermeture. */
    static final class CountingSink implements FrameSink {
        int frames; boolean closed;
        @Override public void accept(Machine.Frame f) { frames++; }
        @Override public long close() { closed = true; return 1234; }
    }

    @Test
    void inactiveRecorderDelegatesRunUnchanged() {
        FakeMachine fake = new FakeMachine();
        CapturingMachine m = new CapturingMachine(fake);
        assertEquals(10, m.run(10).framesDone());
        assertEquals(List.of("run 10"), fake.calls);
    }

    @Test
    void activeRecorderSamplesEveryNFramesAcrossRuns() {
        FakeMachine fake = new FakeMachine();
        CapturingMachine m = new CapturingMachine(fake);
        CountingSink sink = new CountingSink();
        m.recorder().arm(sink, "/tmp/x.mp4", 2, 25);
        assertEquals(5, m.run(5).framesDone());   // 2,2,1 → 2 images, 1 trame en attente
        assertEquals(2, sink.frames);
        assertEquals(1, m.run(1).framesDone());   // complète le pas → 3e image
        assertEquals(3, sink.frames);
        assertEquals(List.of("run 2", "run 2", "run 1", "run 1"), fake.calls);
        Map<String, Object> st = m.recorder().status();
        assertEquals(true, st.get("active"));
        assertEquals(3L, st.get("frames"));
        assertEquals(6L, st.get("emulated_frames"));
    }

    @Test
    void breakpointStopEndsRunEarlyButKeepsSampling() {
        FakeMachine fake = new FakeMachine();
        CapturingMachine m = new CapturingMachine(fake);
        CountingSink sink = new CountingSink();
        m.recorder().arm(sink, "/tmp/x.mp4", 2, 25);
        fake.nextStop = Machine.StopReason.BREAKPOINT;
        Machine.RunResult r = m.run(10);
        assertEquals(Machine.StopReason.BREAKPOINT, r.reason());
        assertEquals(0, r.framesDone());
        assertEquals(0, sink.frames);
    }

    @Test
    void stopClosesSinkAndIsIdempotent() {
        FakeMachine fake = new FakeMachine();
        CapturingMachine m = new CapturingMachine(fake);
        CountingSink sink = new CountingSink();
        m.recorder().arm(sink, "/tmp/x.mp4", 2, 25);
        m.run(4);
        Map<String, Object> out = m.recorder().stop();
        assertTrue(sink.closed);
        assertEquals(false, out.get("active"));
        assertEquals(1234L, out.get("bytes"));
        assertEquals(2L, out.get("frames"));
        assertEquals(out, m.recorder().stop());
        assertFalse(m.recorder().active());
        assertEquals(List.of("run 2", "run 2"), fake.calls);
    }

    @Test
    void armTwiceIsRefused() {
        CapturingMachine m = new CapturingMachine(new FakeMachine());
        m.recorder().arm(new CountingSink(), "/tmp/a.mp4", 2, 25);
        assertThrows(IllegalStateException.class,
                () -> m.recorder().arm(new CountingSink(), "/tmp/b.mp4", 2, 25));
    }

    @Test
    void statusWithoutCaptureIsAnError() {
        CapturingMachine m = new CapturingMachine(new FakeMachine());
        assertThrows(IllegalStateException.class, () -> m.recorder().status());
        assertThrows(IllegalStateException.class, () -> m.recorder().stop());
    }
}
