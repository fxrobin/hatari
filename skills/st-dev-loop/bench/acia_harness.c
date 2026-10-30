/*
 * Drive acia.prg through the libretro core (the one the Hatari MCP server
 * uses) with a fixed joystick / keyboard scenario, frame by frame.
 *
 * build: cc -O2 -I../../../hatari-mcp/native/include -o acia_harness acia_harness.c -ldl
 * run:   ./acia_harness <libretro-hatari.so> acia.prg
 *
 * Scenario (frame numbers = calls to hatari_run(1)):
 *   100-129 UP, 130-159 UP+FIRE, 160-189 RIGHT, 190- released,
 *   220 key down space ($39), 230 key up.
 */
#include <libretro.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

static void *dlh;
static int frame;

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
	bool up = frame >= 100 && frame < 160;
	bool fire = frame >= 130 && frame < 160;
	bool right = frame >= 160 && frame < 190;
	switch (id) {
	case RETRO_DEVICE_ID_JOYPAD_UP: return up;
	case RETRO_DEVICE_ID_JOYPAD_RIGHT: return right;
	case RETRO_DEVICE_ID_JOYPAD_B: return fire;
	default: return 0;
	}
}

int main(int argc, char *argv[])
{
	if (argc != 3) {
		fprintf(stderr, "usage: %s <libretro-hatari.so> <prog.prg>\n", argv[0]);
		return EXIT_FAILURE;
	}
	dlh = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
	if (!dlh) {
		fprintf(stderr, "dlopen: %s\n", dlerror());
		return EXIT_FAILURE;
	}
	((void (*)(retro_environment_t))sym("retro_set_environment"))(env_cb);
	((void (*)(retro_video_refresh_t))sym("retro_set_video_refresh"))(video_cb);
	((void (*)(retro_audio_sample_t))sym("retro_set_audio_sample"))(audio_cb);
	((void (*)(retro_audio_sample_batch_t))sym("retro_set_audio_sample_batch"))(audio_batch_cb);
	((void (*)(retro_input_poll_t))sym("retro_set_input_poll"))(input_poll_cb);
	((void (*)(retro_input_state_t))sym("retro_set_input_state"))(input_state_cb);
	void (*init_args)(int, const char *const *) = sym("hatari_init_args");
	int (*run)(int, int *, int *) = sym("hatari_run");
	void (*key)(uint8_t, bool) = sym("hatari_key");

	const char *args[] = { "hatari", "--machine", "ste", "--tos", "none", "--natfeats", "on",
	                       "--sound", "off", "--joy1", "real", argv[2] };
	init_args(sizeof(args) / sizeof(args[0]), args);

	int reason, done;
	for (frame = 0; frame < 340; frame++) {
		if (frame == 220)
			key(0x39, true);
		if (frame == 230)
			key(0x39, false);
		if (run(1, &reason, &done) != 0) {
			fprintf(stderr, "hatari_run failed at frame %d\n", frame);
			return EXIT_FAILURE;
		}
	}
	return EXIT_SUCCESS;
}
