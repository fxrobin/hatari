# Hatari MCP — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Piloter Hatari depuis Claude par un serveur MCP stdio Java qui charge le cœur libretro de Hatari par FFM, avec les outils de debug de l'itération 1 (exécution, mémoire, registres, breakpoints, désassemblage, screenshot, clavier, disquettes).

**Architecture:** Une couche C `src/retro/debug_api.c` ajoute au cœur libretro des exports `hatari_*` (exécution bornée, mémoire, registres, passerelle texte vers le debugger Hatari) grâce à un hook posé dans `DebugUI()` qui remplace le REPL readline par un retour immédiat. Un projet Maven `hatari-mcp/` (Java 25, MCP SDK 2.0.0, stdio, mono-thread) charge `libretro-hatari.so` par FFM et expose les outils, en reprenant le squelette de `~/dev/toje/toje-mcp`.

**Tech Stack:** C99 / CMake / CTest (Hatari), Java 25 + FFM (`java.lang.foreign`), Maven, `io.modelcontextprotocol.sdk:mcp:2.0.0` (Jackson 3), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-08-hatari-mcp-design.md`

## Global Constraints

- Branche git : `mcp` (déjà créée). Tous les commits sur cette branche.
- Code C : style Hatari (`doc/coding.txt`) : TAB, doxygen, `const char Xxx_fileid[]` en tête, pas d'include dans les headers, ASCII seulement dans les sources. Zéro warning (`-Werror` en CI).
- Build Hatari : `build/` existant, configuré avec `-DLIBRETRO_INCLUDE_DIR=<scratchpad>/libretro -DENABLE_LIBRETRO=1`. Le header `libretro.h` vit dans `/tmp/claude-1000/-home-robin-dev-hatari/905df54f-934e-4a37-92fc-32a6968d9776/scratchpad/libretro/libretro.h` ; le copier dans `hatari-mcp/native/include/libretro.h` (Task 1) pour ne plus dépendre du scratchpad.
- Rebuild : `cmake --build build -j$(nproc)`. Tests C : `cd build && ctest -R 'retro|debug-api' -V`.
- Java : JDK 25 Temurin `~/.sdkman/candidates/java/25.0.4-tem` (`export JAVA_HOME=~/.sdkman/candidates/java/25.0.4-tem; export PATH=$JAVA_HOME/bin:$PATH` avant toute commande `mvn`). Niveau de langage 25. Flag JVM `--enable-native-access=ALL-UNNAMED`.
- Package Java : `fr.hatari.mcp`. Licence GPL v2+ (fichier `hatari-mcp/LICENSE` = copie de `gpl.txt`).
- Un seul thread touche le cœur natif : les outils passent par `EmulatorSession` (`synchronized`).
- TOS de test : `~/.hatari/tos.img` (EmuTOS 1.4 1024k). Les tests d'intégration Java sont ignorés (`Assumptions`) si `hatari.core` ou le TOS manque.
- Sorties d'outils : adresses et valeurs en hexadécimal majuscule sans préfixe (`"E00D98"`), comme toje-mcp. Les arguments hex acceptent `$`, `0x` ou rien.
- Commit messages : conventionnels, en anglais, terminés par les deux lignes d'attribution :
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` et
  `Claude-Session: https://claude.ai/code/session_01FTU7a9LtSiamiFc9wxtVVR`.

---

## Carte des fichiers

Côté Hatari (C) :

| Fichier | Rôle |
|---|---|
| `src/debug/debugui.c`, `src/debug/debugui.h` (modif) | hook `DebugUI_SetStopHook` |
| `src/debug/debugcpu.c`, `src/debug/debugcpu.h` (modif) | `DebugCpu_SetSteps(int)` |
| `src/retro/debug_api.h` (nouveau) | prototypes et codes `HATARI_STOP_*` |
| `src/retro/debug_api.c` (nouveau) | tous les exports `hatari_*` |
| `src/retro/CMakeLists.txt` (modif) | ajoute `debug_api.c` à `UiRetro` |
| `tests/retro/test-debug-api.c` (nouveau), `tests/retro/CMakeLists.txt` (modif) | test CTest `debug-api` |
| `hatari-mcp/native/include/libretro.h` (copie) | header libretro versionné |

Côté Java (`hatari-mcp/`) :

| Fichier | Rôle |
|---|---|
| `pom.xml`, `LICENSE`, `README.md` | projet Maven autonome |
| `src/main/java/fr/hatari/mcp/StdioMcpMain.java` | point d'entrée stdio |
| `.../EofSignalingInputStream.java`, `McpJson.java`, `Tools.java`, `Args.java` | repris de toje-mcp (adaptés : hex 24 bits) |
| `.../Fmt.java` | hex, registres 68k, hexdump |
| `.../Machine.java` | interface consommée par les outils |
| `.../ffm/HatariCore.java` | bindings FFM, implémente `Machine` |
| `.../ffm/RetroCallbacks.java` | upcalls libretro |
| `.../EmulatorSession.java` | verrou + accès `Machine` + cycle de vie |
| `.../Options.java` | `--core`, `--tos`, `--machine`, `--memsize` |
| `.../keymap/StScancodes.java` | ASCII / noms → scancodes ST |
| `.../ToolCatalog.java` | assemble les groupes d'outils |
| `.../tools/MachineTools.java` | ping, machine_state, reset, run_frames, run_until_pc, run_to_breakpoint, step |
| `.../tools/MemoryTools.java` | read_memory, write_memory, read_registers, set_register, disassemble |
| `.../tools/DebugTools.java` | breakpoints, watchpoints, load_symbols, debug_command |
| `.../tools/VideoTools.java` | screenshot |
| `.../tools/InputTools.java` | press_key, type_keys |
| `.../tools/DiskTools.java` | mount_disk, boot_disk |
| `src/test/java/fr/hatari/mcp/FakeMachine.java` | fake pour les tests unitaires |
| `src/test/java/fr/hatari/mcp/it/CoreIT.java` | tests d'intégration sur la vraie `.so` |
| `scripts/hatari-mcp.sh`, `.mcp.json` (racine du repo) | lancement |

---

### Task 1 : hook debugger et pas-à-pas programmable (patch Hatari)

**Files:**
- Modify: `src/debug/debugui.h`
- Modify: `src/debug/debugui.c` (fonction `DebugUI()` ligne ~1305)
- Modify: `src/debug/debugcpu.h`
- Modify: `src/debug/debugcpu.c` (près de `DebugCpu_Continue`, ligne ~1180)
- Create: `hatari-mcp/native/include/libretro.h` (copie)

**Interfaces:**
- Produces:
  - `typedef bool (*DebugUI_StopHook)(debug_reason_t reason);`
  - `void DebugUI_SetStopHook(DebugUI_StopHook hook);` — `NULL` pour retirer.
  - `void DebugCpu_SetSteps(int steps);` — arme `steps` instructions avant arrêt (`REASON_CPU_STEPS`).

- [ ] **Step 1 : copier le header libretro dans le repo**

```bash
mkdir -p hatari-mcp/native/include
cp /tmp/claude-1000/-home-robin-dev-hatari/905df54f-934e-4a37-92fc-32a6968d9776/scratchpad/libretro/libretro.h hatari-mcp/native/include/libretro.h
cd build && cmake -DLIBRETRO_INCLUDE_DIR=$PWD/../hatari-mcp/native/include -DENABLE_LIBRETRO:BOOL=1 .. | rg libretro
```

Attendu : `- libretro : found (include dir = '.../hatari-mcp/native/include')`.

- [ ] **Step 2 : déclarer le hook dans `debugui.h`**

Après `extern void DebugUI_Init(void);` ajouter :

```c
/* Hook called instead of the interactive prompt when emulation stops
 * (breakpoint, steps, exception...). If it returns true, DebugUI()
 * returns immediately without entering the readline loop. Used by
 * the libretro debug API to hand control back to the host. */
typedef bool (*DebugUI_StopHook)(debug_reason_t reason);
extern void DebugUI_SetStopHook(DebugUI_StopHook hook);
```

- [ ] **Step 3 : implémenter le hook dans `debugui.c`**

Avant `void DebugUI(debug_reason_t reason)` :

```c
static DebugUI_StopHook stopHook;

/**
 * Set (or clear with NULL) the hook called when emulation stops.
 */
void DebugUI_SetStopHook(DebugUI_StopHook hook)
{
	stopHook = hook;
}
```

Dans `DebugUI()`, juste après les déclarations (`static bool recursing;`) et avant `if (recursing)` :

```c
	if (stopHook && stopHook(reason))
		return;
```

- [ ] **Step 4 : ajouter `DebugCpu_SetSteps`**

`debugcpu.h`, après `extern void DebugCpu_SetDebugging(void);` :

```c
extern void DebugCpu_SetSteps(int steps);
```

`debugcpu.c`, juste avant `static int DebugCpu_Continue(` :

```c
/**
 * Arm single-stepping for given number of instructions, after which
 * DebugUI() is called with REASON_CPU_STEPS. Zero disables stepping.
 * Caller must invoke DebugCpu_SetDebugging() afterwards.
 */
void DebugCpu_SetSteps(int steps)
{
	nCpuSteps = steps > 0 ? steps : 0;
}
```

- [ ] **Step 5 : compiler sans warning et lancer les tests existants**

```bash
cmake --build build -j$(nproc) 2>&1 | rg -i "warning|error" ; cd build && ctest -j$(nproc) | tail -3
```

Attendu : aucune ligne warning/error ; `100% tests passed`.

- [ ] **Step 6 : commit**

```bash
git add src/debug/debugui.h src/debug/debugui.c src/debug/debugcpu.h src/debug/debugcpu.c hatari-mcp/native/include/libretro.h
git commit -m "debug: add DebugUI stop hook and DebugCpu_SetSteps for embedding

Allows an embedding host (libretro debug API) to be notified when the
emulation stops instead of entering the interactive readline prompt.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FTU7a9LtSiamiFc9wxtVVR"
```

---

### Task 2 : `debug_api.c` — init, exécution bornée, compteurs, reset

**Files:**
- Create: `src/retro/debug_api.h`
- Create: `src/retro/debug_api.c`
- Modify: `src/retro/CMakeLists.txt`
- Create: `tests/retro/test-debug-api.c`
- Modify: `tests/retro/CMakeLists.txt`

**Interfaces:**
- Consumes : `DebugUI_SetStopHook`, `DebugCpu_SetDebugging` (Task 1), `retro_run`, `retro_set_*` (cœur libretro existant), `nVBLs` (`video.h`), `CyclesGlobalClockCounter` (`cycles.h`), `Reset_Cold/Reset_Warm` (`reset.h`).
- Produces (toutes `RETRO_API`) :
  - `void hatari_init_args(int argc, const char *const *argv)` — remplace `retro_init`. `argv[0]` doit être un chemin (ex. `"hatari"`). Appeler après tous les `retro_set_*`.
  - `int hatari_run(int max_frames, int *stop_reason, int *frames_done)` — retour 0. `stop_reason` ∈ `HATARI_STOP_NONE(0)`, `HATARI_STOP_BREAKPOINT(1)`, `HATARI_STOP_STEPS(2)`, `HATARI_STOP_EXCEPTION(3)`, `HATARI_STOP_OTHER(4)`.
  - `void hatari_counters(uint32_t *vbl, uint64_t *cycles)`
  - `void hatari_reset(bool cold)`

- [ ] **Step 1 : écrire le test C (échoue : symboles absents)**

`tests/retro/test-debug-api.c` :

```c
/*
 * Test the hatari_* debug API exported by the libretro core.
 * Runs TOS-less (--tos none): the fake TOS loops at $1100.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
#include <libretro.h>
#include <assert.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define FAKE_TOS_LOOP 0x1100

static void *dlh;

static void *sym(const char *name)
{
	void *fun = dlsym(dlh, name);
	if (!fun)
	{
		fprintf(stderr, "Missing symbol '%s': %s\n", name, dlerror());
		exit(EXIT_FAILURE);
	}
	return fun;
}

static bool env_cb(unsigned cmd, void *data)
{
	if (cmd == RETRO_ENVIRONMENT_SET_PIXEL_FORMAT)
		return *(enum retro_pixel_format *)data == RETRO_PIXEL_FORMAT_XRGB8888;
	return cmd == RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME;
}
static void video_cb(const void *d, unsigned w, unsigned h, size_t p) { (void)d; (void)w; (void)h; (void)p; }
static void audio_cb(int16_t l, int16_t r) { (void)l; (void)r; }
static size_t audio_batch_cb(const int16_t *d, size_t n) { (void)d; return n; }
static void input_poll_cb(void) {}
static int16_t input_state_cb(unsigned a, unsigned b, unsigned c, unsigned d) { (void)a; (void)b; (void)c; (void)d; return 0; }

int main(int argc, char *argv[])
{
	if (argc != 2)
	{
		fprintf(stderr, "Usage: %s <libretro-hatari.so>\n", argv[0]);
		return EXIT_FAILURE;
	}
	dlh = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
	if (!dlh)
	{
		fprintf(stderr, "dlopen failed: %s\n", dlerror());
		return EXIT_FAILURE;
	}

	void (*set_env)(retro_environment_t) = sym("retro_set_environment");
	void (*set_video)(retro_video_refresh_t) = sym("retro_set_video_refresh");
	void (*set_audio)(retro_audio_sample_t) = sym("retro_set_audio_sample");
	void (*set_audio_batch)(retro_audio_sample_batch_t) = sym("retro_set_audio_sample_batch");
	void (*set_input_poll)(retro_input_poll_t) = sym("retro_set_input_poll");
	void (*set_input_state)(retro_input_state_t) = sym("retro_set_input_state");
	void (*deinit)(void) = sym("retro_deinit");

	void (*init_args)(int, const char *const *) = sym("hatari_init_args");
	int (*run)(int, int *, int *) = sym("hatari_run");
	void (*counters)(uint32_t *, uint64_t *) = sym("hatari_counters");
	void (*reset)(bool) = sym("hatari_reset");

	set_env(env_cb);
	set_video(video_cb);
	set_audio(audio_cb);
	set_audio_batch(audio_batch_cb);
	set_input_poll(input_poll_cb);
	set_input_state(input_state_cb);

	const char *args[] = { "hatari", "--tos", "none", "--machine", "st", "--memsize", "1", "--sound", "off" };
	init_args(9, args);

	uint32_t vbl0, vbl1;
	uint64_t cyc0, cyc1;
	int reason = -1, done = -1;

	counters(&vbl0, &cyc0);
	assert(run(10, &reason, &done) == 0);
	counters(&vbl1, &cyc1);
	printf("run 10: reason=%d done=%d vbl %u->%u\n", reason, done, vbl0, vbl1);
	assert(reason == 0);
	assert(done == 10);
	assert(vbl1 - vbl0 == 10);
	assert(cyc1 > cyc0);

	reset(true);
	assert(run(1, &reason, &done) == 0);
	assert(done == 1);

	printf("All debug-api tests finished successfully.\n");
	deinit();
	dlclose(dlh);
	return EXIT_SUCCESS;
}
```

`tests/retro/CMakeLists.txt`, ajouter à la fin :

