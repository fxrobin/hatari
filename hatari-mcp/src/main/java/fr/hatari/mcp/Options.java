package fr.hatari.mcp;

import java.nio.file.Files;
import java.nio.file.Path;

/** Options de ligne de commande du serveur : --core, --tos, --machine, --memsize. */
public record Options(Path core, Path tos, String machine, int memsize) {

    public static Options parse(String[] args) {
        Path core = null;
        Path tos = Path.of(System.getProperty("user.home"), ".hatari", "tos.img");
        String machine = "st";
        int memsize = 1;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--core" -> core = Path.of(args[++i]);
                case "--tos" -> tos = Path.of(args[++i]);
                case "--machine" -> machine = args[++i];
                case "--memsize" -> memsize = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("option inconnue : " + args[i]);
            }
        }
        if (core == null) core = defaultCore();
        return new Options(core, tos, machine, memsize);
    }

    /** libretro-hatari.so à côté du jar, sinon ../build/src/libretro-hatari.so relatif au projet. */
    static Path defaultCore() {
        Path beside = Path.of("libretro-hatari.so").toAbsolutePath();
        if (Files.exists(beside)) return beside;
        return Path.of("..", "build", "src", "libretro-hatari.so").toAbsolutePath().normalize();
    }

    /** Arguments passés à hatari_init_args. */
    public String[] hatariArgs() {
        return new String[] {
            "hatari", "--tos", tos.toString(), "--machine", machine,
            "--memsize", Integer.toString(memsize), "--sound", "off", "--confirm-quit", "false"
        };
    }
}
