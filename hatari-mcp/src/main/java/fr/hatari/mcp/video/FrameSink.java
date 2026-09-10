package fr.hatari.mcp.video;

import fr.hatari.mcp.Machine;

/** Destination des images d'une capture vidéo (encodeur, fichier, fake de test). */
public interface FrameSink {

    /** Reçoit une image de l'écran émulé. */
    void accept(Machine.Frame frame);

    /** Finalise la sortie ; retourne la taille en octets du fichier produit (0 si inconnue). */
    long close();
}