```cmake
add_executable(test-debug-api test-debug-api.c)
target_include_directories(test-debug-api PRIVATE ${LIBRETRO_INCLUDE_DIR})
target_link_libraries(test-debug-api ${CMAKE_DL_LIBS})

add_test(NAME debug-api COMMAND test-debug-api $<TARGET_FILE:retro-hatari>)
set_tests_properties(debug-api PROPERTIES ENVIRONMENT "HATARI_TEST=debug-api")
```

(`HATARI_TEST` empêche `Main_Init` de charger `~/.config/hatari/hatari.cfg`, voir `Main_LoadInitialConfig`.)

- [ ] **Step 2 : lancer le test, vérifier l'échec**

```bash
cmake --build build -j$(nproc) && cd build && ctest -R debug-api -V | tail -5
```

Attendu : `Missing symbol 'hatari_init_args'` et test FAILED.

- [ ] **Step 3 : écrire `debug_api.h`**

```c
/*
  Hatari - debug_api.h

  This file is distributed under the GNU General Public License, version 2
  or at your option any later version. Read the file gpl.txt for details.

  Debug / automation API exported by the libretro core in addition to
  the standard libretro entry points. Used by external hosts (MCP server).
*/
#ifndef HATARI_DEBUG_API_H
#define HATARI_DEBUG_API_H

/* Reasons returned by hatari_run() */
enum
{
	HATARI_STOP_NONE = 0,		/* max_frames reached */
	HATARI_STOP_BREAKPOINT = 1,
	HATARI_STOP_STEPS = 2,
	HATARI_STOP_EXCEPTION = 3,
	HATARI_STOP_OTHER = 4
};

/* Number of 32-bit values written by hatari_regs_get() */
#define HATARI_REGS_COUNT	20
/* Indexes in that array: D0-D7 = 0-7, A0-A7 = 8-15 */
#define HATARI_REG_PC		16
#define HATARI_REG_SR		17
#define HATARI_REG_USP		18
#define HATARI_REG_ISP		19

RETRO_API void hatari_init_args(int argc, const char *const *argv);
RETRO_API int hatari_run(int max_frames, int *stop_reason, int *frames_done);
RETRO_API void hatari_counters(uint32_t *vbl, uint64_t *cycles);
RETRO_API void hatari_reset(bool cold);

RETRO_API int hatari_mem_read(uint32_t addr, uint8_t *buf, size_t len);
RETRO_API int hatari_mem_write(uint32_t addr, const uint8_t *buf, size_t len);
RETRO_API void hatari_regs_get(uint32_t *out);
RETRO_API int hatari_reg_set(const char *name, uint32_t value);
RETRO_API int hatari_step(int steps);

RETRO_API int hatari_dbg_command(const char *cmd, char *out, size_t outlen);
RETRO_API int hatari_disasm(uint32_t addr, int count, char *out, size_t outlen, uint32_t *next_pc);

RETRO_API int hatari_framebuffer(const uint32_t **pixels, int *width, int *height);
RETRO_API void hatari_key(uint8_t scancode, bool press);
RETRO_API int hatari_disk_insert(int drive, const char *path);
RETRO_API int hatari_disk_eject(int drive);

#endif /* HATARI_DEBUG_API_H */
```

