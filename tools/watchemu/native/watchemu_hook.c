/*
 * A basic-block hook for Unicorn that does not enter Python.
 *
 * Crossing into Python costs about 285 ns per basic block, and this firmware
 * averages a block every 5.4 instructions -- 7.4M crossings per 40M
 * instructions, roughly 30% of the emulator's wall clock. Everything else
 * (devices, probes, watchpoints, the tests) stays in Python; only the hot path
 * moves here.
 *
 * Deliberately free of Unicorn headers: the two functions it needs are passed
 * in as pointers by watchemu_init, so this builds anywhere with a C compiler
 * and cannot disagree with the unicorn build Python has loaded.
 *
 * The interrupt check is the only piece of emulator logic duplicated here, and
 * it is kept to the minimum that can be got right twice: Python computes the
 * priority of the best pending exception whenever the pending set changes, and
 * all this does is compare it against the CPU's three mask registers, exactly
 * as CortexM._masked does. Choosing *which* exception to take stays in Python
 * and happens once, after we stop.
 */

#include <stdint.h>

#ifdef _WIN32
#define EXPORT __declspec(dllexport)
#else
#define EXPORT
#endif

/* Nothing is pending. Matches the sentinel CortexM.pending_exception uses. */
#define NO_PENDING 256

/* Why the hook asked emulation to stop. */
#define REASON_NONE      0
#define REASON_EXCEPTION 1
#define REASON_IDLE      2
#define REASON_QUANTUM   3

typedef struct {
    /*
     * The emulated clock, counted the way the rest of the emulator counts it:
     * halfwords, so a 32-bit Thumb-2 instruction is two. Letting Unicorn's own
     * `count` argument end the slice instead would mix units -- that counts
     * instructions -- and the clock ran ~30% fast, which showed up as twice the
     * timer interrupts and a boot animation a frame ahead.
     */
    uint64_t instructions;
    uint64_t blocks;            /* basic blocks seen, for reporting */

    uint32_t quantum_cycles;    /* since the clocks were last advanced */
    uint32_t time_quantum;      /* how far they may drift before we hand back */

    int32_t  pending_priority;  /* priority of the best pending exception */
    int32_t  pending_is_nmi;    /* NMI ignores the masks entirely */

    uint32_t stop_reason;
    uint32_t idle_head;         /* 0 disables idle detection */
    uint32_t idle_max_iteration;
    uint32_t idle_hit;          /* set when the idle busy-wait was recognised */
    uint64_t idle_last_head;
    uint32_t idle_last_valid;

    /*
     * Stopping from a block hook leaves the block unexecuted, so it is hooked
     * again on resume. Counting it twice would be a rounding error at three
     * thousand stops a run and a 0.4% clock error at a hundred and fifty
     * thousand, which is what a per-quantum stop costs.
     */
    uint64_t stopped_address;
    uint32_t just_stopped;

    int32_t  reg_faultmask;     /* register ids, handed in by Python */
    int32_t  reg_primask;
    int32_t  reg_basepri;
} watchemu_state;

typedef int (*uc_reg_read_fn)(void *uc, int regid, void *value);
typedef int (*uc_emu_stop_fn)(void *uc);

static uc_reg_read_fn p_reg_read;
static uc_emu_stop_fn p_emu_stop;

EXPORT void watchemu_init(void *reg_read, void *emu_stop)
{
    p_reg_read = (uc_reg_read_fn)reg_read;
    p_emu_stop = (uc_emu_stop_fn)emu_stop;
}

EXPORT uint32_t watchemu_state_size(void)
{
    return (uint32_t)sizeof(watchemu_state);
}

/*
 * CortexM._masked, in the same order and with the same meaning. Returns
 * non-zero if the pending exception cannot be taken yet.
 */
static int masked(void *uc, watchemu_state *s)
{
    uint32_t faultmask = 0, primask = 0, basepri = 0;

    if (s->pending_is_nmi)
        return 0;

    p_reg_read(uc, s->reg_faultmask, &faultmask);
    if (faultmask & 1u)
        return 1;

    p_reg_read(uc, s->reg_primask, &primask);
    if ((primask & 1u) && s->pending_priority >= 0)
        return 1;

    p_reg_read(uc, s->reg_basepri, &basepri);
    basepri &= 0xFFu;
    if (basepri && s->pending_priority >= (int32_t)basepri)
        return 1;

    return 0;
}

EXPORT void watchemu_block(void *uc, uint64_t address, uint32_t size, void *user_data)
{
    watchemu_state *s = (watchemu_state *)user_data;
    uint32_t count = size >> 1;
    if (count == 0)
        count = 1;

    if (s->just_stopped && address == s->stopped_address) {
        /* Re-entering the block we stopped on: already counted, do not count
         * it again. The checks below still run, and none of them can fire a
         * second time -- Python has reset the quantum, taken the exception or
         * cleared the idle position before resuming us. */
        s->just_stopped = 0;
    } else {
        s->just_stopped = 0;
        s->instructions += count;
        s->quantum_cycles += count;
        s->blocks += 1;
    }

    /*
     * The idle busy-wait. Only skip on a second arrival at the head within a
     * few instructions, which means the loop went round and nothing else ran;
     * a first arrival, or one after a task has run, is not an idle core.
     */
    if (s->idle_head && (uint32_t)address == s->idle_head) {
        if (s->idle_last_valid &&
            (s->instructions - s->idle_last_head) <= s->idle_max_iteration) {
            s->idle_hit = 1;
            s->stop_reason = REASON_IDLE;
            s->stopped_address = address;
            s->just_stopped = 1;
            p_emu_stop(uc);
            return;
        }
        s->idle_last_head = s->instructions;
        s->idle_last_valid = 1;
    }

    /* Hand back so the clocks can be advanced and scheduled stimulus fired. */
    if (s->quantum_cycles >= s->time_quantum) {
        s->quantum_cycles = 0;
        s->stop_reason = REASON_QUANTUM;
        s->stopped_address = address;
        s->just_stopped = 1;
        p_emu_stop(uc);
        return;
    }

    /*
     * Deliverability is checked every block, not once per slice, because PendSV
     * latency has to be near zero: FreeRTOS sets it inside a critical section
     * and expects the switch the instant BASEPRI is released. The guard is a
     * single compare, so the common case costs nothing.
     */
    if (s->pending_priority < NO_PENDING && !masked(uc, s)) {
        s->stop_reason = REASON_EXCEPTION;
        s->stopped_address = address;
        s->just_stopped = 1;
        p_emu_stop(uc);
    }
}
