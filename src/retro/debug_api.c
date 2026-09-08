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
#include <stdint.h>
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
	Main_Init(argc, (char **)(intptr_t)argv);
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
