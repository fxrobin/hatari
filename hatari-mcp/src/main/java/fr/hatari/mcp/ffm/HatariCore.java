package fr.hatari.mcp.ffm;

import fr.hatari.mcp.Machine;
import fr.hatari.mcp.Options;

/**
 * Implémentation {@link Machine} adossée au cœur libretro Hatari via FFM.
 *
 * <p>Squelette pour la Task 6 : les liaisons natives (Task 7) ne sont pas
 * encore écrites.
 */
public final class HatariCore {

    private HatariCore() {}

    public static Machine open(Options options) {
        throw new UnsupportedOperationException("Task 7");
    }
}
