/*
 * Scripted Hatari run through the libretro core (the one the Hatari MCP
 * server drives), without Java: boot a disk, press joystick/keys at given
 * frames, take screenshots (PPM), dump memory, run debugger commands.
 *
 * build: cc -O2 -I../../../hatari-mcp/native/include -o scenario_harness scenario_harness.c -ldl
 * run:   ./scenario_harness <libretro-hatari.so> <scenario> -- <hatari args...>
 *        e.g. -- --machine st --tos etos192uk.img --memsize 1 game.stx
 *
 * Scenario lines (frame = number of frames run so far, ascending order):
 *   <frame> joy <letters>          joystick 1 state: u d l r f, or 0 = released
 *   <frame> key <hex> down|up      ST scancode
 *   <frame> poke <hexaddr> <hexbytes>
 *   <frame> dump <file> <hexaddr> <hexlen>
 *   <frame> shot <file.ppm>
 *   <frame> dbg <debugger command...>   output printed on stdout
 *   <frame> onstop <debugger command...> run whenever the debugger stops the
 *                                  emulation (breakpoint, --debug-except...)
 *   <frame> end
 * '#' starts a comment.
 */
#include <libretro.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_ARGS 32
#define DBG_OUT_SIZE 65536
#define MAX_ONSTOP 8

static void *dlh;
static unsigned joy_state;	/* bit 0 up, 1 down, 2 left, 3 right, 4 fire */
static char onstop[MAX_ONSTOP][256];
static int onstop_count;

