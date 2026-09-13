package fr.hatari.mcp.ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Upcalls libretro : environnement, video, audio, entrees. Inertes sauf l'environnement et l'etat joystick. */
final class RetroCallbacks {

    static final int ENV_SET_PIXEL_FORMAT = 10;
    static final int ENV_SET_SUPPORT_NO_GAME = 18;
    static final int PIXEL_FORMAT_XRGB8888 = 1;

    static final int DEVICE_JOYPAD = 1;
    static final int ID_JOYPAD_B = 0, ID_JOYPAD_UP = 4, ID_JOYPAD_DOWN = 5, ID_JOYPAD_LEFT = 6, ID_JOYPAD_RIGHT = 7;

    /**
     * Etat des deux ports joystick tel que le core le lira (index = port libretro).
     * Le core echange les ports 0 et 1 (src/retro/joy_ui.c) : le joystick ST du port 1
     * est lu sur le port libretro 0. Masque = bits Machine.JOY_*.
     */
    private static final int[] joyState = new int[2];

    /** Récepteur des échantillons audio (null = son ignoré). */
    private static volatile fr.hatari.mcp.video.AudioListener audioListener;

    static void setAudioListener(fr.hatari.mcp.video.AudioListener l) { audioListener = l; }

    static void setJoystick(int retroPort, int mask) { joyState[retroPort] = mask; }

    final MemorySegment env, video, audio, audioBatch, inputPoll, inputState;

    RetroCallbacks(Linker linker, Arena arena) throws ReflectiveOperationException {
        MethodHandles.Lookup lk = MethodHandles.lookup();
        env = linker.upcallStub(lk.findStatic(RetroCallbacks.class, "envCb",
                MethodType.methodType(boolean.class, int.class, MemorySegment.class)),
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT, ValueLayout.ADDRESS), arena);
        video = linker.upcallStub(lk.findStatic(RetroCallbacks.class, "videoCb",
                MethodType.methodType(void.class, MemorySegment.class, int.class, int.class, long.class)),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG), arena);
        audio = linker.upcallStub(lk.findStatic(RetroCallbacks.class, "audioCb",
                MethodType.methodType(void.class, short.class, short.class)),
                FunctionDescriptor.ofVoid(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT), arena);
        audioBatch = linker.upcallStub(lk.findStatic(RetroCallbacks.class, "audioBatchCb",
                MethodType.methodType(long.class, MemorySegment.class, long.class)),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), arena);
        inputPoll = linker.upcallStub(lk.findStatic(RetroCallbacks.class, "inputPollCb",
                MethodType.methodType(void.class)), FunctionDescriptor.ofVoid(), arena);
        inputState = linker.upcallStub(lk.findStatic(RetroCallbacks.class, "inputStateCb",
                MethodType.methodType(short.class, int.class, int.class, int.class, int.class)),
                FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), arena);
    }

    static boolean envCb(int cmd, MemorySegment data) {
        return switch (cmd) {
            case ENV_SET_PIXEL_FORMAT -> data.reinterpret(4).get(ValueLayout.JAVA_INT, 0) == PIXEL_FORMAT_XRGB8888;
            case ENV_SET_SUPPORT_NO_GAME -> true;
            default -> false;
        };
    }

    static void videoCb(MemorySegment data, int w, int h, long pitch) {}
    static void audioCb(short l, short r) {}
    static long audioBatchCb(MemorySegment d, long frames) {
        fr.hatari.mcp.video.AudioListener l = audioListener;
        if (l != null && frames > 0) {
            l.samples(d.reinterpret(frames * 2 * Short.BYTES).toArray(ValueLayout.JAVA_SHORT));
        }
        return frames;
    }
    static void inputPollCb() {}
    static short inputStateCb(int port, int dev, int idx, int id) {
        if (dev != DEVICE_JOYPAD || port < 0 || port >= joyState.length) return 0;
        int m = joyState[port];
        return (short) (switch (id) {
            case ID_JOYPAD_UP -> (m & fr.hatari.mcp.Machine.JOY_UP) != 0;
            case ID_JOYPAD_DOWN -> (m & fr.hatari.mcp.Machine.JOY_DOWN) != 0;
            case ID_JOYPAD_LEFT -> (m & fr.hatari.mcp.Machine.JOY_LEFT) != 0;
            case ID_JOYPAD_RIGHT -> (m & fr.hatari.mcp.Machine.JOY_RIGHT) != 0;
            case ID_JOYPAD_B -> (m & fr.hatari.mcp.Machine.JOY_FIRE) != 0;
            default -> false;
        } ? 1 : 0);
    }
}
