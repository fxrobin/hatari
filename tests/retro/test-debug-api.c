/*
 * Test the hatari_* debug API exported by the libretro core.
 * Runs TOS-less (--tos none): the fake TOS loops at $1100.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
#include <libretro.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define FAKE_TOS_LOOP 0x1100

/*
 * Checks below use an explicit condition instead of assert() because this
 * test must keep verifying behavior (and evaluating its side-effecting
 * calls) even in a release/NDEBUG build, where assert() compiles to nothing.
 */
#define CHECK(cond) \
	do { \
		if (!(cond)) \
		{ \
			fprintf(stderr, "%s:%d: check failed: %s\n", __FILE__, __LINE__, #cond); \
			exit(EXIT_FAILURE); \
		} \
	} while (0)

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
	int rc = run(10, &reason, &done);
	counters(&vbl1, &cyc1);
	printf("run 10: reason=%d done=%d vbl %u->%u\n", reason, done, vbl0, vbl1);
	CHECK(rc == 0);
	CHECK(reason == 0);
	CHECK(done == 10);
	CHECK(vbl1 - vbl0 == 10);
	CHECK(cyc1 > cyc0);

	reset(true);
	rc = run(1, &reason, &done);
	CHECK(rc == 0);
	CHECK(done == 1);

	printf("All debug-api tests finished successfully.\n");
	deinit();
	dlclose(dlh);
	return EXIT_SUCCESS;
}
