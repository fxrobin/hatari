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
#include <strings.h>

#include "main.h"
#include "configuration.h"
#include "cycles.h"
#include "debugcpu.h"
#include "debugui.h"
#include "debug_priv.h"
#include "log.h"
#include "m68000.h"
#include "reset.h"
#include "stMemory.h"
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
 *
 * M68000_Reset() (called by Reset_Cold()/Reset_Warm()) raises
 * SPCFLAG_MODE_CHANGE so the CPU core's own m68k_go() loop notices the
 * reset and re-applies any pending CPU config change. The libretro core
 * only runs that loop once at startup (see has_cpu_config_changed in
 * main_retro.c) and drives every following frame with the lightweight
 * m68k_run(), which never clears SPCFLAG_MODE_CHANGE. Left set, it makes
 * do_specialties() return before it ever reaches the per-instruction
 * debugger hook, silently breaking hatari_step() after any reset. Clear
 * it here and restore the debugger hook the same way m68k_go() would.
 */
void hatari_reset(bool cold)
{
	if (cold)
		Reset_Cold();
	else
		Reset_Warm();
	M68000_UnsetSpecial(SPCFLAG_MODE_CHANGE);
	M68000_RestoreDebugger();
}


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

	for (i = 0; i < 16; i++)
		out[i] = regs.regs[i];
	out[HATARI_REG_PC] = M68000_GetPC();
	out[HATARI_REG_SR] = M68000_GetSR();
	out[HATARI_REG_USP] = regs.usp;
	out[HATARI_REG_ISP] = regs.isp;
}


/**
 * Set a register by name (PC, SR, D0-D7, A0-A7, USP, ISP, ...).
 * Returns 0 on success, -1 for an unknown register.
 *
 * PC and SR go through M68000_SetPC()/M68000_SetSR() rather than touching
 * regs.pc/regs.sr directly: on the cycle-exact CPU core a raw m68k_setpc()
 * leaves the two-word prefetch queue (regs.ir/regs.irc) stale, so the next
 * executed opcode would still come from the old code path instead of the
 * newly set PC.
 */
int hatari_reg_set(const char *name, uint32_t value)
{
	uint32_t *addr;

	if (strcasecmp(name, "PC") == 0)
	{
		M68000_SetPC(value);
		return 0;
	}
	if (strcasecmp(name, "SR") == 0)
	{
		M68000_SetSR(value & 0xffff);
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
