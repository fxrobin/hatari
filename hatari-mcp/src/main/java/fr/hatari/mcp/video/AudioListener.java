package fr.hatari.mcp.video;

/** Reçoit les échantillons audio produits par le cœur (int16 stéréo entrelacé). */
@FunctionalInterface
public interface AudioListener {
    void samples(short[] interleavedStereo);
}