(Le header déclare déjà toute l'API des tâches 2 à 5 ; `debug_api.c` grandit tâche par tâche.)

- [ ] **Step 4 : écrire `debug_api.c` (partie Task 2)**

```c
/*
  Hatari - debug_api.c

  This file is distributed under the GNU General Public License, version 2
  or at your option any later version. Read the file gpl.txt for details.

  Debug / automation API on top of the libretro core. The host drives
  emulation with hatari_run() (bounded by frames and by debugger stops)
  and inspects the machine with the other hatari_* functions.
  Everything must be called from the same thread as retro_run().
*/
const char DebugApi_fileid[] = "Hatari debug_api.c";

#include <libretro.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "main.h"
#include "configuration.h"
#include "cycles.h"
#include "debugcpu.h"
#include "debugui.h"
#include "debug_priv.h"
#include "log.h"
#include "m68000.h"
#include "reset.h"
#include "sysdeps.h"
#include "newcpu.h"
#include "video.h"
#include "debug_api.h"

static int stopReason = HATARI_STOP_NONE;


/*-----------------------------------------------------------------------*/
/**
 * Called by DebugUI() instead of the interactive prompt. Records why the
 * emulation stopped and asks the CPU core to return to retro_run().
 */
static bool DebugApi_StopHook(debug_reason_t reason)
{
	switch (reason)
	{
	 case REASON_CPU_BREAKPOINT:	stopReason = HATARI_STOP_BREAKPOINT; break;
	 case REASON_CPU_STEPS:		stopReason = HATARI_STOP_STEPS; break;
	 case REASON_CPU_EXCEPTION:	stopReason = HATARI_STOP_EXCEPTION; break;
	 default:			stopReason = HATARI_STOP_OTHER; break;
	}
	/* same mechanism as Timing_WaitOnVbl() in the libretro core */
	M68000_SetSpecial(SPCFLAG_BRK);
	quit_program = UAE_QUIT;
	/* re-evaluate whether per-instruction debugger checks are still needed */
	DebugCpu_SetDebugging();
	return true;
}


/**
 * Initialize Hatari with command line options instead of retro_init().
 * Must be called after the retro_set_*() callbacks are installed.
 */
void hatari_init_args(int argc, const char *const *argv)
{
	DebugUI_SetStopHook(DebugApi_StopHook);
	Main_Init(argc, (char **)argv);
	DebugUI_Init();
}


/**
 * Run emulation for at most max_frames VBLs, or until the debugger stops
 * it (breakpoint, steps, exception). Always returns 0.
 */
int hatari_run(int max_frames, int *stop_reason, int *frames_done)
{
	int start = nVBLs;

	stopReason = HATARI_STOP_NONE;
	while (stopReason == HATARI_STOP_NONE && nVBLs - start < max_frames)
		retro_run();

	if (stop_reason)
		*stop_reason = stopReason;
	if (frames_done)
		*frames_done = nVBLs - start;
	return 0;
}


/**
 * Current VBL and CPU clock counters.
 */
void hatari_counters(uint32_t *vbl, uint64_t *cycles)
{
	if (vbl)
		*vbl = (uint32_t)nVBLs;
	if (cycles)
		*cycles = CyclesGlobalClockCounter;
}


/**
 * Cold or warm reset.
 */
void hatari_reset(bool cold)
{
	if (cold)
		Reset_Cold();
	else
		Reset_Warm();
}
```

`src/retro/CMakeLists.txt` : remplacer la liste de sources de `UiRetro` par :

```cmake
add_library(UiRetro OBJECT audio.c debug_api.c joy_ui.c gui_event.c keymap.c
                    main_retro.c microphone.c screen.c statusbar.c timing.c)
```

Note : `retro_run` est déclaré par `libretro.h` (`RETRO_API void retro_run(void);`), donc appelable directement.

- [ ] **Step 5 : compiler, lancer le test**

```bash
cmake --build build -j$(nproc) 2>&1 | rg -i "warning|error"; cd build && ctest -R debug-api -V | tail -6
```

Attendu : pas de warning ; `run 10: reason=0 done=10 vbl N->N+10` ; `All debug-api tests finished successfully.` ; test Passed.

Si `hatari_run` boucle sans fin : vérifier que `retro_run()` revient bien à chaque VBL (`Timing_WaitOnVbl` dans `src/retro/timing.c` pose `SPCFLAG_BRK`), et que `nVBLs` est incrémenté par `Video_InterruptHandler_VBL`.

- [ ] **Step 6 : commit**

```bash
git add src/retro/debug_api.h src/retro/debug_api.c src/retro/CMakeLists.txt tests/retro/test-debug-api.c tests/retro/CMakeLists.txt
git commit -m "retro: add hatari_* debug API (init with args, bounded run, counters, reset)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FTU7a9LtSiamiFc9wxtVVR"
```

---

### Task 3 : mémoire, registres, pas-à-pas

**Files:**
- Modify: `src/retro/debug_api.c`
- Modify: `tests/retro/test-debug-api.c`

**Interfaces:**
- Consumes : `STMemory_ReadByte/WriteByte` (`stMemory.h`), `regs`, `MakeSR()`, `m68k_setpc()` (`newcpu.h`), `DebugCpu_GetRegisterAddress`, `DebugCpu_SetSteps` (Task 1).
- Produces :
  - `int hatari_mem_read(uint32_t addr, uint8_t *buf, size_t len)` — 0 OK ; -1 si `addr+len` > 16 Mo.
  - `int hatari_mem_write(uint32_t addr, const uint8_t *buf, size_t len)` — idem.
  - `void hatari_regs_get(uint32_t out[20])` — ordre de `debug_api.h`.
  - `int hatari_reg_set(const char *name, uint32_t value)` — `"PC"`, `"SR"`, `"D0".."D7"`, `"A0".."A7"`, `"USP"`, `"ISP"` ; 0 OK, -1 inconnu.
  - `int hatari_step(int steps)` — arme `steps` instructions ; l'arrêt arrive au prochain `hatari_run` avec `HATARI_STOP_STEPS`. 0 OK.

- [ ] **Step 1 : étendre le test (échoue)**

Dans `test-debug-api.c`, après les autres `sym(...)` :

```c
	int (*mem_read)(uint32_t, uint8_t *, size_t) = sym("hatari_mem_read");
	int (*mem_write)(uint32_t, const uint8_t *, size_t) = sym("hatari_mem_write");
	void (*regs_get)(uint32_t *) = sym("hatari_regs_get");
	int (*reg_set)(const char *, uint32_t) = sym("hatari_reg_set");
	int (*step)(int) = sym("hatari_step");
```

Avant `printf("All debug-api tests finished successfully.\n");` :

```c
	/* memory round trip in RAM */
	uint8_t wbuf[4] = { 0xDE, 0xAD, 0xBE, 0xEF }, rbuf[4] = { 0 };
	assert(mem_write(0x2000, wbuf, 4) == 0);
	assert(mem_read(0x2000, rbuf, 4) == 0);
	assert(memcmp(wbuf, rbuf, 4) == 0);
	assert(mem_read(0xFFFFFF, rbuf, 4) == -1);

	/* fake TOS loops at $1100: PC stays in [$1100, $1106) */
	uint32_t r[20];
	regs_get(r);
	printf("pc=%06x sr=%04x a7=%08x\n", r[16], r[17], r[15]);
	assert(r[16] >= FAKE_TOS_LOOP && r[16] < FAKE_TOS_LOOP + 8);

	/* register write */
	assert(reg_set("D3", 0x12345678) == 0);
	regs_get(r);
	assert(r[3] == 0x12345678);
	assert(reg_set("Z9", 1) == -1);

	/* single stepping: 3 instructions then STOP_STEPS */
	assert(step(3) == 0);
	assert(run(100, &reason, &done) == 0);
	printf("step 3: reason=%d done=%d\n", reason, done);
	assert(reason == 2);
	assert(done == 0);
```

- [ ] **Step 2 : lancer, vérifier l'échec sur symbole manquant**

```bash
cmake --build build -j$(nproc) && cd build && ctest -R debug-api -V | rg "Missing|Passed|Failed"
```

Attendu : `Missing symbol 'hatari_mem_read'`.

- [ ] **Step 3 : implémenter dans `debug_api.c`**

Ajouter `#include "stMemory.h"` dans les includes, puis à la fin du fichier :

```c
/*-----------------------------------------------------------------------*/
/**
 * Read len bytes from ST address space. Reading IO registers has side
 * effects, exactly like a CPU access would. Returns -1 if out of range.
 */
int hatari_mem_read(uint32_t addr, uint8_t *buf, size_t len)
{
	size_t i;

	if ((uint64_t)addr + len > 0x1000000)
		return -1;
	for (i = 0; i < len; i++)
		buf[i] = STMemory_ReadByte(addr + i);
	return 0;
}


/**
 * Write len bytes to ST address space. Returns -1 if out of range.
 */
int hatari_mem_write(uint32_t addr, const uint8_t *buf, size_t len)
{
	size_t i;

	if ((uint64_t)addr + len > 0x1000000)
		return -1;
	for (i = 0; i < len; i++)
		STMemory_WriteByte(addr + i, buf[i]);
	return 0;
}


/**
 * Copy D0-D7, A0-A7, PC, SR, USP, ISP into out[HATARI_REGS_COUNT].
 */
void hatari_regs_get(uint32_t *out)
{
	int i;

	MakeSR();
	for (i = 0; i < 16; i++)
		out[i] = regs.regs[i];
	out[HATARI_REG_PC] = M68000_GetPC();
	out[HATARI_REG_SR] = regs.sr;
	out[HATARI_REG_USP] = regs.usp;
	out[HATARI_REG_ISP] = regs.isp;
}


/**
 * Set a register by name (PC, SR, D0-D7, A0-A7, USP, ISP, ...).
 * Returns 0 on success, -1 for an unknown register.
 */
int hatari_reg_set(const char *name, uint32_t value)
{
	uint32_t *addr;

	if (strcasecmp(name, "PC") == 0)
	{
		m68k_setpc(value);
		return 0;
	}
	if (strcasecmp(name, "SR") == 0)
	{
		regs.sr = value & 0xffff;
		MakeFromSR();
		return 0;
	}
	if (DebugCpu_GetRegisterAddress(name, &addr) == 0)
		return -1;
	*addr = value;
	return 0;
}


/**
 * Arm single-stepping: next hatari_run() stops with HATARI_STOP_STEPS
 * after given number of instructions.
 */
int hatari_step(int steps)
{
	if (steps <= 0)
		return -1;
	DebugCpu_SetSteps(steps);
	DebugCpu_SetDebugging();
	return 0;
}
```

`strcasecmp` vient de `<strings.h>` : ajouter `#include <strings.h>` en tête. `MakeFromSR` est déclaré dans `newcpu.h` (`extern void REGPARAM3 MakeFromSR (void) REGPARAM;`) ; vérifier avec `rg -n "MakeFromSR" src/cpu/newcpu.h`.

- [ ] **Step 4 : compiler et tester**

```bash
cmake --build build -j$(nproc) 2>&1 | rg -i "warning|error"; cd build && ctest -R debug-api -V | tail -8
```

Attendu : `step 3: reason=2 done=0`, test Passed.

Si `done` vaut 1 au lieu de 0 : le pas-à-pas a traversé un VBL, c'est possible si les 3 instructions chevauchent une trame ; assouplir en `assert(done <= 1)`.

- [ ] **Step 5 : commit**

```bash
git add src/retro/debug_api.c tests/retro/test-debug-api.c
git commit -m "retro: debug API memory, registers and single-step

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FTU7a9LtSiamiFc9wxtVVR"
```

---

### Task 4 : passerelle debugger texte et désassemblage

**Files:**
- Modify: `src/retro/debug_api.c`
- Modify: `tests/retro/test-debug-api.c`

**Interfaces:**
- Consumes : `DebugUI_ParseLine`, `debugOutput` (`debug_priv.h`), `Disasm()` (`68kDisass.h`).
- Produces :
  - `int hatari_dbg_command(const char *cmd, char *out, size_t outlen)` — exécute une commande du debugger Hatari, copie sa sortie (tronquée, `\0` final). Retourne 0 si la commande a rendu `DEBUGGER_CMDDONE`, 1 sinon (commandes de reprise comme `c`), -1 si `open_memstream` échoue.
  - `int hatari_disasm(uint32_t addr, int count, char *out, size_t outlen, uint32_t *next_pc)` — texte du désassembleur, 0 OK.

- [ ] **Step 1 : étendre le test (échoue)**

Symboles :

```c
	int (*dbg)(const char *, char *, size_t) = sym("hatari_dbg_command");
	int (*disasm)(uint32_t, int, char *, size_t, uint32_t *) = sym("hatari_disasm");
```

Avant le `printf` final :

```c
	/* breakpoint via debugger command, then run until it triggers */
	char out[4096];
	assert(dbg("b pc = $1100", out, sizeof(out)) == 0);
	printf("b: %s", out);
	assert(strstr(out, "CPU condition breakpoint") != NULL);
	assert(run(100, &reason, &done) == 0);
	printf("bp: reason=%d done=%d\n", reason, done);
	assert(reason == 1);
	regs_get(r);
	assert(r[16] == FAKE_TOS_LOOP);

	/* list & remove */
	assert(dbg("b", out, sizeof(out)) == 0);
	assert(strstr(out, "pc = $1100") != NULL);
	assert(dbg("b 1", out, sizeof(out)) == 0);
	assert(run(5, &reason, &done) == 0);
	assert(reason == 0 && done == 5);

	/* disassembly */
	uint32_t next = 0;
	assert(disasm(FAKE_TOS_LOOP, 2, out, sizeof(out), &next) == 0);
	printf("disasm:\n%s", out);
	assert(strstr(out, "jmp") != NULL || strstr(out, "JMP") != NULL);
	assert(next > FAKE_TOS_LOOP);
```

- [ ] **Step 2 : lancer, vérifier `Missing symbol 'hatari_dbg_command'`**

```bash
cmake --build build -j$(nproc) && cd build && ctest -R debug-api -V | rg "Missing|Passed|Failed"
```

- [ ] **Step 3 : implémenter**

Ajouter `#include "68kDisass.h"` puis, à la fin de `debug_api.c` :

```c
/*-----------------------------------------------------------------------*/
/**
 * Capture what a function writes to debugOutput into out[outlen].
 * Returns false if the memory stream could not be created.
 */
static bool DebugApi_CaptureOutput(void (*fn)(void *), void *arg, char *out, size_t outlen)
{
	FILE *saved = debugOutput;
	char *buf = NULL;
	size_t buflen = 0;
	FILE *ms;

	ms = open_memstream(&buf, &buflen);
	if (!ms)
		return false;
	debugOutput = ms;
	fn(arg);
	fflush(ms);
	debugOutput = saved;
	fclose(ms);

	if (outlen > 0)
	{
		size_t n = buflen < outlen - 1 ? buflen : outlen - 1;
		memcpy(out, buf, n);
		out[n] = '\0';
	}
	free(buf);
	return true;
}

static int lastCmdDone;

static void DebugApi_RunCommand(void *arg)
{
	lastCmdDone = DebugUI_ParseLine((const char *)arg) ? 0 : 1;
}

/**
 * Execute a Hatari debugger command and capture its output.
 * Returns 0 if command completed, 1 if it asked to resume emulation
 * (e.g. "c"), -1 on capture failure.
 */
int hatari_dbg_command(const char *cmd, char *out, size_t outlen)
{
	if (!DebugApi_CaptureOutput(DebugApi_RunCommand, (void *)cmd, out, outlen))
		return -1;
	return lastCmdDone;
}


typedef struct
{
	uint32_t addr;
	int count;
	uint32_t next;
} disasm_req_t;

static void DebugApi_RunDisasm(void *arg)
{
	disasm_req_t *req = arg;
	uaecptr next = req->addr;

	Disasm(debugOutput, (uaecptr)req->addr, &next, req->count);
	req->next = next;
}

/**
 * Disassemble count instructions from addr into out.
 */
int hatari_disasm(uint32_t addr, int count, char *out, size_t outlen, uint32_t *next_pc)
{
	disasm_req_t req = { addr, count, addr };

	if (!DebugApi_CaptureOutput(DebugApi_RunDisasm, &req, out, outlen))
		return -1;
	if (next_pc)
		*next_pc = req.next;
	return 0;
}
```

Note : `DebugUI_ParseLine` appelle déjà `DebugUI_Init()` et `DebugCpu_SetDebugging()` ; rien à ajouter. Elle écrit aussi `> commande` sur `stderr` : normal.

- [ ] **Step 4 : compiler et tester**

```bash
cmake --build build -j$(nproc) 2>&1 | rg -i "warning|error"; cd build && ctest -R debug-api -V | tail -14
```

Attendu : `bp: reason=1 done=...`, un listing `jmp $1100`, test Passed. Si l'assert sur `"CPU condition breakpoint"` échoue, lire la sortie imprimée et ajuster la chaîne cherchée au message réel de `BreakCond_Command` (voir `src/debug/breakcond.c`, fonction `BreakCond_Parse`).

- [ ] **Step 5 : commit**

```bash
git add src/retro/debug_api.c tests/retro/test-debug-api.c
git commit -m "retro: debug API text command gateway and disassembly

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FTU7a9LtSiamiFc9wxtVVR"
```

---

### Task 5 : framebuffer, clavier, disquettes

**Files:**
- Modify: `src/retro/debug_api.c`
- Modify: `tests/retro/test-debug-api.c`

**Interfaces:**
- Consumes : `Screen_GetDimension` (`screen.h`), `IKBD_PressSTKey` (`ikbd.h`), `Floppy_SetDiskFileName`, `Floppy_InsertDiskIntoDrive`, `Floppy_EjectDiskFromDrive` (`floppy.h`), `File_Exists` (`file.h`).
- Produces :
  - `int hatari_framebuffer(const uint32_t **pixels, int *width, int *height)` — pointeur interne XRGB8888, pitch = `width*4`. 0 OK, -1 si pas encore alloué.
  - `void hatari_key(uint8_t scancode, bool press)`
  - `int hatari_disk_insert(int drive, const char *path)` — 0 OK, -1 drive invalide, -2 fichier absent, -3 échec d'insertion.
  - `int hatari_disk_eject(int drive)` — 0 OK, -1 drive invalide.

- [ ] **Step 1 : étendre le test (échoue)**

```c
	int (*fb)(const uint32_t **, int *, int *) = sym("hatari_framebuffer");
	void (*key)(uint8_t, bool) = sym("hatari_key");
	int (*disk_insert)(int, const char *) = sym("hatari_disk_insert");
	int (*disk_eject)(int) = sym("hatari_disk_eject");
```

Avant le `printf` final :

```c
	const uint32_t *px = NULL;
	int w = 0, h = 0;
	assert(fb(&px, &w, &h) == 0);
	printf("framebuffer %dx%d\n", w, h);
	assert(px != NULL && w >= 320 && h >= 200);

	key(0x39, true);	/* space */
	assert(run(2, &reason, &done) == 0);
	key(0x39, false);
	assert(run(2, &reason, &done) == 0);

	assert(disk_insert(2, "x.st") == -1);
	assert(disk_insert(0, "/nonexistent/x.st") == -2);
	assert(disk_eject(0) == 0);
```

- [ ] **Step 2 : lancer, vérifier `Missing symbol 'hatari_framebuffer'`**

- [ ] **Step 3 : implémenter**

Includes : `#include "screen.h"`, `#include "ikbd.h"`, `#include "floppy.h"`, `#include "file.h"`. Fin de fichier :

```c
/*-----------------------------------------------------------------------*/
/**
 * Current host framebuffer (XRGB8888, pitch = width*4). Pointer stays
 * valid until the next resolution change.
 */
int hatari_framebuffer(const uint32_t **pixels, int *width, int *height)
{
	uint32_t *px = NULL;
	int w = 0, h = 0, pitch = 0;

	Screen_GetDimension(&px, &w, &h, &pitch);
	if (!px)
		return -1;
	*pixels = px;
	*width = w;
	*height = h;
	return 0;
}


/**
 * Press or release an ST keyboard scancode.
 */
void hatari_key(uint8_t scancode, bool press)
{
	IKBD_PressSTKey(scancode, press);
}


/**
 * Insert a floppy image into drive 0 (A:) or 1 (B:).
 */
int hatari_disk_insert(int drive, const char *path)
{
	if (drive < 0 || drive > 1)
		return -1;
	if (!File_Exists(path))
		return -2;
	Floppy_SetDiskFileName(drive, path, NULL);
	if (!Floppy_InsertDiskIntoDrive(drive))
		return -3;
	return 0;
}


/**
 * Eject the floppy from given drive.
 */
int hatari_disk_eject(int drive)
{
	if (drive < 0 || drive > 1)
		return -1;
	Floppy_EjectDiskFromDrive(drive);
	return 0;
}
```

Vérifier la signature exacte de `File_Exists` : `rg -n "File_Exists" src/includes/file.h`.

- [ ] **Step 4 : compiler, tester tout, y compris les tests existants**

```bash
cmake --build build -j$(nproc) 2>&1 | rg -i "warning|error"; cd build && ctest -j$(nproc) | tail -3
```

Attendu : `100% tests passed`.

- [ ] **Step 5 : commit**

```bash
git add src/retro/debug_api.c tests/retro/test-debug-api.c
git commit -m "retro: debug API framebuffer, keyboard and floppy access

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FTU7a9LtSiamiFc9wxtVVR"
```

---

### Task 6 : squelette Maven `hatari-mcp` avec `ping`

**Files:**
- Create: `hatari-mcp/pom.xml`, `hatari-mcp/LICENSE`, `hatari-mcp/.gitignore`
- Create: `hatari-mcp/src/main/java/fr/hatari/mcp/{StdioMcpMain,EofSignalingInputStream,McpJson,Tools,Args,Fmt,Machine,EmulatorSession,ToolCatalog}.java`
- Create: `hatari-mcp/src/main/java/fr/hatari/mcp/tools/MachineTools.java` (ping seulement)
- Create: `hatari-mcp/src/test/java/fr/hatari/mcp/FakeMachine.java`
- Create: `hatari-mcp/src/test/java/fr/hatari/mcp/ToolCatalogTest.java`

**Interfaces:**
- Produces :
  - `interface Machine` (ci-dessous) : contrat unique consommé par tous les outils.
  - `EmulatorSession` : `<T> T read(Function<Machine,T>)`, `void mutate(Consumer<Machine>)`, `void close()`.
  - `Tools.tool(name, description, inputSchemaJson, Function<Args,Object>)` (repris de toje).
  - `Args` : `intVal`, `hex` (24 bits), `str`, `bool`, `has`.
  - `Fmt.hex24(int)`, `Fmt.hex16(int)`, `Fmt.hex8(int)`, `Fmt.hex32(long)`.
  - `ToolCatalog.all()` : `List<SyncToolSpecification>`.

- [ ] **Step 1 : `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>fr.hatari</groupId>
    <artifactId>hatari-mcp</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>hatari-mcp</name>
    <description>Serveur MCP stdio pilotant l'émulateur Atari ST Hatari (cœur libretro chargé par FFM).</description>

    <properties>
        <maven.compiler.release>25</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <mcp.sdk.version>2.0.0</mcp.sdk.version>
        <junit.version>5.10.2</junit.version>
        <exec.mainClass>fr.hatari.mcp.StdioMcpMain</exec.mainClass>
    </properties>

    <dependencies>
        <dependency>
            <groupId>io.modelcontextprotocol.sdk</groupId>
            <artifactId>mcp</artifactId>
            <version>${mcp.sdk.version}</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>hatari-mcp</finalName>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
                <configuration>
                    <argLine>--enable-native-access=ALL-UNNAMED</argLine>
                    <systemPropertyVariables>
                        <hatari.core>${hatari.core}</hatari.core>
                        <hatari.tos>${hatari.tos}</hatari.tos>
                    </systemPropertyVariables>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.2.0</version>
                <configuration>
                    <mainClass>${exec.mainClass}</mainClass>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-assembly-plugin</artifactId>
                <version>3.7.1</version>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>${exec.mainClass}</mainClass>
                        </manifest>
                    </archive>
                    <descriptorRefs>
                        <descriptorRef>jar-with-dependencies</descriptorRef>
                    </descriptorRefs>
                    <appendAssemblyId>false</appendAssemblyId>
                </configuration>
                <executions>
                    <execution>
                        <id>fat-jar</id>
                        <phase>package</phase>
                        <goals><goal>single</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

`hatari-mcp/.gitignore` : `target/`. `hatari-mcp/LICENSE` : `cp gpl.txt hatari-mcp/LICENSE`.

- [ ] **Step 2 : copier et adapter les classes d'infrastructure de toje-mcp**

Source : `~/dev/toje/toje-mcp/src/main/java/fr/toje/mcp/`. Copier `EofSignalingInputStream.java`, `McpJson.java`, `Tools.java`, `Args.java` dans `hatari-mcp/src/main/java/fr/hatari/mcp/` en remplaçant `package fr.toje.mcp;` par `package fr.hatari.mcp;` et `import fr.toje.mcp.` par `import fr.hatari.mcp.` :

```bash
SRC=~/dev/toje/toje-mcp/src/main/java/fr/toje/mcp; DST=hatari-mcp/src/main/java/fr/hatari/mcp; mkdir -p $DST/tools $DST/ffm $DST/keymap
for f in EofSignalingInputStream McpJson Tools Args; do sed 's/fr\.toje\.mcp/fr.hatari.mcp/g' $SRC/$f.java > $DST/$f.java; done
```

Adapter `Args.java` : dans `hex(String key)`, remplacer `& 0xFFFF` par `& 0xFFFFFF` et mettre à jour la javadoc (« adresses 68000 sur 24 bits »). Remplacer les mentions « 6809 » par « 68000 » dans les commentaires. Le message d'erreur sur entier JSON nu reste identique.

- [ ] **Step 3 : `Machine.java`**

```java
package fr.hatari.mcp;

/**
 * Contrat consommé par les outils MCP. Implémenté par {@code ffm.HatariCore}
 * (vraie machine) et par un fake dans les tests. Tous les appels sont
 * synchrones et supposés exécutés sur un seul thread.
 */
public interface Machine {

    /** Raison d'arrêt de {@link #run}. Valeurs alignées sur {@code HATARI_STOP_*} de debug_api.h. */
    enum StopReason {
        NONE, BREAKPOINT, STEPS, EXCEPTION, OTHER;

        public static StopReason of(int code) {
            return code >= 0 && code < values().length ? values()[code] : OTHER;
        }
    }

    /** Résultat de {@link #run}. */
    record RunResult(StopReason reason, int framesDone) {}

    /** Registres CPU : D0-D7, A0-A7, PC, SR, USP, ISP (20 valeurs non signées). */
    record Registers(long[] values) {
        public long d(int i) { return values[i]; }
        public long a(int i) { return values[8 + i]; }
        public long pc() { return values[16]; }
        public int sr() { return (int) values[17]; }
        public long usp() { return values[18]; }
        public long isp() { return values[19]; }
    }

    /** Image écran XRGB8888 copiée. */
    record Frame(int width, int height, int[] pixels) {}

    RunResult run(int maxFrames);
    void step(int instructions);
    void reset(boolean cold);
    long vblCount();
    long cycleCount();

    byte[] readMemory(int addr, int len);
    void writeMemory(int addr, byte[] data);
    Registers registers();
    void setRegister(String name, long value);

    /** Exécute une commande du debugger Hatari ; retourne sa sortie texte. */
    String debugCommand(String cmd);
    /** Désassemble ; retourne le texte. */
    String disassemble(int addr, int count);

    Frame frame();
    void key(int scancode, boolean press);
    void insertDisk(int drive, String path);
    void ejectDisk(int drive);

    /** Libère la machine (retro_deinit). */
    void close();
}
```

- [ ] **Step 4 : `EmulatorSession.java`**

```java
package fr.hatari.mcp;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Une session = une machine Hatari. Sérialise tous les accès au cœur natif
 * (un seul thread doit toucher le cœur libretro).
 */
public final class EmulatorSession implements AutoCloseable {

    private final Object lock = new Object();
    private final Machine machine;

    public EmulatorSession(Machine machine) {
        this.machine = machine;
    }

    public <T> T read(Function<Machine, T> fn) {
        synchronized (lock) {
            return fn.apply(machine);
        }
    }

    public void mutate(Consumer<Machine> fn) {
        synchronized (lock) {
            fn.accept(machine);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            machine.close();
        }
    }
}
```

- [ ] **Step 5 : `Fmt.java`**

```java
package fr.hatari.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/** Formatage partagé des sorties d'outils (hex majuscule sans préfixe, registres 68k, hexdump). */
public final class Fmt {

    private Fmt() {}

    public static String hex8(int v) { return String.format("%02X", v & 0xFF); }
    public static String hex16(int v) { return String.format("%04X", v & 0xFFFF); }
    public static String hex24(int v) { return String.format("%06X", v & 0xFFFFFF); }
    public static String hex32(long v) { return String.format("%08X", v & 0xFFFFFFFFL); }

    /** Registres 68000 + drapeaux décodés. */
    public static Map<String, Object> registers(Machine.Registers r) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) out.put("d" + i, hex32(r.d(i)));
        for (int i = 0; i < 8; i++) out.put("a" + i, hex32(r.a(i)));
        out.put("pc", hex24((int) r.pc()));
        out.put("sr", hex16(r.sr()));
        out.put("usp", hex32(r.usp()));
        out.put("isp", hex32(r.isp()));
        out.put("flags", flags(r.sr()));
        return out;
    }

    /** SR décodé : T1, S, IPL, X, N, Z, V, C. */
    public static Map<String, Object> flags(int sr) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("t", (sr & 0x8000) != 0);
        f.put("s", (sr & 0x2000) != 0);
        f.put("ipl", (sr >> 8) & 7);
        f.put("x", (sr & 0x10) != 0);
        f.put("n", (sr & 0x08) != 0);
        f.put("z", (sr & 0x04) != 0);
        f.put("v", (sr & 0x02) != 0);
        f.put("c", (sr & 0x01) != 0);
        return f;
    }

    /** Hexdump 16 octets par ligne : adresse, octets, ASCII. */
    public static String hexdump(int base, byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int off = 0; off < data.length; off += 16) {
            sb.append(hex24(base + off)).append(": ");
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                if (off + i < data.length) {
                    int b = data[off + i] & 0xFF;
                    sb.append(hex8(b)).append(' ');
                    ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
                } else {
                    sb.append("   ");
                }
            }
            sb.append(' ').append(ascii).append('\n');
        }
        return sb.toString();
    }
}
```

- [ ] **Step 6 : `MachineTools.java` (ping seulement) et `ToolCatalog.java`**

```java
package fr.hatari.mcp.tools;

