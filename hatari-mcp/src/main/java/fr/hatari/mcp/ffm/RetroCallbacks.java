package fr.hatari.mcp.ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Upcalls libretro : environnement, video, audio, entrees. Tous inertes sauf l'environnement. */
final class RetroCallbacks {

    static final int ENV_SET_PIXEL_FORMAT = 10;
    static final int ENV_SET_SUPPORT_NO_GAME = 18;
    static final int PIXEL_FORMAT_XRGB8888 = 1;

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
    static long audioBatchCb(MemorySegment d, long frames) { return frames; }
    static void inputPollCb() {}
    static short inputStateCb(int port, int dev, int idx, int id) { return 0; }
}