static void *sym(const char *name)
{
	void *fun = dlsym(dlh, name);
	if (!fun) {
		fprintf(stderr, "missing symbol %s\n", name);
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

/* libretro port 0 = ST joystick 1 (src/retro/joy_ui.c swaps ports 0 and 1) */
static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id)
{
	(void)index;
	if (port != 0 || device != RETRO_DEVICE_JOYPAD)
		return 0;
	switch (id) {
	case RETRO_DEVICE_ID_JOYPAD_UP: return (joy_state & 1) != 0;
	case RETRO_DEVICE_ID_JOYPAD_DOWN: return (joy_state & 2) != 0;
	case RETRO_DEVICE_ID_JOYPAD_LEFT: return (joy_state & 4) != 0;
	case RETRO_DEVICE_ID_JOYPAD_RIGHT: return (joy_state & 8) != 0;
	case RETRO_DEVICE_ID_JOYPAD_B: return (joy_state & 16) != 0;
	default: return 0;
	}
}

static int (*fb)(const uint32_t **, int *, int *);
static int (*mem_read)(uint32_t, uint8_t *, size_t);
static int (*mem_write)(uint32_t, const uint8_t *, size_t);
static int (*dbg)(const char *, char *, size_t);
static void (*key)(uint8_t, bool);

static void shot(const char *path)
{
	const uint32_t *px;
	int w, h;
	FILE *f;

	if (fb(&px, &w, &h) != 0 || !(f = fopen(path, "wb"))) {
		fprintf(stderr, "shot %s failed\n", path);
		return;
	}
	fprintf(f, "P6\n%d %d\n255\n", w, h);
	for (int i = 0; i < w * h; i++) {
		uint8_t rgb[3] = { px[i] >> 16, px[i] >> 8, px[i] };
		fwrite(rgb, 1, 3, f);
	}
	fclose(f);
}

static void dump(const char *path, uint32_t addr, uint32_t len)
{
	uint8_t *buf = malloc(len);
	FILE *f = fopen(path, "wb");

	if (!buf || !f || mem_read(addr, buf, len) != 0)
		fprintf(stderr, "dump %s failed\n", path);
	else
		fwrite(buf, 1, len, f);
	if (f)
		fclose(f);
	free(buf);
}

static void poke(uint32_t addr, const char *hex)
{
	uint8_t b;
	for (; hex[0] && hex[1]; hex += 2, addr++) {
		sscanf(hex, "%2hhx", &b);
		mem_write(addr, &b, 1);
	}
}

static unsigned parse_joy(const char *s)
{
	unsigned m = 0;
	for (; *s; s++)
		m |= *s == 'u' ? 1 : *s == 'd' ? 2 : *s == 'l' ? 4 : *s == 'r' ? 8 : *s == 'f' ? 16 : 0;
	return m;
}

static void run_dbg(int frame, const char *cmd)
{
	static char out[DBG_OUT_SIZE];

	out[0] = 0;
	dbg(cmd, out, sizeof(out));
	printf("[%d] dbg %s\n%s\n", frame, cmd, out);
}

/* Execute one scenario line; return false on 'end'. */
static bool run_action(int frame, char *op, char *rest)
{
	char a[256], b[64], c[64];

	rest[strcspn(rest, "\n")] = 0;
	if (!strcmp(op, "end"))
		return false;
	if (!strcmp(op, "joy") && sscanf(rest, "%63s", a) == 1)
		joy_state = parse_joy(a);
	else if (!strcmp(op, "key") && sscanf(rest, "%63s %63s", b, c) == 2)
		key((uint8_t)strtoul(b, NULL, 16), !strcmp(c, "down"));
	else if (!strcmp(op, "poke") && sscanf(rest, "%63s %255s", b, a) == 2)
		poke(strtoul(b, NULL, 16), a);
	else if (!strcmp(op, "dump") && sscanf(rest, "%255s %63s %63s", a, b, c) == 3)
		dump(a, strtoul(b, NULL, 16), strtoul(c, NULL, 16));
	else if (!strcmp(op, "shot") && sscanf(rest, "%255s", a) == 1)
		shot(a);
	else if (!strcmp(op, "dbg"))
		run_dbg(frame, rest);
	else if (!strcmp(op, "onstop") && onstop_count < MAX_ONSTOP)
		snprintf(onstop[onstop_count++], sizeof(onstop[0]), "%s", rest);
	else
		fprintf(stderr, "frame %d: bad action '%s %s'\n", frame, op, rest);
	return true;
}

int main(int argc, char *argv[])
{
	const char *args[MAX_ARGS] = { "hatari" };
	int nargs = 1, frame = 0, reason, done;
	char line[512];
	FILE *scn;

	if (argc < 4 || strcmp(argv[3], "--") != 0) {
		fprintf(stderr, "usage: %s <libretro-hatari.so> <scenario> -- <hatari args>\n", argv[0]);
		return EXIT_FAILURE;
	}
	for (int i = 4; i < argc && nargs < MAX_ARGS; i++)
		args[nargs++] = argv[i];
	dlh = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
	scn = fopen(argv[2], "r");
	if (!dlh || !scn) {
		fprintf(stderr, "cannot open core or scenario\n");
		return EXIT_FAILURE;
	}
	((void (*)(retro_environment_t))sym("retro_set_environment"))(env_cb);
	((void (*)(retro_video_refresh_t))sym("retro_set_video_refresh"))(video_cb);
	((void (*)(retro_audio_sample_t))sym("retro_set_audio_sample"))(audio_cb);
	((void (*)(retro_audio_sample_batch_t))sym("retro_set_audio_sample_batch"))(audio_batch_cb);
	((void (*)(retro_input_poll_t))sym("retro_set_input_poll"))(input_poll_cb);
	((void (*)(retro_input_state_t))sym("retro_set_input_state"))(input_state_cb);
	int (*run)(int, int *, int *) = sym("hatari_run");
	fb = sym("hatari_framebuffer");
	mem_read = sym("hatari_mem_read");
	mem_write = sym("hatari_mem_write");
	dbg = sym("hatari_dbg_command");
	key = sym("hatari_key");
	((void (*)(int, const char *const *))sym("hatari_init_args"))(nargs, args);

	while (fgets(line, sizeof(line), scn)) {
		char op[32];
		int at, n = 0;

		line[strcspn(line, "#")] = 0;
		if (sscanf(line, "%d %31s %n", &at, op, &n) < 2)
			continue;
		while (frame < at) {
			if (run(1, &reason, &done) != 0) {
				fprintf(stderr, "hatari_run failed at frame %d\n", frame);
				return EXIT_FAILURE;
			}
			frame += done;
			if (reason != 0) {
				printf("[%d] debugger stop (reason %d)\n", frame, reason);
				for (int i = 0; i < onstop_count; i++)
					run_dbg(frame, onstop[i]);
			}
		}
		if (!run_action(frame, op, line + n))
			break;
	}
	fclose(scn);
	return EXIT_SUCCESS;
}