import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Tools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.List;
import java.util.Map;

/** Outils de contrôle de la machine : ping, état, reset, exécution. */
public final class MachineTools {

    private final EmulatorSession session;
    private final Tools tools;

    public MachineTools(EmulatorSession session, Tools tools) {
        this.session = session;
        this.tools = tools;
    }

    public List<SyncToolSpecification> specs() {
        return List.of(ping());
    }

    private SyncToolSpecification ping() {
        return tools.tool("ping", "Vérifie que le serveur et la machine émulée répondent.",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> session.read(m -> Map.of("ok", true, "vbl", m.vblCount())));
    }
}
```

```java
package fr.hatari.mcp;

import fr.hatari.mcp.tools.MachineTools;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.ArrayList;
import java.util.List;

/** Source unique des outils MCP exposant Hatari. */
public final class ToolCatalog {

    private final MachineTools machineTools;

    public ToolCatalog(EmulatorSession session, McpJsonMapper mapper) {
        Tools tools = new Tools(mapper);
        this.machineTools = new MachineTools(session, tools);
    }

    public List<SyncToolSpecification> all() {
        List<SyncToolSpecification> list = new ArrayList<>();
        list.addAll(machineTools.specs());
        return list;
    }
}
```

- [ ] **Step 7 : `StdioMcpMain.java`**

Copie de la version toje avec ces différences : package `fr.hatari.mcp`, suppression de la ligne `System.setProperty("toje.init.report", "off");`, création de la session via `Options` (Task 7). Pour cette tâche, la machine est créée par `Options.parse(args).openMachine()` : écrire dès maintenant `Options.java` minimal :

```java
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
```

Dans `StdioMcpMain.main`, remplacer `EmulatorSession session = new EmulatorSession();` par :

```java
        Options options = Options.parse(args);
        EmulatorSession session = new EmulatorSession(fr.hatari.mcp.ffm.HatariCore.open(options));
        Runtime.getRuntime().addShutdownHook(new Thread(session::close));
```

et `.serverInfo("toje-emulator", "0.1.0")` par `.serverInfo("hatari-emulator", "0.1.0")`, `.instructions(...)` par « Émulateur Atari ST/STE/TT/Falcon (Hatari, CPU 68000). Inspecte et pilote une machine émulée : registres, mémoire, désassemblage, breakpoints, exécution par trames ou pas-à-pas, clavier, disquettes, screenshot. ». `HatariCore.open` n'existe pas encore : créer dans `ffm/HatariCore.java` un squelette qui lève `UnsupportedOperationException("Task 7")` pour compiler.

- [ ] **Step 8 : `FakeMachine.java` et test du catalogue**

```java
package fr.hatari.mcp;

import java.util.ArrayList;
import java.util.List;

/** Machine factice : mémoire 16 Mo lazy, registres, journal des appels. */
public class FakeMachine implements Machine {
    public final byte[] ram = new byte[0x100000];
    public final long[] regs = new long[20];
    public final List<String> calls = new ArrayList<>();
    public long vbl;
    public StopReason nextStop = StopReason.NONE;
    public String debugOutput = "";

    @Override public RunResult run(int maxFrames) {
        calls.add("run " + maxFrames);
        StopReason r = nextStop;
        nextStop = StopReason.NONE;
        int done = r == StopReason.NONE ? maxFrames : 0;
        vbl += done;
        return new RunResult(r, done);
    }
    @Override public void step(int n) { calls.add("step " + n); nextStop = StopReason.STEPS; }
    @Override public void reset(boolean cold) { calls.add("reset " + cold); }
    @Override public long vblCount() { return vbl; }
    @Override public long cycleCount() { return vbl * 160256; }
    @Override public byte[] readMemory(int addr, int len) {
        byte[] out = new byte[len];
        System.arraycopy(ram, addr, out, 0, len);
        return out;
    }
    @Override public void writeMemory(int addr, byte[] data) { System.arraycopy(data, 0, ram, addr, data.length); }
    @Override public Registers registers() { return new Registers(regs.clone()); }
    @Override public void setRegister(String name, long value) { calls.add("reg " + name + "=" + value); }
    @Override public String debugCommand(String cmd) { calls.add("dbg " + cmd); return debugOutput; }
    @Override public String disassemble(int addr, int count) { return String.format("$%06x : jmp $1100\n", addr); }
    @Override public Frame frame() { return new Frame(320, 200, new int[320 * 200]); }
    @Override public void key(int scancode, boolean press) { calls.add("key " + scancode + " " + press); }
    @Override public void insertDisk(int drive, String path) { calls.add("insert " + drive + " " + path); }
    @Override public void ejectDisk(int drive) { calls.add("eject " + drive); }
    @Override public void close() { calls.add("close"); }
}
```

`ToolCatalogTest.java` :

```java
package fr.hatari.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogTest {

    static CallToolResult call(ToolCatalog catalog, String name, Map<String, Object> args) {
        for (SyncToolSpecification spec : catalog.all()) {
            if (spec.tool().name().equals(name)) {
                return spec.callHandler().apply(null, new CallToolRequest(name, args));
            }
        }
        throw new AssertionError("outil absent : " + name);
    }

    @Test
    void pingReturnsOkAndVbl() {
        FakeMachine fake = new FakeMachine();
        fake.vbl = 42;
        ToolCatalog catalog = new ToolCatalog(new EmulatorSession(fake), McpJson.defaultMapper());
        CallToolResult res = call(catalog, "ping", Map.of());
        assertNotEquals(Boolean.TRUE, res.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) res.structuredContent();
        assertEquals(true, out.get("ok"));
        assertEquals(42L, ((Number) out.get("vbl")).longValue());
    }

    @Test
    void toolNamesAreUnique() {
        ToolCatalog catalog = new ToolCatalog(new EmulatorSession(new FakeMachine()), McpJson.defaultMapper());
        List<String> names = catalog.all().stream().map(s -> s.tool().name()).toList();
        assertEquals(names.size(), names.stream().distinct().count());
    }
}
```

Si `spec.callHandler().apply(null, request)` ne compile pas avec le SDK 2.0.0, ouvrir `~/dev/toje/toje-mcp/src/test/java` pour voir comment toje invoque un outil dans ses tests et copier la même forme.

- [ ] **Step 9 : compiler et tester**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25.0.4-tem; export PATH=$JAVA_HOME/bin:$PATH
cd hatari-mcp && mvn -q test
```

Attendu : `BUILD SUCCESS`, 2 tests verts.

- [ ] **Step 10 : commit**

```bash
git add hatari-mcp
git commit -m "hatari-mcp: Maven skeleton with stdio MCP server and ping tool

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FTU7a9LtSiamiFc9wxtVVR"
```

---

### Task 7 : bindings FFM `HatariCore`

**Files:**
- Create: `hatari-mcp/src/main/java/fr/hatari/mcp/ffm/RetroCallbacks.java`
- Modify (remplace le squelette): `hatari-mcp/src/main/java/fr/hatari/mcp/ffm/HatariCore.java`
- Create: `hatari-mcp/src/test/java/fr/hatari/mcp/it/CoreIT.java`

**Interfaces:**
- Consumes : exports `hatari_*` (Tasks 2-5), `retro_set_*`, `retro_load_game`, `retro_deinit`.
- Produces : `static HatariCore open(Options)` ; `HatariCore implements Machine`.

- [ ] **Step 1 : test d'intégration (échoue : squelette)**

```java
package fr.hatari.mcp.it;

import fr.hatari.mcp.Machine;
import fr.hatari.mcp.Options;
import fr.hatari.mcp.ffm.HatariCore;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Tests sur la vraie .so : -Dhatari.core=... -Dhatari.tos=... (ignorés sinon). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoreIT {

    static HatariCore core;

    @BeforeAll
    void open() {
        String so = System.getProperty("hatari.core", "../build/src/libretro-hatari.so");
        String tos = System.getProperty("hatari.tos", System.getProperty("user.home") + "/.hatari/tos.img");
        assumeTrue(Files.exists(Path.of(so)), "pas de core : " + so);
        assumeTrue(Files.exists(Path.of(tos)), "pas de TOS : " + tos);
        core = HatariCore.open(new Options(Path.of(so), Path.of(tos), "st", 1));
    }

    @AfterAll
    void close() {
        if (core != null) core.close();
    }

    @Test
    @Order(1)
    void bootsEmuTosAndDrawsSomething() {
        Machine.RunResult r = core.run(300);
        assertEquals(Machine.StopReason.NONE, r.reason());
        assertEquals(300, r.framesDone());
        Machine.Frame f = core.frame();
        assertTrue(f.width() >= 320 && f.height() >= 200);
        long distinct = java.util.Arrays.stream(f.pixels()).distinct().count();
        assertTrue(distinct > 2, "écran uniforme, EmuTOS n'a rien affiché");
    }

    @Test
    void memoryRoundTrip() {
        byte[] data = { 1, 2, 3, 4 };
        core.writeMemory(0x2000, data);
        assertArrayEquals(data, core.readMemory(0x2000, 4));
    }

    @Test
    void registersAndRomPc() {
        Machine.Registers regs = core.registers();
        assertTrue(regs.pc() >= 0xE00000 && regs.pc() < 0xF00000, "PC hors ROM : " + Long.toHexString(regs.pc()));
    }

    @Test
    void breakpointStopsRun() {
        long pc = core.registers().pc();
        core.run(1);
        long target = core.registers().pc();
        String out = core.debugCommand("b pc = $" + Long.toHexString(target) + " :once");
        assertTrue(out.contains("breakpoint"), out);
        Machine.RunResult r = core.run(500);
        assertEquals(Machine.StopReason.BREAKPOINT, r.reason(), "pc initial " + Long.toHexString(pc));
        assertEquals(target, core.registers().pc());
    }

    @Test
    void stepStopsAfterInstructions() {
        core.step(5);
        Machine.RunResult r = core.run(10);
        assertEquals(Machine.StopReason.STEPS, r.reason());
    }

    @Test
    void disassembleReturnsText() {
        String txt = core.disassemble((int) core.registers().pc(), 4);
        assertFalse(txt.isBlank());
    }
}
```

Surefire n'exécute pas les classes `*IT` par défaut : ajouter dans la configuration surefire du `pom.xml` `<includes><include>**/*Test.java</include><include>**/*IT.java</include></includes>`.

- [ ] **Step 2 : lancer, vérifier l'échec**

```bash
cd hatari-mcp && mvn -q test -Dtest=CoreIT 2>&1 | tail -15
```

Attendu : `UnsupportedOperationException: Task 7`.

- [ ] **Step 3 : `RetroCallbacks.java`**

```java
package fr.hatari.mcp.ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Upcalls libretro : environnement, vidéo, audio, entrées. Tous inertes sauf l'environnement. */
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
```

- [ ] **Step 4 : `HatariCore.java`**

