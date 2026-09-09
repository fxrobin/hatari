/*
 * Test the hatari_* debug API exported by the libretro core.
 * Runs TOS-less (--tos none): FAKE_TOS_LOOP is an arbitrary RAM address
 * used to install a self-loop and drive PC/step tests from a known state.
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
	int (*mem_read)(uint32_t, uint8_t *, size_t) = sym("hatari_mem_read");
	int (*mem_write)(uint32_t, const uint8_t *, size_t) = sym("hatari_mem_write");
	void (*regs_get)(uint32_t *) = sym("hatari_regs_get");
	int (*reg_set)(const char *, uint32_t) = sym("hatari_reg_set");
	int (*step)(int) = sym("hatari_step");

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

	/* memory round trip in RAM */
	uint8_t wbuf[4] = { 0xDE, 0xAD, 0xBE, 0xEF }, rbuf[4] = { 0 };
	CHECK(mem_write(0x2000, wbuf, 4) == 0);
	CHECK(mem_read(0x2000, rbuf, 4) == 0);
	CHECK(memcmp(wbuf, rbuf, 4) == 0);
	CHECK(mem_read(0xFFFFFF, rbuf, 4) == -1);

	/*
	 * Install a self-loop ("bra.s *", bytes $60 $FE) at FAKE_TOS_LOOP,
	 * point PC at it and run: this exercises mem_write + reg_set + run
	 * together, without relying on where the fake TOS idles.
	 */
	uint8_t loop[2] = { 0x60, 0xFE };
	CHECK(mem_write(FAKE_TOS_LOOP, loop, 2) == 0);
	/*
	 * Mask all autovector interrupt levels (SR IPL = 7): the fake TOS
	 * (faketos.s) does not fully set up its interrupt vector table, so
	 * letting a VBL/HBL/MFP interrupt fire while PC sits on our
	 * hand-installed loop would vector through garbage.
	 */
	CHECK(reg_set("SR", 0x2700) == 0);
	CHECK(reg_set("PC", FAKE_TOS_LOOP) == 0);
	CHECK(run(1, &reason, &done) == 0);

	uint32_t r[20];
	regs_get(r);
	printf("pc=%06x sr=%04x a7=%08x\n", r[16], r[17], r[15]);
	CHECK(r[16] >= FAKE_TOS_LOOP && r[16] < FAKE_TOS_LOOP + 2);

	/* register write */
	CHECK(reg_set("D3", 0x12345678) == 0);
	regs_get(r);
	CHECK(r[3] == 0x12345678);
	CHECK(reg_set("Z9", 1) == -1);

	/* single stepping from the self-loop: 3 instructions then STOP_STEPS */
	CHECK(reg_set("SR", 0x2700) == 0);
	CHECK(reg_set("PC", FAKE_TOS_LOOP) == 0);
	CHECK(step(3) == 0);
	CHECK(run(100, &reason, &done) == 0);
	printf("step 3: reason=%d done=%d\n", reason, done);
	CHECK(reason == 2);
	CHECK(done <= 1);

	printf("All debug-api tests finished successfully.\n");
	deinit();
	dlclose(dlh);
	return EXIT_SUCCESS;
}
