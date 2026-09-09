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
#include <unistd.h>

#include "main.h"
#include "configuration.h"
#include "cycles.h"
#include "debugcpu.h"
#include "debugui.h"
#include "debug_priv.h"
#include "m68000.h"
#include "reset.h"
#include "stMemory.h"
#include "sysdeps.h"
#include "newcpu.h"
#include "video.h"
#include "main_retro.h"
#include "68kDisass.h"
#include "screen.h"
#include "ikbd.h"
#include "floppy.h"
#include "file.h"
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
 * Reset_Cold()/Reset_Warm() only record the request: M68000_Reset() sets
 * quit_program and SPCFLAG_MODE_CHANGE, and the CPU core performs the
 * reset (PC reloaded from the reset vector, MODE_CHANGE cleared, debugger
 * hooks restored) in its m68k_go() loop. Retro_RequestCpuReset() makes the
 * next retro_run() enter that loop instead of the plain m68k_run(), so
 * emulation really restarts from the reset vector rather than resuming at
 * the old PC on freshly reset hardware.
 */
void hatari_reset(bool cold)
{
	if (cold)
		Reset_Cold();
	else
		Reset_Warm();
	Retro_RequestCpuReset();
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


/*-----------------------------------------------------------------------*/
/**
 * Capture what a function writes to debugOutput and to stderr (fd 2) into
 * out[outlen] (memstream text first, then stderr text), truncated with a
 * final '\0'. Most commands in src/debug (breakcond.c, symbols.c, ...)
 * report through fprintf(stderr, ...) rather than debugOutput, so both
 * streams have to be captured for hatari_dbg_command() to be useful.
 *
 * The fd 2 redirection is process-global (dup()/dup2() on STDERR_FILENO):
 * by contract this function, like the rest of this API, must only ever be
 * called from the single thread that drives retro_run(), never
 * concurrently with itself or with other code writing to stderr.
 *
 * stderr capture is best-effort: if tmpfile()/dup()/dup2() fails, fn(arg)
 * still runs and writes to the real stderr, and out only carries the
 * debugOutput text. This function itself returns false only when the
 * memstream backing debugOutput could not be created at all.
 */
static bool DebugApi_CaptureOutput(void (*fn)(void *), void *arg, char *out, size_t outlen)
{
	FILE *saved = debugOutput;
	char *buf = NULL;
	size_t buflen = 0;
	FILE *ms;
	FILE *errFile = NULL;
	int savedErrFd = -1;
	bool haveErrCapture = false;

	ms = open_memstream(&buf, &buflen);
	if (!ms)
		return false;
	debugOutput = ms;

	fflush(stderr);
	errFile = tmpfile();
	if (errFile)
	{
		savedErrFd = dup(STDERR_FILENO);
		if (savedErrFd >= 0)
		{
			if (dup2(fileno(errFile), STDERR_FILENO) >= 0)
				haveErrCapture = true;
			else
			{
				close(savedErrFd);
				savedErrFd = -1;
			}
		}
	}

	fn(arg);

	fflush(ms);
	debugOutput = saved;
	fclose(ms);

	if (haveErrCapture)
	{
		fflush(stderr);
		dup2(savedErrFd, STDERR_FILENO);
		close(savedErrFd);
	}

	if (outlen > 0)
	{
		size_t n = buflen < outlen - 1 ? buflen : outlen - 1;
		memcpy(out, buf, n);
		if (haveErrCapture && n < outlen - 1)
		{
			size_t room = outlen - 1 - n;
			rewind(errFile);
			n += fread(out + n, 1, room, errFile);
		}
		out[n] = '\0';
	}
	if (errFile)
		fclose(errFile);
	free(buf);
	return true;
}

/* Result of the last DebugUI_ParseLine() call run through
 * DebugApi_CaptureOutput(), consumed right away by hatari_dbg_command(). */
static int lastCmdDone;

/**
 * DebugApi_CaptureOutput() callback running one debugger command line.
 * Passed through a struct rather than a bare cast, so the const-ness of
 * the caller's command string is never discarded.
 */
static void DebugApi_RunCommand(void *arg)
{
	const char *const *cmd = arg;

	lastCmdDone = DebugUI_ParseLine(*cmd) ? 0 : 1;
}

/**
 * Execute a Hatari debugger command and capture its output.
 * Returns 0 if the command completed, 1 if it asked to resume emulation
 * (e.g. "c"), -1 on capture failure.
 */
int hatari_dbg_command(const char *cmd, char *out, size_t outlen)
{
	if (!DebugApi_CaptureOutput(DebugApi_RunCommand, &cmd, out, outlen))
		return -1;
	return lastCmdDone;
}


/* Parameters and result of a Disasm() call run through
 * DebugApi_CaptureOutput(), consumed right away by hatari_disasm(). */
typedef struct
{
	uint32_t addr;
	int count;
	uint32_t next;
} disasm_req_t;

/**
 * DebugApi_CaptureOutput() callback disassembling req->count instructions.
 */
static void DebugApi_RunDisasm(void *arg)
{
	disasm_req_t *req = arg;
	uaecptr next = req->addr;

	Disasm(debugOutput, (uaecptr)req->addr, &next, req->count);
	req->next = next;
}

/**
 * Disassemble count instructions starting at addr into out, and report
 * the address following the last decoded instruction in *next_pc (unless
 * NULL). Returns 0 on success, -1 on capture failure.
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


/*-----------------------------------------------------------------------*/
/**
 * Current host framebuffer (XRGB8888, pitch = width*4). Pointer stays
 * valid until the next resolution change. Returns -1 without writing
 * anything if any output pointer is NULL or no buffer is allocated yet.
 */
int hatari_framebuffer(const uint32_t **pixels, int *width, int *height)
{
	uint32_t *px = NULL;
	int w = 0, h = 0, pitch = 0;

	if (!pixels || !width || !height)
		return -1;
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
 * Insert a floppy image into drive 0 (A:) or 1 (B:). Returns -3 both when
 * Floppy_SetDiskFileName() refuses the image (e.g. already inserted in the
 * other drive) and when Floppy_InsertDiskIntoDrive() itself fails.
 */
int hatari_disk_insert(int drive, const char *path)
{
	if (drive < 0 || drive >= MAX_FLOPPYDRIVES)
		return -1;
	if (!File_Exists(path))
		return -2;
	if (!Floppy_SetDiskFileName(drive, path, NULL))
		return -3;
	if (!Floppy_InsertDiskIntoDrive(drive))
		return -3;
	return 0;
}


/**
 * Eject the floppy from given drive.
 */
int hatari_disk_eject(int drive)
{
	if (drive < 0 || drive >= MAX_FLOPPYDRIVES)
		return -1;
	Floppy_EjectDiskFromDrive(drive);
	return 0;
}