```java
package fr.hatari.mcp.ffm;

import fr.hatari.mcp.Machine;
import fr.hatari.mcp.Options;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.*;

/** Bindings FFM vers libretro-hatari.so + debug_api. Seule classe qui manipule MemorySegment. */
public final class HatariCore implements Machine {

    private static final int TEXT_BUF = 1 << 20;
    private static final FunctionDescriptor SET_CB = FunctionDescriptor.ofVoid(ADDRESS);

    private final Arena arena = Arena.ofShared();
    private final MethodHandle run, step, reset, counters, memRead, memWrite, regsGet, regSet,
            dbgCommand, disasm, framebuffer, key, diskInsert, diskEject, deinit;
    private final MemorySegment textBuf;
    private boolean closed;

    public static HatariCore open(Options options) {
        try {
            return new HatariCore(options);
        } catch (Throwable t) {
            throw new IllegalStateException("chargement du core Hatari impossible : " + t.getMessage(), t);
        }
    }

    private HatariCore(Options options) throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lib = SymbolLookup.libraryLookup(options.core().toAbsolutePath().toString(), arena);
        RetroCallbacks cb = new RetroCallbacks(linker, arena);

        down(linker, lib, "retro_set_environment", SET_CB).invoke(cb.env);
        down(linker, lib, "retro_set_video_refresh", SET_CB).invoke(cb.video);
        down(linker, lib, "retro_set_audio_sample", SET_CB).invoke(cb.audio);
        down(linker, lib, "retro_set_audio_sample_batch", SET_CB).invoke(cb.audioBatch);
        down(linker, lib, "retro_set_input_poll", SET_CB).invoke(cb.inputPoll);
        down(linker, lib, "retro_set_input_state", SET_CB).invoke(cb.inputState);

        String[] args = options.hatariArgs();
        MemorySegment argv = arena.allocate(ADDRESS, args.length);
        for (int i = 0; i < args.length; i++) argv.setAtIndex(ADDRESS, i, arena.allocateFrom(args[i]));
        down(linker, lib, "hatari_init_args", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS)).invoke(args.length, argv);
        down(linker, lib, "retro_load_game", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS)).invoke(MemorySegment.NULL);

        run = down(linker, lib, "hatari_run", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        step = down(linker, lib, "hatari_step", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        reset = down(linker, lib, "hatari_reset", FunctionDescriptor.ofVoid(JAVA_BOOLEAN));
        counters = down(linker, lib, "hatari_counters", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        memRead = down(linker, lib, "hatari_mem_read", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG));
        memWrite = down(linker, lib, "hatari_mem_write", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG));
        regsGet = down(linker, lib, "hatari_regs_get", FunctionDescriptor.ofVoid(ADDRESS));
        regSet = down(linker, lib, "hatari_reg_set", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        dbgCommand = down(linker, lib, "hatari_dbg_command", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
        disasm = down(linker, lib, "hatari_disasm", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
        framebuffer = down(linker, lib, "hatari_framebuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        key = down(linker, lib, "hatari_key", FunctionDescriptor.ofVoid(JAVA_BYTE, JAVA_BOOLEAN));
        diskInsert = down(linker, lib, "hatari_disk_insert", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        diskEject = down(linker, lib, "hatari_disk_eject", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        deinit = down(linker, lib, "retro_deinit", FunctionDescriptor.ofVoid());
        textBuf = arena.allocate(TEXT_BUF);
    }

    private static MethodHandle down(Linker linker, SymbolLookup lib, String name, FunctionDescriptor fd) {
        return linker.downcallHandle(lib.findOrThrow(name), fd);
    }

    private static RuntimeException wrap(Throwable t) {
        return t instanceof RuntimeException r ? r : new IllegalStateException(t);
    }

    @Override
    public RunResult run(int maxFrames) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment reason = a.allocate(JAVA_INT), done = a.allocate(JAVA_INT);
            run.invoke(maxFrames, reason, done);
            return new RunResult(StopReason.of(reason.get(JAVA_INT, 0)), done.get(JAVA_INT, 0));
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public void step(int instructions) {
        try {
            if ((int) step.invoke(instructions) != 0) throw new IllegalArgumentException("steps doit être > 0");
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public void reset(boolean cold) {
        try { reset.invoke(cold); } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public long vblCount() { return counters()[0]; }

    @Override
    public long cycleCount() { return counters()[1]; }

    private long[] counters() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment vbl = a.allocate(JAVA_INT), cyc = a.allocate(JAVA_LONG);
            counters.invoke(vbl, cyc);
            return new long[] { Integer.toUnsignedLong(vbl.get(JAVA_INT, 0)), cyc.get(JAVA_LONG, 0) };
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public byte[] readMemory(int addr, int len) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(len);
            if ((int) memRead.invoke(addr, buf, (long) len) != 0) throw new IllegalArgumentException("adresse hors plage");
            return buf.toArray(JAVA_BYTE);
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public void writeMemory(int addr, byte[] data) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocateFrom(JAVA_BYTE, data);
            if ((int) memWrite.invoke(addr, buf, (long) data.length) != 0) throw new IllegalArgumentException("adresse hors plage");
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public Registers registers() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(JAVA_INT, 20);
            regsGet.invoke(out);
            long[] v = new long[20];
            for (int i = 0; i < 20; i++) v[i] = Integer.toUnsignedLong(out.getAtIndex(JAVA_INT, i));
            return new Registers(v);
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public void setRegister(String name, long value) {
        try (Arena a = Arena.ofConfined()) {
            if ((int) regSet.invoke(a.allocateFrom(name), (int) value) != 0)
                throw new IllegalArgumentException("registre inconnu : " + name);
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public String debugCommand(String cmd) {
        try (Arena a = Arena.ofConfined()) {
            int rc = (int) dbgCommand.invoke(a.allocateFrom(cmd), textBuf, (long) TEXT_BUF);
            if (rc < 0) throw new IllegalStateException("capture de la sortie impossible");
            return textBuf.getString(0, StandardCharsets.ISO_8859_1);
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public String disassemble(int addr, int count) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment next = a.allocate(JAVA_INT);
            disasm.invoke(addr, count, textBuf, (long) TEXT_BUF, next);
            return textBuf.getString(0, StandardCharsets.ISO_8859_1);
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public Frame frame() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment px = a.allocate(ADDRESS), w = a.allocate(JAVA_INT), h = a.allocate(JAVA_INT);
            if ((int) framebuffer.invoke(px, w, h) != 0) throw new IllegalStateException("framebuffer non alloué");
            int width = w.get(JAVA_INT, 0), height = h.get(JAVA_INT, 0);
            MemorySegment pixels = px.get(ADDRESS, 0).reinterpret((long) width * height * 4);
            return new Frame(width, height, pixels.toArray(JAVA_INT));
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public void key(int scancode, boolean press) {
        try { key.invoke((byte) scancode, press); } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public void insertDisk(int drive, String path) {
        try (Arena a = Arena.ofConfined()) {
            int rc = (int) diskInsert.invoke(drive, a.allocateFrom(path));
            switch (rc) {
                case 0 -> {}
                case -1 -> throw new IllegalArgumentException("lecteur invalide : " + drive);
                case -2 -> throw new IllegalArgumentException("image introuvable : " + path);
                default -> throw new IllegalStateException("insertion refusée par Hatari : " + path);
            }
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public void ejectDisk(int drive) {
        try {
            if ((int) diskEject.invoke(drive) != 0) throw new IllegalArgumentException("lecteur invalide : " + drive);
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { deinit.invoke(); } catch (Throwable t) { throw wrap(t); }
        arena.close();
    }
}
```

- [ ] **Step 5 : lancer le test d'intégration**

```bash
cd hatari-mcp && mvn -q test -Dtest=CoreIT 2>&1 | rg -v "Bus Error" | tail -20
```

Attendu : 6 tests verts. En cas de `SIGSEGV` au chargement : vérifier que la `.so` a été rebuildée après Task 5 (`ls -la ../build/src/libretro-hatari.so`).

- [ ] **Step 6 : commit**

```bash
git add hatari-mcp
git commit -m "hatari-mcp: FFM bindings to libretro-hatari core with integration tests

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FTU7a9LtSiamiFc9wxtVVR"
```

---

### Task 8 : `MachineTools` — état, reset, exécution

**Files:**
- Modify: `hatari-mcp/src/main/java/fr/hatari/mcp/tools/MachineTools.java`
- Create: `hatari-mcp/src/test/java/fr/hatari/mcp/tools/MachineToolsTest.java`

**Interfaces:**
- Produces les outils : `machine_state`, `reset {cold?}`, `run_frames {n}`, `run_until_pc {pc, max_frames?}`, `run_to_breakpoint {max_frames?}`, `step {n?}`. Toutes les réponses d'exécution ont la forme `{reason, frames_done, pc, vbl}` construite par `MachineTools.runReport(Machine, RunResult)` (méthode `static` publique, réutilisée par `DiskTools`).

- [ ] **Step 1 : tests (échouent)**

```java
package fr.hatari.mcp.tools;

import fr.hatari.mcp.*;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MachineToolsTest {

    FakeMachine fake;
    ToolCatalog catalog;

    @BeforeEach
    void setUp() {
        fake = new FakeMachine();
        catalog = new ToolCatalog(new EmulatorSession(fake), McpJson.defaultMapper());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> call(String name, Map<String, Object> args) {
        CallToolResult r = ToolCatalogTest.call(catalog, name, args);
        assertNotEquals(Boolean.TRUE, r.isError(), () -> "erreur : " + r.content());
        return (Map<String, Object>) r.structuredContent();
    }

    @Test
    void runFramesReportsReasonPcAndVbl() {
        fake.regs[16] = 0xE00D98L;
        Map<String, Object> out = call("run_frames", Map.of("n", 10));
        assertEquals("NONE", out.get("reason"));
        assertEquals(10, out.get("frames_done"));
        assertEquals("E00D98", out.get("pc"));
        assertEquals(10L, ((Number) out.get("vbl")).longValue());
        assertEquals("run 10", fake.calls.get(0));
    }

    @Test
    void runUntilPcSetsOnceBreakpointThenRuns() {
        fake.nextStop = Machine.StopReason.BREAKPOINT;
        Map<String, Object> out = call("run_until_pc", Map.of("pc", "E00D98", "max_frames", 50));
        assertEquals("BREAKPOINT", out.get("reason"));
        assertEquals("dbg b pc = $E00D98 :once", fake.calls.get(0));
        assertEquals("run 50", fake.calls.get(1));
    }

    @Test
    void stepArmsThenRunsOneFrame() {
        Map<String, Object> out = call("step", Map.of("n", 3));
        assertEquals("STEPS", out.get("reason"));
        assertEquals("step 3", fake.calls.get(0));
        assertEquals("run 1", fake.calls.get(1));
    }

    @Test
    void resetDefaultsToCold() {
        call("reset", Map.of());
        assertEquals("reset true", fake.calls.get(0));
    }

    @Test
    void machineStateExposesCountersAndRegisters() {
        fake.vbl = 7;
        Map<String, Object> out = call("machine_state", Map.of());
        assertEquals(7L, ((Number) out.get("vbl")).longValue());
        assertTrue(out.containsKey("registers"));
    }
}
```

- [ ] **Step 2 : vérifier l'échec** — `mvn -q test -Dtest=MachineToolsTest` : « outil absent : run_frames ».

- [ ] **Step 3 : implémentation**

```java
package fr.hatari.mcp.tools;

import fr.hatari.mcp.Args;
import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Fmt;
import fr.hatari.mcp.Machine;
import fr.hatari.mcp.Tools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Outils de contrôle de la machine : ping, état, reset, exécution. */
public final class MachineTools {

    /** Garde-fou par défaut pour run_until_pc / run_to_breakpoint (≈ 20 s machine à 50 Hz). */
    static final int DEFAULT_MAX_FRAMES = 1000;

    private final EmulatorSession session;
    private final Tools tools;

    public MachineTools(EmulatorSession session, Tools tools) {
        this.session = session;
        this.tools = tools;
    }

    public List<SyncToolSpecification> specs() {
        return List.of(ping(), machineState(), reset(), runFrames(), runUntilPc(), runToBreakpoint(), step());
    }

    /** Compte rendu commun à toutes les exécutions. */
    public static Map<String, Object> runReport(Machine m, Machine.RunResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.reason().name());
        out.put("frames_done", r.framesDone());
        out.put("pc", Fmt.hex24((int) m.registers().pc()));
        out.put("vbl", m.vblCount());
        return out;
    }

    private SyncToolSpecification ping() {
        return tools.tool("ping", "Vérifie que le serveur et la machine émulée répondent.",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> session.read(m -> Map.of("ok", true, "vbl", m.vblCount())));
    }

    private SyncToolSpecification machineState() {
        return tools.tool("machine_state",
                "État courant : compteur VBL, cycles CPU, registres et drapeaux.",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> session.read(m -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("vbl", m.vblCount());
                    out.put("cycles", m.cycleCount());
                    out.put("registers", Fmt.registers(m.registers()));
                    return out;
                }));
    }

    private SyncToolSpecification reset() {
        return tools.tool("reset", "Reset de la machine (cold par défaut, warm si cold=false).",
                "{\"type\":\"object\",\"properties\":{\"cold\":{\"type\":\"boolean\"}}}",
                args -> {
                    boolean cold = args.bool("cold", true);
                    session.mutate(m -> m.reset(cold));
                    return Map.of("ok", true, "cold", cold);
                });
    }

    private SyncToolSpecification runFrames() {
        return tools.tool("run_frames",
                "Exécute n trames (VBL, 50 Hz). S'arrête plus tôt sur breakpoint/watchpoint/step.",
                "{\"type\":\"object\",\"required\":[\"n\"],\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100000}}}",
                args -> {
                    int n = args.intVal("n");
                    return session.read(m -> runReport(m, m.run(n)));
                });
    }

    private SyncToolSpecification runUntilPc() {
        return tools.tool("run_until_pc",
                "Exécute jusqu'à ce que PC atteigne l'adresse hex donnée (breakpoint temporaire), "
                        + "au plus max_frames trames (défaut " + DEFAULT_MAX_FRAMES + ").",
                "{\"type\":\"object\",\"required\":[\"pc\"],\"properties\":{"
                        + "\"pc\":{\"type\":\"string\"},\"max_frames\":{\"type\":\"integer\",\"minimum\":1}}}",
                args -> {
                    int pc = args.hex("pc");
                    int max = args.intVal("max_frames", DEFAULT_MAX_FRAMES);
                    return session.read(m -> {
                        m.debugCommand("b pc = $" + Fmt.hex24(pc) + " :once");
                        Machine.RunResult r = m.run(max);
                        Map<String, Object> out = runReport(m, r);
                        out.put("reached", r.reason() == Machine.StopReason.BREAKPOINT);
                        return out;
                    });
                });
    }

    private SyncToolSpecification runToBreakpoint() {
        return tools.tool("run_to_breakpoint",
                "Exécute jusqu'au prochain breakpoint/watchpoint, au plus max_frames trames (défaut "
                        + DEFAULT_MAX_FRAMES + ").",
                "{\"type\":\"object\",\"properties\":{\"max_frames\":{\"type\":\"integer\",\"minimum\":1}}}",
                args -> {
                    int max = args.intVal("max_frames", DEFAULT_MAX_FRAMES);
                    return session.read(m -> runReport(m, m.run(max)));
                });
    }

    private SyncToolSpecification step() {
        return tools.tool("step", "Exécute n instructions CPU (défaut 1) puis s'arrête.",
                "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000000}}}",
                args -> {
                    int n = args.intVal("n", 1);
                    return session.read(m -> {
                        m.step(n);
                        // n instructions tiennent dans bien moins d'une seconde machine ; 
                        // 50 trames = garde-fou si le CPU est arrêté (STOP) ou en boucle d'attente.
                        Machine.RunResult r = m.run(n <= 10_000 ? 1 : 50);
                        return runReport(m, r);
                    });
                });
    }
}
```

Note : `step` fait `run(1)` : si les n instructions ne tiennent pas dans la trame, l'arrêt vient avec `NONE` et `frames_done=1`. Le rapport le dit, l'agent peut relancer.

- [ ] **Step 4 : tests verts** — `mvn -q test` (tous).

- [ ] **Step 5 : commit**

```bash
git add hatari-mcp
git commit -m "hatari-mcp: machine tools (state, reset, run_frames, run_until_pc, step)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FTU7a9LtSiamiFc9wxtVVR"
```

---

### Task 9 : `MemoryTools`

