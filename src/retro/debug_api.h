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
