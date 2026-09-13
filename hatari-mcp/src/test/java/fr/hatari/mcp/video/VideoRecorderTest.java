package fr.hatari.mcp.video;

import fr.hatari.mcp.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VideoRecorderTest {

    /** Sink de test : compte les images, se souvient de la fermeture. */
    static final class CountingSink implements FrameSink {
        int frames, audioSamples; boolean closed; double scale;
        @Override public void accept(Machine.Frame f) { frames++; }
        @Override public void audio(short[] pcm) { audioSamples += pcm.length / 2; }
        @Override public long close(double videoTimeScale) { closed = true; scale = videoTimeScale; return 1234; }
    }

    /** Fake à 50 Hz exacts : 160256 cycles par trame, CPU 8012800 Hz. */
    static final long CPU_HZ_FAKE = 160256L * 50;

    @Test
    void inactiveRecorderDelegatesRunUnchanged() {
        FakeMachine fake = new FakeMachine();
        CapturingMachine m = new CapturingMachine(fake, CPU_HZ_FAKE);
        assertEquals(10, m.run(10).framesDone());
        assertEquals(List.of("run 10"), fake.calls);
    }

    @Test
    void activeRecorderSamplesEveryNFramesAcrossRuns() {
        FakeMachine fake = new FakeMachine();
        CapturingMachine m = new CapturingMachine(fake, CPU_HZ_FAKE);
        CountingSink sink = new CountingSink();
        m.recorder().arm(sink, "/tmp/x.mp4", 2, 44100, 0);
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
        CapturingMachine m = new CapturingMachine(fake, CPU_HZ_FAKE);
        CountingSink sink = new CountingSink();
        m.recorder().arm(sink, "/tmp/x.mp4", 2, 44100, 0);
        fake.nextStop = Machine.StopReason.BREAKPOINT;
        Machine.RunResult r = m.run(10);
        assertEquals(Machine.StopReason.BREAKPOINT, r.reason());
        assertEquals(0, r.framesDone());
        assertEquals(0, sink.frames);
    }

    @Test
    void stopClosesSinkAndIsIdempotent() {
        FakeMachine fake = new FakeMachine();
        CapturingMachine m = new CapturingMachine(fake, CPU_HZ_FAKE);
        CountingSink sink = new CountingSink();
        m.recorder().arm(sink, "/tmp/x.mp4", 2, 44100, 0);
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
        m.recorder().arm(new CountingSink(), "/tmp/a.mp4", 2, 44100, 0);
        assertThrows(IllegalStateException.class,
                () -> m.recorder().arm(new CountingSink(), "/tmp/b.mp4", 2, 44100, 0));
    }

    @Test
    void statusWithoutCaptureIsAnError() {
        CapturingMachine m = new CapturingMachine(new FakeMachine());
        assertThrows(IllegalStateException.class, () -> m.recorder().status());
        assertThrows(IllegalStateException.class, () -> m.recorder().stop());
    }

    @Test
    void audioIsRelayedOnlyWhileActive() {
        FakeMachine fake = new FakeMachine();
        CapturingMachine m = new CapturingMachine(fake, CPU_HZ_FAKE);
        assertNotNull(fake.audioListener, "le décorateur installe l'écouteur audio sur la machine");
        CountingSink sink = new CountingSink();
        fake.audioListener.samples(new short[882 * 2]);      // avant armement : ignoré
        m.recorder().arm(sink, "/tmp/x.mp4", 2, 44100, 0);
        fake.audioListener.samples(new short[882 * 2]);
        fake.audioListener.samples(new short[882 * 2]);
        assertEquals(1764, sink.audioSamples);
        assertEquals(1764L, m.recorder().status().get("audio_samples"));
        assertEquals(true, m.recorder().status().get("audio"));
        m.recorder().stop();
        fake.audioListener.samples(new short[882 * 2]);      // après arrêt : ignoré
        assertEquals(1764, sink.audioSamples);
    }

    @Test
    void mutedCaptureDropsAudio() {
        FakeMachine fake = new FakeMachine();
        CapturingMachine m = new CapturingMachine(fake, CPU_HZ_FAKE);
        CountingSink sink = new CountingSink();
        m.recorder().arm(sink, "/tmp/x.mp4", 2, 0, 0);
        fake.audioListener.samples(new short[100]);
        assertEquals(0, sink.audioSamples);
        assertEquals(false, m.recorder().status().get("audio"));
    }

    @Test
    void videoTimeScaleFollowsCpuCycles() {
        FakeMachine fake = new FakeMachine();
        // CPU deux fois plus lent que la cadence nominale : 100 trames = 4 s réelles, 2 s nominales
        CapturingMachine m = new CapturingMachine(fake, CPU_HZ_FAKE / 2);
        CountingSink sink = new CountingSink();
        m.recorder().arm(sink, "/tmp/x.mp4", 2, 44100, fake.cycleCount());
        m.run(100);
        Map<String, Object> out = m.recorder().stop();
        assertEquals(2.0, sink.scale, 1e-6);
        assertEquals(2.0, out.get("video_time_scale"));
        assertEquals(4.0, out.get("seconds"));
    }

    @Test
    void videoTimeScaleIsOneAtNominalRate() {
        FakeMachine fake = new FakeMachine();
        CapturingMachine m = new CapturingMachine(fake, CPU_HZ_FAKE);
        CountingSink sink = new CountingSink();
        m.recorder().arm(sink, "/tmp/x.mp4", 2, 44100, fake.cycleCount());
        m.run(100);
        m.recorder().stop();
        assertEquals(1.0, sink.scale, 1e-6);
    }
}