**Files:**
- Create: `hatari-mcp/src/main/java/fr/hatari/mcp/tools/MemoryTools.java`
- Modify: `hatari-mcp/src/main/java/fr/hatari/mcp/ToolCatalog.java`
- Create: `hatari-mcp/src/test/java/fr/hatari/mcp/tools/MemoryToolsTest.java`

**Interfaces:**
- Produces : `read_memory {addr, len?=64}` → `{addr, len, hexdump, bytes(hex string)}` ; `write_memory {addr, bytes:[hex...]}` ; `read_registers` → `Fmt.registers` ; `set_register {name, value}` ; `disassemble {addr?=PC, count?=16}` → `{addr, text}`.

- [ ] **Step 1 : tests**

```java
package fr.hatari.mcp.tools;

import fr.hatari.mcp.*;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryToolsTest {

    FakeMachine fake;
    ToolCatalog catalog;

    @BeforeEach
    void setUp() {
        fake = new FakeMachine();
        catalog = new ToolCatalog(new EmulatorSession(fake), McpJson.defaultMapper());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> call(String name, Map<String, Object> args) {
        CallToolResult r = ToolCatalogTest.call(catalog, name, args);
        assertNotEquals(Boolean.TRUE, r.isError(), () -> "erreur : " + r.content());
        return (Map<String, Object>) r.structuredContent();
    }

    @Test
    void readMemoryHexdumpsWithAscii() {
        fake.ram[0x2000] = 'H'; fake.ram[0x2001] = 'i';
        Map<String, Object> out = call("read_memory", Map.of("addr", "2000", "len", 4));
        assertEquals("002000", out.get("addr"));
        assertEquals("48690000", out.get("bytes"));
        assertTrue(((String) out.get("hexdump")).startsWith("002000: 48 69 00 00"));
        assertTrue(((String) out.get("hexdump")).contains("Hi.."));
    }

    @Test
    void readMemoryRefusesHugeLength() {
        CallToolResult r = ToolCatalogTest.call(catalog, "read_memory", Map.of("addr", "0", "len", 70000));
        assertEquals(Boolean.TRUE, r.isError());
    }

    @Test
    void writeMemoryAcceptsHexList() {
        call("write_memory", Map.of("addr", "3000", "bytes", List.of("DE", "AD")));
        assertEquals((byte) 0xDE, fake.ram[0x3000]);
        assertEquals((byte) 0xAD, fake.ram[0x3001]);
    }

    @Test
    void readRegistersDecodesFlags() {
        fake.regs[17] = 0x2704;
        Map<String, Object> out = call("read_registers", Map.of());
        assertEquals("2704", out.get("sr"));
        @SuppressWarnings("unchecked")
        Map<String, Object> flags = (Map<String, Object>) out.get("flags");
        assertEquals(true, flags.get("s"));
        assertEquals(7, flags.get("ipl"));
        assertEquals(true, flags.get("z"));
    }

    @Test
    void setRegisterDelegates() {
        call("set_register", Map.of("name", "d0", "value", "12345678"));
        assertEquals("reg d0=305419896", fake.calls.get(0));
    }

    @Test
    void disassembleDefaultsToPc() {
        fake.regs[16] = 0x1100;
        Map<String, Object> out = call("disassemble", Map.of());
        assertEquals("001100", out.get("addr"));
        assertTrue(((String) out.get("text")).contains("jmp"));
    }
}
```

- [ ] **Step 2 : vérifier l'échec** (« outil absent : read_memory »).

- [ ] **Step 3 : implémentation**

```java
package fr.hatari.mcp.tools;

import fr.hatari.mcp.Args;
import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Fmt;
import fr.hatari.mcp.Tools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mémoire, registres, désassemblage. */
public final class MemoryTools {

    static final int MAX_READ = 65536;

    private final EmulatorSession session;
    private final Tools tools;

    public MemoryTools(EmulatorSession session, Tools tools) {
        this.session = session;
        this.tools = tools;
    }

    public List<SyncToolSpecification> specs() {
        return List.of(readMemory(), writeMemory(), readRegisters(), setRegister(), disassemble());
    }

    private SyncToolSpecification readMemory() {
        return tools.tool("read_memory",
                "Lit len octets (défaut 64, max 65536) à l'adresse hex 24 bits. Lire la zone IO "
                        + "($FF8000+) a les mêmes effets de bord qu'un accès CPU.",
                "{\"type\":\"object\",\"required\":[\"addr\"],\"properties\":{"
                        + "\"addr\":{\"type\":\"string\"},\"len\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":65536}}}",
                args -> {
                    int addr = args.hex("addr");
                    int len = args.intVal("len", 64);
                    if (len < 1 || len > MAX_READ) throw new IllegalArgumentException("len doit être entre 1 et " + MAX_READ);
                    byte[] data = session.read(m -> m.readMemory(addr, len));
                    StringBuilder hex = new StringBuilder(len * 2);
                    for (byte b : data) hex.append(Fmt.hex8(b));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("addr", Fmt.hex24(addr));
                    out.put("len", len);
                    out.put("bytes", hex.toString());
                    out.put("hexdump", Fmt.hexdump(addr, data));
                    return out;
                });
    }

    private SyncToolSpecification writeMemory() {
        return tools.tool("write_memory",
                "Écrit une liste d'octets hex (\"DE\",\"AD\") à l'adresse hex donnée.",
                "{\"type\":\"object\",\"required\":[\"addr\",\"bytes\"],\"properties\":{"
                        + "\"addr\":{\"type\":\"string\"},\"bytes\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}",
                args -> {
                    int addr = args.hex("addr");
                    List<Integer> list = args.byteList("bytes");
                    byte[] data = new byte[list.size()];
                    for (int i = 0; i < data.length; i++) data[i] = (byte) (int) list.get(i);
                    session.mutate(m -> m.writeMemory(addr, data));
                    return Map.of("ok", true, "addr", Fmt.hex24(addr), "len", data.length);
                });
    }

    private SyncToolSpecification readRegisters() {
        return tools.tool("read_registers", "Registres 68000 (D0-D7, A0-A7, PC, SR, USP, ISP) et drapeaux.",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> session.read(m -> Fmt.registers(m.registers())));
    }

    private SyncToolSpecification setRegister() {
        return tools.tool("set_register", "Écrit un registre (pc, sr, d0-d7, a0-a7, usp, isp) ; value en hex.",
                "{\"type\":\"object\",\"required\":[\"name\",\"value\"],\"properties\":{"
                        + "\"name\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"}}}",
                args -> {
                    String name = args.str("name");
                    long value = Long.parseLong(stripHex(args.str("value")), 16);
                    session.mutate(m -> m.setRegister(name, value));
                    return Map.of("ok", true, "name", name, "value", Fmt.hex32(value));
                });
    }

    private SyncToolSpecification disassemble() {
        return tools.tool("disassemble", "Désassemble count instructions (défaut 16) à partir de addr (défaut PC).",
                "{\"type\":\"object\",\"properties\":{\"addr\":{\"type\":\"string\"},"
                        + "\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":256}}}",
                args -> session.read(m -> {
                    int addr = args.has("addr") ? args.hex("addr") : (int) m.registers().pc();
                    int count = args.intVal("count", 16);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("addr", Fmt.hex24(addr));
                    out.put("text", m.disassemble(addr, count));
                    return out;
                }));
    }

    static String stripHex(String s) {
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) return s.substring(2);
        if (s.startsWith("$")) return s.substring(1);
        return s;
    }
}
```

`ToolCatalog` : ajouter le champ `memoryTools = new MemoryTools(session, tools);` et `list.addAll(memoryTools.specs());`.

- [ ] **Step 4 : tests verts, commit**

```bash
git add hatari-mcp
git commit -m "hatari-mcp: memory, register and disassembly tools

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FTU7a9LtSiamiFc9wxtVVR"
```

---

### Task 10 : `DebugTools` — breakpoints, watchpoints, symboles, passerelle

**Files:**
- Create: `hatari-mcp/src/main/java/fr/hatari/mcp/tools/DebugTools.java`
- Modify: `ToolCatalog.java`
- Create: `hatari-mcp/src/test/java/fr/hatari/mcp/tools/DebugToolsTest.java`

**Interfaces:**
- Produces : `set_breakpoint {pc}` → `{id, pc, expression}` ; `clear_breakpoint {id | all}` ; `set_watchpoint {addr, len?=1, label?}` → `{id, addr, len, expression}` ; `clear_watchpoint {id | all}` ; `list_breakpoints` ; `load_symbols {path}` ; `debug_command {cmd}` → `{output}`.
- Mécanique : chaque id Java ↔ expression breakcond. La suppression envoie `b <position>` où la position est retrouvée en listant (`b`) et en cherchant la ligne qui contient l'expression. Format d'une ligne de listing Hatari : `  1: pc = $E00D98` (numéro, deux-points, expression) — vérifier sur la sortie réelle en Task 4 et adapter la regex `^\s*(\d+):\s*(.*)$`.

- [ ] **Step 1 : tests**

```java
package fr.hatari.mcp.tools;

import fr.hatari.mcp.*;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DebugToolsTest {

    FakeMachine fake;
    ToolCatalog catalog;

    @BeforeEach
    void setUp() {
        fake = new FakeMachine();
        catalog = new ToolCatalog(new EmulatorSession(fake), McpJson.defaultMapper());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> call(String name, Map<String, Object> args) {
        CallToolResult r = ToolCatalogTest.call(catalog, name, args);
        assertNotEquals(Boolean.TRUE, r.isError(), () -> "erreur : " + r.content());
        return (Map<String, Object>) r.structuredContent();
    }

    @Test
    void setBreakpointSendsBreakcondExpression() {
        fake.debugOutput = "CPU condition breakpoint 1 added.\n";
        Map<String, Object> out = call("set_breakpoint", Map.of("pc", "E00D98"));
        assertEquals(1, out.get("id"));
        assertEquals("pc = $E00D98", out.get("expression"));
        assertEquals("dbg b pc = $E00D98", fake.calls.get(0));
    }

    @Test
    void setWatchpointUsesValueChangedExpression() {
        fake.debugOutput = "CPU condition breakpoint 1 added.\n";
        Map<String, Object> out = call("set_watchpoint", Map.of("addr", "004000", "len", 2, "label", "score"));
        assertEquals("($4000).w ! ($4000).w", out.get("expression"));
        assertEquals("score", out.get("label"));
    }

    @Test
    void clearBreakpointFindsPositionInListing() {
        fake.debugOutput = "CPU condition breakpoint 1 added.\n";
        call("set_breakpoint", Map.of("pc", "E00D98"));
        fake.debugOutput = "CPU condition breakpoint 1 added.\n";
        call("set_breakpoint", Map.of("pc", "E01000"));
        fake.calls.clear();
        fake.debugOutput = "1 CPU breakpoints:\n  1: pc = $E00D98\n  2: pc = $E01000\n";
        Map<String, Object> out = call("clear_breakpoint", Map.of("id", 2));
        assertEquals(true, out.get("ok"));
        assertEquals("dbg b", fake.calls.get(0));
        assertEquals("dbg b 2", fake.calls.get(1));
    }

    @Test
    void clearAllSendsBAll() {
        call("clear_breakpoint", Map.of("all", true));
        assertEquals("dbg b all", fake.calls.get(0));
    }

    @Test
    void debugCommandPassthrough() {
        fake.debugOutput = "hello\n";
        Map<String, Object> out = call("debug_command", Map.of("cmd", "r"));
        assertEquals("hello\n", out.get("output"));
    }

    @Test
    void setBreakpointFailsIfHatariRefuses() {
        fake.debugOutput = "ERROR: invalid expression\n";
        CallToolResult r = ToolCatalogTest.call(catalog, "set_breakpoint", Map.of("pc", "E00D98"));
        assertEquals(Boolean.TRUE, r.isError());
    }
}
```

- [ ] **Step 2 : vérifier l'échec.**

- [ ] **Step 3 : implémentation**

```java
package fr.hatari.mcp.tools;

import fr.hatari.mcp.Args;
import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Fmt;
import fr.hatari.mcp.Machine;
import fr.hatari.mcp.Tools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Breakpoints, watchpoints, symboles, passerelle brute vers le debugger Hatari. */
public final class DebugTools {

    /** Ligne du listing « b » : position Hatari puis expression. */
    private static final Pattern LISTING = Pattern.compile("^\\s*(\\d+):\\s*(.+?)\\s*$");

    private record Point(int id, String kind, String expression, String label) {}

    private final EmulatorSession session;
    private final Tools tools;
    private final Map<Integer, Point> points = new LinkedHashMap<>();
    private int nextId = 1;

    public DebugTools(EmulatorSession session, Tools tools) {
        this.session = session;
        this.tools = tools;
    }

    public List<SyncToolSpecification> specs() {
        return List.of(setBreakpoint(), clearBreakpoint(), setWatchpoint(), clearWatchpoint(),
                listBreakpoints(), loadSymbols(), debugCommand());
    }

    /** Envoie « b <expr> » et vérifie l'acquittement de Hatari. */
    private Point add(String kind, String expression, String label) {
        String out = session.read(m -> m.debugCommand("b " + expression));
        if (!out.contains("added")) {
            throw new IllegalStateException("Hatari a refusé le breakpoint : " + out.trim());
        }
        Point p = new Point(nextId++, kind, expression, label);
        points.put(p.id(), p);
        return p;
    }

    /** Retire tous les points d'un type, ou un seul par id. */
    private Map<String, Object> clear(String kind, Args args) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (args.bool("all", false)) {
            session.read(m -> m.debugCommand("b all"));
            int n = (int) points.values().stream().filter(p -> p.kind().equals(kind)).count();
            points.values().removeIf(p -> p.kind().equals(kind));
            // « b all » retire aussi les points de l'autre type : on les repose.
            List<Point> others = new ArrayList<>(points.values());
            points.clear();
            for (Point p : others) add(p.kind(), p.expression(), p.label());
            out.put("ok", true);
            out.put("cleared", n);
            return out;
        }
        int id = args.intVal("id");
        Point p = points.get(id);
        if (p == null || !p.kind().equals(kind)) {
            out.put("ok", false);
            out.put("id", id);
            return out;
        }
        int position = session.read(m -> positionOf(m, p.expression()));
        if (position < 0) {
            points.remove(id);
            throw new IllegalStateException("point " + id + " absent du listing Hatari (déjà retiré par :once ?)");
        }
        session.read(m -> m.debugCommand("b " + position));
        points.remove(id);
        out.put("ok", true);
        out.put("id", id);
        return out;
    }

    /** Position Hatari (1-based) d'une expression dans le listing « b », -1 si absente. */
    public static int positionOf(Machine m, String expression) {
        for (String line : m.debugCommand("b").split("\n")) {
            Matcher mt = LISTING.matcher(line);
            if (mt.matches() && mt.group(2).startsWith(expression)) {
                return Integer.parseInt(mt.group(1));
            }
        }
        return -1;
    }

    private SyncToolSpecification setBreakpoint() {
        return tools.tool("set_breakpoint", "Pose un breakpoint sur PC = adresse hex. Retourne un id.",
                "{\"type\":\"object\",\"required\":[\"pc\"],\"properties\":{\"pc\":{\"type\":\"string\"}}}",
                args -> {
                    int pc = args.hex("pc");
                    Point p = add("bp", "pc = $" + Fmt.hex24(pc), null);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", p.id());
                    out.put("pc", Fmt.hex24(pc));
                    out.put("expression", p.expression());
                    return out;
                });
    }

    private SyncToolSpecification clearBreakpoint() {
        return tools.tool("clear_breakpoint", "Retire un breakpoint par id, ou tous avec all=true.",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"},\"all\":{\"type\":\"boolean\"}}}",
                args -> clear("bp", args));
    }

    private SyncToolSpecification setWatchpoint() {
        return tools.tool("set_watchpoint",
                "Arrête l'exécution quand la valeur à addr (len 1, 2 ou 4 octets) change. label optionnel.",
                "{\"type\":\"object\",\"required\":[\"addr\"],\"properties\":{\"addr\":{\"type\":\"string\"},"
                        + "\"len\":{\"type\":\"integer\",\"enum\":[1,2,4]},\"label\":{\"type\":\"string\"}}}",
                args -> {
                    int addr = args.hex("addr");
                    int len = args.intVal("len", 1);
                    String size = switch (len) { case 1 -> "b"; case 2 -> "w"; case 4 -> "l";
                        default -> throw new IllegalArgumentException("len doit valoir 1, 2 ou 4"); };
                    String ref = "($" + Integer.toHexString(addr).toUpperCase() + ")." + size;
                    Point p = add("wp", ref + " ! " + ref, args.str("label", null));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", p.id());
                    out.put("addr", Fmt.hex24(addr));
                    out.put("len", len);
                    out.put("label", p.label());
                    out.put("expression", p.expression());
                    return out;
                });
    }

    private SyncToolSpecification clearWatchpoint() {
        return tools.tool("clear_watchpoint", "Retire un watchpoint par id, ou tous avec all=true.",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"},\"all\":{\"type\":\"boolean\"}}}",
                args -> clear("wp", args));
    }

    private SyncToolSpecification listBreakpoints() {
        return tools.tool("list_breakpoints", "Liste les breakpoints et watchpoints posés par ce serveur, et le listing Hatari brut.",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (Point p : points.values()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", p.id());
                        row.put("kind", p.kind());
                        row.put("expression", p.expression());
                        row.put("label", p.label());
                        rows.add(row);
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("points", rows);
                    out.put("hatari", session.read(m -> m.debugCommand("b")));
                    return out;
                });
    }

    private SyncToolSpecification loadSymbols() {
        return tools.tool("load_symbols", "Charge un fichier de symboles (ex. ~/.hatari/etos1024k.sym) dans le debugger.",
                "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}}}",
                args -> {
                    String path = args.str("path").replaceFirst("^~", System.getProperty("user.home"));
                    String out = session.read(m -> m.debugCommand("symbols " + path));
                    return Map.of("output", out);
                });
    }

    private SyncToolSpecification debugCommand() {
        return tools.tool("debug_command",
                "Passerelle brute vers le debugger Hatari (voir doc/debugger.html) : 'r', 'm $4000', 'd', 'b', 'history'...",
                "{\"type\":\"object\",\"required\":[\"cmd\"],\"properties\":{\"cmd\":{\"type\":\"string\"}}}",
                args -> {
                    String cmd = args.str("cmd");
                    return Map.of("output", session.read(m -> m.debugCommand(cmd)));
                });
    }
}
```

