package fr.hatari.mcp.video;

import fr.hatari.mcp.Machine;

/** Destination des images et du son d'une capture vidéo (encodeur, fake de test). */
public interface FrameSink {

    /** Reçoit une image de l'écran émulé. */
    void accept(Machine.Frame frame);

    /** Reçoit des échantillons audio int16 stéréo entrelacés (optionnel). */
    default void audio(short[] interleavedStereo) {}

    /**
     * Finalise la sortie ; retourne la taille en octets du fichier produit (0 si inconnue).
     *
     * @param videoTimeScale facteur à appliquer aux horodatages vidéo pour retrouver le
     *                       temps réel émulé (1.0 si la cadence nominale était exacte)
     */
    long close(double videoTimeScale);
}