`ToolCatalog` : `debugTools = new DebugTools(session, tools);` + `list.addAll(debugTools.specs());`.

- [ ] **Step 4 : tests verts. Vérifier le format réel du listing sur la vraie machine** : ajouter dans `CoreIT` :

```java
    @Test
    void breakpointListingMatchesParser() {
        core.debugCommand("b pc = $E00000");
        int pos = fr.hatari.mcp.tools.DebugTools.positionOf(core, "pc = $E00000");
        core.debugCommand("b " + pos);
        assertTrue(pos >= 1, "listing non reconnu : " + core.debugCommand("b"));
    }
```

`mvn -q test -Dtest=CoreIT`. Si la regex ne reconnaît pas le listing, l'adapter à la sortie affichée par l'assertion.

- [ ] **Step 5 : commit**

```bash
git add hatari-mcp
git commit -m "hatari-mcp: breakpoint, watchpoint, symbols and debugger passthrough tools

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FTU7a9LtSiamiFc9wxtVVR"
```

---

### Task 11 : `VideoTools` — screenshot

**Files:**
- Create: `hatari-mcp/src/main/java/fr/hatari/mcp/tools/VideoTools.java`
- Modify: `ToolCatalog.java`
- Create: `hatari-mcp/src/test/java/fr/hatari/mcp/tools/VideoToolsTest.java`

**Interfaces:**
- Produces : `screenshot {path?}` → `{path, width, height}`. PNG écrit avec `ImageIO`, `path` par défaut `${java.io.tmpdir}/hatari-mcp-screenshot.png`.

- [ ] **Step 1 : test**

```java
package fr.hatari.mcp.tools;

import fr.hatari.mcp.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VideoToolsTest {

    @Test
    void screenshotWritesPngOfFrame(@TempDir Path dir) throws Exception {
        FakeMachine fake = new FakeMachine();
        ToolCatalog catalog = new ToolCatalog(new EmulatorSession(fake), McpJson.defaultMapper());
        Path png = dir.resolve("shot.png");
        var r = ToolCatalogTest.call(catalog, "screenshot", Map.of("path", png.toString()));
        assertNotEquals(Boolean.TRUE, r.isError());
        BufferedImage img = ImageIO.read(png.toFile());
        assertEquals(320, img.getWidth());
        assertEquals(200, img.getHeight());
    }
}
```

- [ ] **Step 2 : échec, puis implémentation**

```java
package fr.hatari.mcp.tools;

import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Machine;
import fr.hatari.mcp.Tools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Capture d'écran. */
public final class VideoTools {

    private final EmulatorSession session;
    private final Tools tools;

    public VideoTools(EmulatorSession session, Tools tools) {
        this.session = session;
        this.tools = tools;
    }

    public List<SyncToolSpecification> specs() {
        return List.of(screenshot());
    }

    private SyncToolSpecification screenshot() {
        return tools.tool("screenshot",
                "Capture l'écran émulé en PNG. path optionnel (défaut : fichier temporaire). Lire ensuite le fichier pour voir l'image.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}",
                args -> {
                    String path = args.str("path", System.getProperty("java.io.tmpdir") + "/hatari-mcp-screenshot.png");
                    Machine.Frame f = session.read(Machine::frame);
                    BufferedImage img = new BufferedImage(f.width(), f.height(), BufferedImage.TYPE_INT_RGB);
                    img.setRGB(0, 0, f.width(), f.height(), f.pixels(), 0, f.width());
                    try {
                        ImageIO.write(img, "png", new File(path));
                    } catch (IOException e) {
                        throw new IllegalStateException("écriture PNG impossible : " + e.getMessage());
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("path", path);
                    out.put("width", f.width());
                    out.put("height", f.height());
                    return out;
                });
    }
}
```

`ToolCatalog` : ajouter `videoTools`.

- [ ] **Step 3 : tests verts, commit**

```bash
git add hatari-mcp
git commit -m "hatari-mcp: screenshot tool

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FTU7a9LtSiamiFc9wxtVVR"
```

---

### Task 12 : `InputTools` — clavier

**Files:**
- Create: `hatari-mcp/src/main/java/fr/hatari/mcp/keymap/StScancodes.java`
- Create: `hatari-mcp/src/main/java/fr/hatari/mcp/tools/InputTools.java`
- Modify: `ToolCatalog.java`
- Create: `hatari-mcp/src/test/java/fr/hatari/mcp/keymap/StScancodesTest.java`
- Create: `hatari-mcp/src/test/java/fr/hatari/mcp/tools/InputToolsTest.java`

**Interfaces:**
- Produces :
  - `StScancodes.Key(int scancode, boolean shift)` ; `static Optional<Key> ofName(String)` (noms : `RETURN`, `ESC`, `SPACE`, `TAB`, `BACKSPACE`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `F1`..`F10`, `CONTROL`, `SHIFT`, `ALT`, `DELETE`, `INSERT`, `HOME`, `HELP`, `UNDO`, `CAPSLOCK`, ou une lettre/chiffre/ponctuation ASCII) ; `static Optional<Key> ofChar(char)`.
  - Outils : `press_key {key, hold_frames?=2}` ; `type_keys {text, hold_frames?=2, gap_frames?=1}`.
  - Scancodes ST (clavier US, TOS américain d'EmuTOS) : `ESC=01`, `1..9,0 = 02..0B`, `-=0C`, `= =0D`, `BACKSPACE=0E`, `TAB=0F`, `Q W E R T Y U I O P = 10..19`, `[=1A`, `]=1B`, `RETURN=1C`, `CONTROL=1D`, `A S D F G H J K L = 1E..26`, `;=27`, `'=28`, `` `=29 ``, `SHIFT(gauche)=2A`, `\=2B`, `Z X C V B N M = 2C..32`, `,=33`, `.=34`, `/=35`, `SHIFT(droit)=36`, `ALT=38`, `SPACE=39`, `CAPSLOCK=3A`, `F1..F10 = 3B..44`, `HOME=47`, `UP=48`, `LEFT=4B`, `RIGHT=4D`, `DOWN=50`, `INSERT=52`, `DELETE=53`, `UNDO=61`, `HELP=62`.

- [ ] **Step 1 : tests**

```java
package fr.hatari.mcp.keymap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StScancodesTest {

    @Test
    void lowercaseLetterHasNoShift() {
        var k = StScancodes.ofChar('a').orElseThrow();
        assertEquals(0x1E, k.scancode());
        assertFalse(k.shift());
    }

    @Test
    void uppercaseLetterNeedsShift() {
        var k = StScancodes.ofChar('A').orElseThrow();
        assertEquals(0x1E, k.scancode());
        assertTrue(k.shift());
    }

    @Test
    void digitsAndPunctuation() {
        assertEquals(0x02, StScancodes.ofChar('1').orElseThrow().scancode());
        assertEquals(0x0B, StScancodes.ofChar('0').orElseThrow().scancode());
        var excl = StScancodes.ofChar('!').orElseThrow();
        assertEquals(0x02, excl.scancode());
        assertTrue(excl.shift());
        assertEquals(0x39, StScancodes.ofChar(' ').orElseThrow().scancode());
    }

    @Test
    void namedKeys() {
        assertEquals(0x1C, StScancodes.ofName("RETURN").orElseThrow().scancode());
        assertEquals(0x1C, StScancodes.ofName("return").orElseThrow().scancode());
        assertEquals(0x3B, StScancodes.ofName("F1").orElseThrow().scancode());
        assertEquals(0x44, StScancodes.ofName("F10").orElseThrow().scancode());
        assertEquals(0x1E, StScancodes.ofName("a").orElseThrow().scancode());
        assertTrue(StScancodes.ofName("BOGUS").isEmpty());
    }
}
```

```java
package fr.hatari.mcp.tools;

import fr.hatari.mcp.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InputToolsTest {

    @Test
    void pressKeyPressesRunsReleasesRuns() {
        FakeMachine fake = new FakeMachine();
        ToolCatalog catalog = new ToolCatalog(new EmulatorSession(fake), McpJson.defaultMapper());
        var r = ToolCatalogTest.call(catalog, "press_key", Map.of("key", "RETURN", "hold_frames", 3));
        assertNotEquals(Boolean.TRUE, r.isError());
        assertEquals(List.of("key 28 true", "run 3", "key 28 false", "run 1"), fake.calls);
    }

    @Test
    void typeKeysHandlesShiftedCharacters() {
        FakeMachine fake = new FakeMachine();
        ToolCatalog catalog = new ToolCatalog(new EmulatorSession(fake), McpJson.defaultMapper());
        var r = ToolCatalogTest.call(catalog, "type_keys", Map.of("text", "A\n"));
        assertNotEquals(Boolean.TRUE, r.isError());
        assertEquals(List.of(
                "key 42 true", "key 30 true", "run 2", "key 30 false", "key 42 false", "run 1",
                "key 28 true", "run 2", "key 28 false", "run 1"), fake.calls);
    }
}
```

- [ ] **Step 2 : échec, puis implémentation**

```java
package fr.hatari.mcp.keymap;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Table clavier Atari ST (disposition US, celle d'EmuTOS par défaut). */
public final class StScancodes {

    public record Key(int scancode, boolean shift) {}

    private static final Map<Character, Key> CHARS = new HashMap<>();
    private static final Map<String, Key> NAMES = new HashMap<>();

    static {
        String unshifted = "1234567890-=";
        String shifted = "!@#$%^&*()_+";
        for (int i = 0; i < unshifted.length(); i++) {
            CHARS.put(unshifted.charAt(i), new Key(0x02 + i, false));
            CHARS.put(shifted.charAt(i), new Key(0x02 + i, true));
        }
        row("qwertyuiop[]", 0x10);
        row("asdfghjkl;'`", 0x1E);
        row("\\zxcvbnm,./", 0x2B);
        String punctUnshifted = "[];'`\\,./";
        String punctShifted = "{}:\"~|<>?";
        for (int i = 0; i < punctUnshifted.length(); i++) {
            Key base = CHARS.get(punctUnshifted.charAt(i));
            CHARS.put(punctShifted.charAt(i), new Key(base.scancode(), true));
        }
        CHARS.put(' ', new Key(0x39, false));
        CHARS.put('\n', new Key(0x1C, false));
        CHARS.put('\t', new Key(0x0F, false));
        CHARS.put('\b', new Key(0x0E, false));
        CHARS.put((char) 27, new Key(0x01, false));

        name("ESC", 0x01); name("BACKSPACE", 0x0E); name("TAB", 0x0F); name("RETURN", 0x1C);
        name("ENTER", 0x72); name("CONTROL", 0x1D); name("SHIFT", 0x2A); name("ALT", 0x38);
        name("SPACE", 0x39); name("CAPSLOCK", 0x3A); name("HOME", 0x47); name("UP", 0x48);
        name("LEFT", 0x4B); name("RIGHT", 0x4D); name("DOWN", 0x50); name("INSERT", 0x52);
        name("DELETE", 0x53); name("UNDO", 0x61); name("HELP", 0x62);
        for (int i = 1; i <= 10; i++) name("F" + i, 0x3A + i);
    }

    private StScancodes() {}

    private static void row(String chars, int first) {
        for (int i = 0; i < chars.length(); i++) {
            char c = chars.charAt(i);
            CHARS.put(c, new Key(first + i, false));
            if (Character.isLetter(c)) CHARS.put(Character.toUpperCase(c), new Key(first + i, true));
        }
    }

    private static void name(String n, int scancode) {
        NAMES.put(n, new Key(scancode, false));
    }

    public static Optional<Key> ofChar(char c) {
        return Optional.ofNullable(CHARS.get(c));
    }

    /** Nom de touche (insensible à la casse) ou caractère unique. */
    public static Optional<Key> ofName(String name) {
        if (name == null || name.isEmpty()) return Optional.empty();
        Key k = NAMES.get(name.toUpperCase(Locale.ROOT));
        if (k != null) return Optional.of(k);
        if (name.length() == 1) return ofChar(name.charAt(0));
        return Optional.empty();
    }
}
```

```java
package fr.hatari.mcp.tools;

import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Machine;
import fr.hatari.mcp.Tools;
import fr.hatari.mcp.keymap.StScancodes;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.List;
import java.util.Map;

/** Clavier : press_key, type_keys. */
public final class InputTools {

    private static final int SHIFT = 0x2A;

    private final EmulatorSession session;
    private final Tools tools;

    public InputTools(EmulatorSession session, Tools tools) {
        this.session = session;
        this.tools = tools;
    }

    public List<SyncToolSpecification> specs() {
        return List.of(pressKey(), typeKeys());
    }

    /** Appuie (avec Shift si besoin), tient hold trames, relâche, laisse gap trames. */
    static void tap(Machine m, StScancodes.Key k, int hold, int gap) {
        if (k.shift()) m.key(SHIFT, true);
        m.key(k.scancode(), true);
        m.run(hold);
        m.key(k.scancode(), false);
        if (k.shift()) m.key(SHIFT, false);
        m.run(gap);
    }

    private SyncToolSpecification pressKey() {
        return tools.tool("press_key",
                "Appuie puis relâche une touche : nom (RETURN, ESC, SPACE, F1..F10, UP, DOWN, LEFT, RIGHT, "
                        + "TAB, BACKSPACE, DELETE, INSERT, HOME, HELP, UNDO, CONTROL, SHIFT, ALT, CAPSLOCK) "
                        + "ou caractère ASCII. hold_frames : durée d'appui (défaut 2).",
                "{\"type\":\"object\",\"required\":[\"key\"],\"properties\":{\"key\":{\"type\":\"string\"},"
                        + "\"hold_frames\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500}}}",
                args -> {
                    String name = args.str("key");
                    StScancodes.Key k = StScancodes.ofName(name)
                            .orElseThrow(() -> new IllegalArgumentException("touche inconnue : " + name));
                    int hold = args.intVal("hold_frames", 2);
                    session.mutate(m -> tap(m, k, hold, 1));
                    return Map.of("ok", true, "key", name, "scancode", String.format("%02X", k.scancode()));
                });
    }

    private SyncToolSpecification typeKeys() {
        return tools.tool("type_keys",
                "Tape une chaîne ASCII (\\n = RETURN). hold_frames par touche (défaut 2), gap_frames entre touches (défaut 1).",
                "{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"},"
                        + "\"hold_frames\":{\"type\":\"integer\",\"minimum\":1},\"gap_frames\":{\"type\":\"integer\",\"minimum\":0}}}",
                args -> {
                    String text = args.str("text");
                    int hold = args.intVal("hold_frames", 2);
                    int gap = args.intVal("gap_frames", 1);
                    session.mutate(m -> {
                        for (char c : text.toCharArray()) {
                            StScancodes.Key k = StScancodes.ofChar(c)
                                    .orElseThrow(() -> new IllegalArgumentException("caractère non mappé : '" + c + "'"));
                            tap(m, k, hold, gap);
                        }
                    });
                    return Map.of("ok", true, "typed", text.length());
                });
    }
}
```

`ToolCatalog` : ajouter `inputTools`.

Ajouter dans `CoreIT` un test bout en bout clavier : après le boot EmuTOS, presser `ESC` (« early console ») pendant l'écran d'accueil et vérifier que l'écran change :

```java
    @Test
    void escapeKeyChangesScreen() {
        core.reset(true);
        core.run(120);
        int[] before = core.frame().pixels();
        fr.hatari.mcp.tools.InputTools.tap(core, fr.hatari.mcp.keymap.StScancodes.ofName("ESC").orElseThrow(), 2, 60);
        int[] after = core.frame().pixels();
        assertFalse(java.util.Arrays.equals(before, after), "ESC n'a pas modifié l'écran d'accueil EmuTOS");
    }
```

- [ ] **Step 3 : tests verts (unitaires + `CoreIT`), commit**

```bash
git add hatari-mcp
git commit -m "hatari-mcp: keyboard tools with ST scancode table

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FTU7a9LtSiamiFc9wxtVVR"
```

---

### Task 13 : `DiskTools` — mount_disk, boot_disk

**Files:**
- Create: `hatari-mcp/src/main/java/fr/hatari/mcp/tools/DiskTools.java`
- Modify: `ToolCatalog.java`
- Create: `hatari-mcp/src/test/java/fr/hatari/mcp/tools/DiskToolsTest.java`

**Interfaces:**
- Produces : `mount_disk {path, drive?=0}` ; `boot_disk {path, frames?=300}` → rapport `MachineTools.runReport` + `path`. `eject_disk {drive?=0}`.

- [ ] **Step 1 : tests**

```java
package fr.hatari.mcp.tools;

import fr.hatari.mcp.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiskToolsTest {

    @Test
    void mountDiskInsertsInDriveA() {
        FakeMachine fake = new FakeMachine();
        ToolCatalog catalog = new ToolCatalog(new EmulatorSession(fake), McpJson.defaultMapper());
        var r = ToolCatalogTest.call(catalog, "mount_disk", Map.of("path", "/tmp/x.st"));
        assertNotEquals(Boolean.TRUE, r.isError());
        assertEquals(List.of("insert 0 /tmp/x.st"), fake.calls);
    }

    @Test
    void bootDiskMountsResetsAndRuns() {
        FakeMachine fake = new FakeMachine();
        ToolCatalog catalog = new ToolCatalog(new EmulatorSession(fake), McpJson.defaultMapper());
        var r = ToolCatalogTest.call(catalog, "boot_disk", Map.of("path", "/tmp/x.st", "frames", 100));
        assertNotEquals(Boolean.TRUE, r.isError());
        assertEquals(List.of("insert 0 /tmp/x.st", "reset true", "run 100"), fake.calls);
    }
}
```

- [ ] **Step 2 : échec, puis implémentation**

```java
package fr.hatari.mcp.tools;

import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Tools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Disquettes : mount_disk, eject_disk, boot_disk. */
public final class DiskTools {

    private final EmulatorSession session;
    private final Tools tools;

    public DiskTools(EmulatorSession session, Tools tools) {
        this.session = session;
        this.tools = tools;
    }

    public List<SyncToolSpecification> specs() {
        return List.of(mountDisk(), ejectDisk(), bootDisk());
    }

    private static String expand(String path) {
        return path.replaceFirst("^~", System.getProperty("user.home"));
    }

    private SyncToolSpecification mountDisk() {
        return tools.tool("mount_disk", "Insère une image disquette (.st, .msa, .dim, .stx, .zip...) dans le lecteur (0=A, 1=B).",
                "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"},"
                        + "\"drive\":{\"type\":\"integer\",\"enum\":[0,1]}}}",
                args -> {
                    String path = expand(args.str("path"));
                    int drive = args.intVal("drive", 0);
                    session.mutate(m -> m.insertDisk(drive, path));
                    return Map.of("ok", true, "path", path, "drive", drive);
                });
    }

    private SyncToolSpecification ejectDisk() {
        return tools.tool("eject_disk", "Éjecte la disquette du lecteur (0=A par défaut).",
                "{\"type\":\"object\",\"properties\":{\"drive\":{\"type\":\"integer\",\"enum\":[0,1]}}}",
                args -> {
                    int drive = args.intVal("drive", 0);
                    session.mutate(m -> m.ejectDisk(drive));
                    return Map.of("ok", true, "drive", drive);
                });
    }

    private SyncToolSpecification bootDisk() {
        return tools.tool("boot_disk", "Insère l'image en A:, reset à froid, exécute frames trames (défaut 300).",
                "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"},"
                        + "\"frames\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100000}}}",
                args -> {
                    String path = expand(args.str("path"));
                    int frames = args.intVal("frames", 300);
                    return session.read(m -> {
                        m.insertDisk(0, path);
                        m.reset(true);
                        Map<String, Object> out = new LinkedHashMap<>(MachineTools.runReport(m, m.run(frames)));
                        out.put("path", path);
                        return out;
                    });
                });
    }
}
```

`ToolCatalog` : ajouter `diskTools`. Version finale attendue de `ToolCatalog.all()` :

```java
        list.addAll(machineTools.specs());
        list.addAll(memoryTools.specs());
        list.addAll(debugTools.specs());
        list.addAll(videoTools.specs());
        list.addAll(inputTools.specs());
        list.addAll(diskTools.specs());
```

- [ ] **Step 3 : tests verts, commit**

```bash
git add hatari-mcp
git commit -m "hatari-mcp: floppy tools (mount, eject, boot)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FTU7a9LtSiamiFc9wxtVVR"
```

---

### Task 14 : lanceur, `.mcp.json`, README, smoke test bout en bout

**Files:**
- Create: `hatari-mcp/scripts/hatari-mcp.sh`
- Create: `.mcp.json` (racine du repo Hatari)
- Create: `hatari-mcp/README.md`
- Modify: `CLAUDE.md` (section « hatari-mcp »)
- Create: `hatari-mcp/scripts/smoke.sh`

- [ ] **Step 1 : lanceur**

`hatari-mcp/scripts/hatari-mcp.sh` :

```bash
#!/usr/bin/env bash
# Lanceur du serveur MCP stdio Hatari. stdout est réservé au protocole JSON-RPC :
# toute sortie Maven part sur stderr.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/target/hatari-mcp.jar"
CORE="${HATARI_CORE:-$ROOT/../build/src/libretro-hatari.so}"
TOS="${HATARI_TOS:-$HOME/.hatari/tos.img}"
JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.4-tem}"

if [[ ! -f "$JAR" || "$ROOT/pom.xml" -nt "$JAR" || -n "$(find "$ROOT/src/main" -newer "$JAR" -type f 2>/dev/null | head -1)" ]]; then
  (cd "$ROOT" && JAVA_HOME="$JAVA_HOME" mvn -q -DskipTests package 1>&2)
fi

exec "$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -jar "$JAR" \
  --core "$CORE" --tos "$TOS" "$@"
```

`chmod +x hatari-mcp/scripts/hatari-mcp.sh`.

- [ ] **Step 2 : `.mcp.json` à la racine du repo**

```json
{
  "mcpServers": {
    "hatari": {
      "command": "hatari-mcp/scripts/hatari-mcp.sh",
      "args": []
    }
  }
}
```

- [ ] **Step 3 : smoke test JSON-RPC**

`hatari-mcp/scripts/smoke.sh` :

```bash
#!/usr/bin/env bash
# Handshake MCP + ping + run_frames + screenshot, sans client MCP.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp)"
{
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}'
  echo '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ping","arguments":{}}}'
  echo '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"run_frames","arguments":{"n":300}}}'
  echo '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"screenshot","arguments":{"path":"/tmp/hatari-mcp-smoke.png"}}}'
  sleep 2
} | "$ROOT/scripts/hatari-mcp.sh" 2>/dev/null > "$OUT" || true
rg -q '"id":2' "$OUT" && rg -q '"frames_done":300' "$OUT" && rg -q 'hatari-mcp-smoke.png' "$OUT" \
  && echo "smoke OK ($(wc -l < "$OUT") réponses, /tmp/hatari-mcp-smoke.png)" \
  || { echo "smoke KO"; cat "$OUT"; exit 1; }
```

Lancer : `hatari-mcp/scripts/smoke.sh`. Attendu : `smoke OK`, et `/tmp/hatari-mcp-smoke.png` montre l'écran EmuTOS (vérifier avec l'outil Read).

- [ ] **Step 4 : README et CLAUDE.md**

`hatari-mcp/README.md` : but, prérequis (build Hatari avec `-DENABLE_LIBRETRO=1 -DLIBRETRO_INCLUDE_DIR=$PWD/../hatari-mcp/native/include`, JDK 25, EmuTOS dans `~/.hatari/tos.img`), lancement (`scripts/hatari-mcp.sh`, variables `HATARI_CORE`, `HATARI_TOS`), liste des outils avec un exemple d'arguments chacun, tests (`mvn test`, `mvn test -Dhatari.core=...`), licence GPL v2+.

`CLAUDE.md` : ajouter en fin de fichier :

```markdown
## hatari-mcp (branche `mcp`)

Serveur MCP stdio Java (`hatari-mcp/`, JDK 25, FFM) qui charge `build/src/libretro-hatari.so`. Les exports `hatari_*` vivent dans `src/retro/debug_api.c` ; le hook `DebugUI_SetStopHook` remplace le REPL readline quand un breakpoint tombe.

- Build du cœur : `cmake -DENABLE_LIBRETRO=1 -DLIBRETRO_INCLUDE_DIR=$PWD/../hatari-mcp/native/include ..` puis `cmake --build .` ; test C : `ctest -R debug-api -V`.
- Java : `export JAVA_HOME=~/.sdkman/candidates/java/25.0.4-tem` puis `cd hatari-mcp && mvn test` (les tests `*IT` utilisent la vraie `.so` et `~/.hatari/tos.img`, ignorés si absents).
- Lancement : `hatari-mcp/scripts/hatari-mcp.sh` (déclaré dans `.mcp.json`). Smoke : `hatari-mcp/scripts/smoke.sh`.
- Spec et plan : `docs/superpowers/specs/2026-09-08-hatari-mcp-design.md`, `docs/superpowers/plans/2026-09-08-hatari-mcp.md`.
```

- [ ] **Step 5 : tout vérifier puis commit**

```bash
cmake --build build -j$(nproc) 2>&1 | rg -i "warning|error"; (cd build && ctest -j$(nproc) | tail -2)
(cd hatari-mcp && mvn -q test) && hatari-mcp/scripts/smoke.sh
git add .mcp.json CLAUDE.md hatari-mcp
git commit -m "hatari-mcp: launcher, .mcp.json, README and end-to-end smoke test

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FTU7a9LtSiamiFc9wxtVVR"
```

---

## Auto-revue du plan

- **Couverture spec** : §4.2 hook → Task 1 ; §4.3 exports → Tasks 2-5 (tous listés dans `debug_api.h`) ; §4.4 test C → Tasks 2-5 ; §5.1 structure → Tasks 6-13 (`Options` remplace `Args` de la spec pour la ligne de commande, `Args` gardant son rôle toje d'arguments d'outil) ; §5.2 outils : ping, machine_state, reset, run_frames, run_until_pc, run_to_breakpoint, step (T8), read/write_memory, read_registers, set_register, disassemble (T9), set/clear_breakpoint, set/clear_watchpoint, load_symbols, debug_command, list_breakpoints (T10), screenshot (T11), press_key, type_keys (T12), mount_disk, eject_disk, boot_disk (T13) ; §5.3 erreurs → `Tools.invoke` (isError) et `HatariCore` (exceptions typées) ; §5.4 tests → unitaires par tâche, `CoreIT` (T7, T10, T12) ; §6 licence → T6 ; lanceur/`.mcp.json` → T14.
- **Cohérence des types** : `Machine.run(int) → RunResult(StopReason, int)`, `Machine.step(int)`, `Machine.debugCommand(String) → String`, `Machine.frame() → Frame(width, height, int[])` utilisés à l'identique dans `FakeMachine`, `HatariCore`, et tous les `*Tools`. `MachineTools.runReport(Machine, RunResult)` public static, réutilisé par `DiskTools`. `DebugTools.positionOf(Machine, String)` public static, utilisé par `CoreIT`.
- **Points d'attention à l'exécution** : chaîne d'acquittement `"added"` et format du listing `b` (T4/T10) à confronter à la sortie réelle ; `File_Exists` (T5) et `MakeFromSR` (T3) signatures à vérifier par `rg` avant usage ; invocation d'un `SyncToolSpecification` dans les tests (T6) à aligner sur le SDK 2.0.0.
