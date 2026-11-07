/*
 * rxtrace: QEMU TCG plugin that records what an x86-64 guest executes.
 *
 * Arguments (-plugin rxtrace.so,arg=value,...):
 *   mode=count|pc|full  count: only per-segment counters (stats file)
 *                       pc:    + every executed instruction (id only)
 *                       full:  + changed registers and every memory store
 *                       Segments named "idle" are always count-only; the
 *                       plugin switches modes at marks (qemu_plugin_reset).
 *   boot=on|off         record the boot segment in mode (default on);
 *                       off runs boot count-only, which is much faster
 *   arm=ADDR            (hex) record nothing until the instruction at ADDR
 *                       first runs, e.g. the kernel's physical entry point,
 *                       so boot starts at the kernel and not in the firmware
 *   out=PREFIX          each recorded segment goes to PREFIX.NNN.zst
 *                       (zstd), NNN = mark number, 000 = boot. A segment
 *                       file is self-contained: the mode switch at its
 *                       start flushes QEMU's translation cache, so every
 *                       instruction it runs is (re)translated inside it.
 *   stats=PATH          per-segment counters as text (always written)
 *   ctl=PATH            FIFO; each line written to it is a mark that ends
 *                       the current segment and starts one with that name.
 *                       A mark is acknowledged in PATH.ack once the new
 *                       mode is in effect (and the old file is closed).
 *   snap=PREFIX         before the first recorded instruction of a segment,
 *                       dump guest physical memory [0, snapmb MB) to
 *                       PREFIX.NNN.mem.zst
 *   snapmb=N            size of the snapshot in MB (default 128)
 *
 * Segment file records (little endian, packed; first byte is the type):
 *   'M' u64 insn_count, u16 len, name               first record
 *   'R' u8 nregs, then per reg: u8 namelen, name     register list (full)
 *   'I' u32 id, u64 vaddr, u8 size, bytes, u16 len, disas
 *                                                    insn translated
 *   'X' u8 vcpu, u32 id [, u32 mask, u64 value per set bit]
 *                                                    insn about to run;
 *       mask/values (full mode) are registers that changed since the
 *       previous 'X' of this vcpu, i.e. the state before this insn. The
 *       first 'X' of a segment carries every register.
 *   'W' u8 vcpu, u8 flags(1=io), u8 size, u64 vaddr, u64 paddr, bytes
 *                                                    store by the last 'X'
 *       flags 2: written by the CPU itself while delivering an interrupt
 *       or exception (the frame it pushes: [err,] rip, cs, rflags, rsp,
 *       ss). Plugins see no memory callback for those, so the 6 words at
 *       rsp are read back before the handler's first 'X'; the last one
 *       is only part of the frame if there was an error code.
 *   'D' u8 vcpu, u8 type(1=interrupt,2=exception), u64 from, u64 to
 *                                                    control transfer that
 *                                                    is not an instruction
 */
#define _GNU_SOURCE /* fwrite_unlocked */
#include <fcntl.h>
#include <glib.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <qemu-plugin.h>

QEMU_PLUGIN_EXPORT int qemu_plugin_version = QEMU_PLUGIN_VERSION;

enum mode { MODE_COUNT, MODE_PC, MODE_FULL };

#define MAXVCPU 16
#define MAXREGS 32

static const char *const want_regs[] = {
    "rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rbp", "rsp",
    "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15",
    "eflags", "cs", "ss", "ds", "es", "fs", "gs",
    "fs_base", "gs_base", "k_gs_base",
    "cr0", "cr2", "cr3", "cr4", "efer", NULL,
};

struct vcpu {
    int nregs;
    struct qemu_plugin_register *reg[MAXREGS];
    const char *name[MAXREGS];
    uint64_t last[MAXREGS];
    int rsp;                /* index of rsp in reg[], or -1 */
    gboolean trap_frame;    /* an interrupt/exception was just delivered */
    GByteArray *buf;
};

/* per-vcpu counters, summed per segment */
struct counters {
    uint64_t insns;
    uint64_t stores;
    uint64_t interrupts;
    uint64_t exceptions;
};

/* settings */
static enum mode rec_mode = MODE_FULL;
static bool record_boot = true;
static char *out_prefix;
static char *ctl_path;
static char *ack_path;
static char *snap_prefix;
static uint64_t snap_bytes = 128ull << 20;
static uint64_t arm_addr;

/* state; out_lock guards everything below that a vcpu thread and the
 * control thread can both touch */
static qemu_plugin_id_t plugin_id;
static GMutex out_lock;
static enum mode mode;          /* mode in effect now */
static FILE *out;               /* current segment file, or NULL */
static FILE *stats;
static gboolean regdef_in_seg;  /* 'R' written to the current file */
static gboolean snap_pending;
static struct vcpu vcpus[MAXVCPU];
static struct qemu_plugin_scoreboard *score;
static qemu_plugin_u64 c_insns, c_stores, c_ints, c_excs;
static uint32_t next_insn_id;

static GAsyncQueue *mark_queue;
static gint mark_pending;
static int nmarks;
static char *seg_name;
static struct counters seg_start;
static char *ack_after_reset;

/* arm=: false until the instruction at arm_addr runs. arm_ids are the
 * ids that instruction got from its translations. */
static volatile gboolean armed = TRUE;
#define MAX_ARM_IDS 64
static uint32_t arm_ids[MAX_ARM_IDS];
static gint n_arm_ids;

static void register_cbs(qemu_plugin_id_t id);

/* ---------- output ---------- */

struct rec {
    uint8_t b[16 + 8 * MAXREGS + 64];
    size_t n;
};

static inline void put8(struct rec *r, uint8_t v) { r->b[r->n++] = v; }
static inline void put16(struct rec *r, uint16_t v) { memcpy(r->b + r->n, &v, 2); r->n += 2; }
static inline void put32(struct rec *r, uint32_t v) { memcpy(r->b + r->n, &v, 4); r->n += 4; }
static inline void put64(struct rec *r, uint64_t v) { memcpy(r->b + r->n, &v, 8); r->n += 8; }

static FILE *open_zst(const char *path)
{
    g_autofree char *cmd = g_strdup_printf("zstd -q -3 -T0 -f -o '%s'", path);
    FILE *f = popen(cmd, "w");
    if (f)
        setvbuf(f, NULL, _IOFBF, 16 << 20);
    return f;
}

/* out_lock already serializes writers, so skip stdio's own lock */
static void emit(const void *p, size_t n)
{
    g_mutex_lock(&out_lock);
    if (out)
        fwrite_unlocked(p, 1, n, out);
    g_mutex_unlock(&out_lock);
}

/* 'R' from vcpu 0's register list; out_lock held */
static void write_regdef(void)
{
    struct vcpu *v = &vcpus[0];
    if (!out || regdef_in_seg || v->nregs == 0 || rec_mode != MODE_FULL)
        return;
    regdef_in_seg = TRUE;
    uint8_t hdr[2] = {'R', (uint8_t)v->nregs};
    fwrite(hdr, 1, 2, out);
    for (int i = 0; i < v->nregs; i++) {
        uint8_t len = strlen(v->name[i]);
        fwrite(&len, 1, 1, out);
        fwrite(v->name[i], 1, len, out);
    }
}

static void close_file(void)
{
    if (out) {
        pclose(out);
        out = NULL;
    }
}

/* start segment file nmarks; out_lock held */
static void open_segment(const char *name, uint64_t at)
{
    close_file();
    if (!out_prefix)
        return;
    g_autofree char *path = g_strdup_printf("%s.%03d.zst", out_prefix, nmarks);
    out = open_zst(path);
    if (!out)
        return;
    struct rec r = {.n = 0};
    uint16_t len = strlen(name);
    put8(&r, 'M');
    put64(&r, at);
    put16(&r, len);
    fwrite(r.b, 1, r.n, out);
    fwrite(name, 1, len, out);
    regdef_in_seg = FALSE;
    write_regdef();
    /* the first 'X' of every file carries every register */
    for (int i = 0; i < MAXVCPU; i++)
        for (int j = 0; j < vcpus[i].nregs; j++)
            vcpus[i].last[j] = ~0ull;
    snap_pending = snap_prefix != NULL;
}

/* ---------- segments and marks ---------- */

static struct counters totals(void)
{
    return (struct counters) {
        .insns = qemu_plugin_u64_sum(c_insns),
        .stores = qemu_plugin_u64_sum(c_stores),
        .interrupts = qemu_plugin_u64_sum(c_ints),
        .exceptions = qemu_plugin_u64_sum(c_excs),
    };
}

static void close_segment(void)
{
    struct counters now = totals();
    if (stats && seg_name) {
        fprintf(stats, "%s insns=%" PRIu64 " stores=%" PRIu64
                " interrupts=%" PRIu64 " exceptions=%" PRIu64 "\n",
                seg_name,
                now.insns - seg_start.insns,
                now.stores - seg_start.stores,
                now.interrupts - seg_start.interrupts,
                now.exceptions - seg_start.exceptions);
        fflush(stats);
    }
    seg_start = now;
}

/* vcpu context; out_lock held */
static void snapshot(void)
{
    snap_pending = FALSE;
    g_autofree char *path =
        g_strdup_printf("%s.%03d.mem.zst", snap_prefix, nmarks);
    FILE *f = open_zst(path);
    if (!f)
        return;
    GByteArray *buf = g_byte_array_new();
    const size_t chunk = 1 << 20;
    for (uint64_t pa = 0; pa < snap_bytes; pa += chunk) {
        if (qemu_plugin_read_memory_hwaddr(pa, buf, chunk) !=
            QEMU_PLUGIN_HWADDR_OPERATION_OK)
            break;
        fwrite(buf->data, 1, chunk, f);
    }
    g_byte_array_free(buf, TRUE);
    pclose(f);
}

static void write_ack(const char *name)
{
    if (!ack_path)
        return;
    FILE *ack = fopen(ack_path, "a");
    if (ack) {
        fprintf(ack, "%s\n", name);
        fclose(ack);
    }
}

static enum mode mode_for(const char *name)
{
    if (g_strcmp0(name, "idle") == 0)
        return MODE_COUNT;
    if (g_strcmp0(name, "boot") == 0 && !record_boot)
        return MODE_COUNT;
    return rec_mode;
}

static void reset_done(qemu_plugin_id_t id)
{
    register_cbs(id);
    g_mutex_lock(&out_lock);
    if (ack_after_reset) {
        write_ack(ack_after_reset);
        g_free(ack_after_reset);
        ack_after_reset = NULL;
    }
    g_mutex_unlock(&out_lock);
}

/* vcpu context, before an insn (pc/full) or a tb (count) */
static inline void check_marks(void)
{
    if (G_LIKELY(!g_atomic_int_get(&mark_pending)))
        return;
    g_mutex_lock(&out_lock);
    g_atomic_int_set(&mark_pending, 0);
    char *name;
    while ((name = g_async_queue_try_pop(mark_queue)) != NULL) {
        close_segment();
        g_free(seg_name);
        seg_name = name;
        nmarks++;
        enum mode want = mode_for(name);
        if (want != MODE_COUNT)
            open_segment(name, seg_start.insns);
        else
            close_file();
        if (want != mode) {
            /* retranslate everything with the new instrumentation */
            mode = want;
            g_free(ack_after_reset);
            ack_after_reset = g_strdup(name);
            qemu_plugin_reset(plugin_id, reset_done);
        } else {
            write_ack(name);
        }
    }
    g_mutex_unlock(&out_lock);
}

static gpointer ctl_thread(gpointer arg)
{
    /* O_RDWR keeps the FIFO open (no EOF) when the writer closes. */
    int fd = open(ctl_path, O_RDWR);
    if (fd < 0)
        return NULL;
    GString *line = g_string_new(NULL);
    char c;
    while (read(fd, &c, 1) == 1) {
        if (c != '\n') {
            g_string_append_c(line, c);
            continue;
        }
        g_async_queue_push(mark_queue, g_strdup(line->str));
        g_atomic_int_set(&mark_pending, 1);
        g_string_truncate(line, 0);
    }
    return NULL;
}

/* ---------- callbacks ---------- */

static void vcpu_init(qemu_plugin_id_t id, unsigned int vi)
{
    if (vi >= MAXVCPU)
        return;
    struct vcpu *v = &vcpus[vi];
    v->buf = g_byte_array_new();
    v->rsp = -1;
    GArray *regs = qemu_plugin_get_registers();
    for (int w = 0; want_regs[w] && v->nregs < MAXREGS; w++) {
        for (guint i = 0; i < regs->len; i++) {
            qemu_plugin_reg_descriptor *d =
                &g_array_index(regs, qemu_plugin_reg_descriptor, i);
            if (strcmp(d->name, want_regs[w]) == 0) {
                v->reg[v->nregs] = d->handle;
                v->name[v->nregs] = want_regs[w];
                v->last[v->nregs] = ~0ull;
                if (strcmp(want_regs[w], "rsp") == 0)
                    v->rsp = v->nregs;
                v->nregs++;
                break;
            }
        }
    }
    g_array_free(regs, TRUE);

    g_mutex_lock(&out_lock);
    write_regdef();         /* the boot segment opened before any vcpu */
    g_mutex_unlock(&out_lock);
}

static gboolean is_arm_id(uint32_t iid)
{
    int n = g_atomic_int_get(&n_arm_ids);
    for (int i = 0; i < n && i < MAX_ARM_IDS; i++)
        if (arm_ids[i] == iid)
            return TRUE;
    return FALSE;
}

/* The frame the CPU pushed when it delivered an interrupt/exception,
 * as stores of the step before the handler (see 'W' flags 2). */
static void cpu_pushed_frame(struct vcpu *v, unsigned int vi)
{
    g_byte_array_set_size(v->buf, 0);
    if (v->rsp < 0 || qemu_plugin_read_register(v->reg[v->rsp], v->buf) < 8)
        return;
    uint64_t rsp;
    memcpy(&rsp, v->buf->data, 8);
    /* the word above a frame without an error code may be past the top
     * of the stack (a guard page): read it on its own */
    uint64_t words[6];
    int n = 0;
    for (int i = 0; i < 6; i++) {
        g_byte_array_set_size(v->buf, 0);
        if (!qemu_plugin_read_memory_vaddr(rsp + 8 * i, v->buf, 8))
            break;
        memcpy(&words[i], v->buf->data, 8);
        n++;
    }
    for (int i = 0; i < n; i++) {
        uint64_t va = rsp + 8 * i, pa;
        if (!qemu_plugin_translate_vaddr(va, &pa))
            continue;
        struct rec r = {.n = 0};
        put8(&r, 'W');
        put8(&r, vi);
        put8(&r, 2);
        put8(&r, 8);
        put64(&r, va);
        put64(&r, pa);
        put64(&r, words[i]);
        emit(r.b, r.n);
    }
}

static void insn_exec(unsigned int vi, void *udata)
{
    check_marks();
    if (G_UNLIKELY(!armed)) {
        if (!is_arm_id((uint32_t)(uintptr_t)udata))
            return;
        armed = TRUE;
    }
    if (G_UNLIKELY(snap_pending)) {
        g_mutex_lock(&out_lock);
        if (snap_pending)
            snapshot();
        g_mutex_unlock(&out_lock);
    }
    if (mode == MODE_FULL && vi < MAXVCPU && vcpus[vi].trap_frame) {
        vcpus[vi].trap_frame = FALSE;
        cpu_pushed_frame(&vcpus[vi], vi);
    }
    struct rec r = {.n = 0};
    put8(&r, 'X');
    put8(&r, vi);
    put32(&r, (uint32_t)(uintptr_t)udata);
    if (mode == MODE_FULL && vi < MAXVCPU) {
        struct vcpu *v = &vcpus[vi];
        size_t mask_at = r.n;
        uint32_t mask = 0;
        put32(&r, 0);
        for (int i = 0; i < v->nregs; i++) {
            g_byte_array_set_size(v->buf, 0);
            int sz = qemu_plugin_read_register(v->reg[i], v->buf);
            uint64_t val = 0;
            if (sz > 0)
                memcpy(&val, v->buf->data, sz < 8 ? sz : 8);
            if (val != v->last[i]) {
                v->last[i] = val;
                mask |= 1u << i;
                put64(&r, val);
            }
        }
        memcpy(r.b + mask_at, &mask, 4);
    }
    emit(r.b, r.n);
}

static void tb_exec(unsigned int vi, void *udata)
{
    check_marks();
}

static void mem_store(unsigned int vi, qemu_plugin_meminfo_t info,
                      uint64_t vaddr, void *udata)
{
    if (!armed)
        return;
    struct qemu_plugin_hwaddr *hw = qemu_plugin_get_hwaddr(info, vaddr);
    uint64_t pa = hw ? qemu_plugin_hwaddr_phys_addr(hw) : ~0ull;
    uint8_t flags = (hw && qemu_plugin_hwaddr_is_io(hw)) ? 1 : 0;
    unsigned size = 1u << qemu_plugin_mem_size_shift(info);
    qemu_plugin_mem_value mv = qemu_plugin_mem_get_value(info);

    struct rec r = {.n = 0};
    put8(&r, 'W');
    put8(&r, vi);
    put8(&r, flags);
    put8(&r, size);
    put64(&r, vaddr);
    put64(&r, pa);
    switch (mv.type) {
    case QEMU_PLUGIN_MEM_VALUE_U8:  put8(&r, mv.data.u8); break;
    case QEMU_PLUGIN_MEM_VALUE_U16: put16(&r, mv.data.u16); break;
    case QEMU_PLUGIN_MEM_VALUE_U32: put32(&r, mv.data.u32); break;
    case QEMU_PLUGIN_MEM_VALUE_U64: put64(&r, mv.data.u64); break;
    case QEMU_PLUGIN_MEM_VALUE_U128:
        put64(&r, mv.data.u128.low);
        put64(&r, mv.data.u128.high);
        break;
    }
    emit(r.b, r.n);
}

static void discon(qemu_plugin_id_t id, unsigned int vi,
                   enum qemu_plugin_discon_type type,
                   uint64_t from_pc, uint64_t to_pc)
{
    if (type == QEMU_PLUGIN_DISCON_INTERRUPT)
        qemu_plugin_u64_add(c_ints, vi, 1);
    else if (type == QEMU_PLUGIN_DISCON_EXCEPTION)
        qemu_plugin_u64_add(c_excs, vi, 1);
    if (mode == MODE_COUNT || !armed)
        return;
    if (mode == MODE_FULL && vi < MAXVCPU)
        vcpus[vi].trap_frame = TRUE;
    struct rec r = {.n = 0};
    put8(&r, 'D');
    put8(&r, vi);
    put8(&r, (uint8_t)type);
    put64(&r, from_pc);
    put64(&r, to_pc);
    emit(r.b, r.n);
}

static void tb_trans(qemu_plugin_id_t id, struct qemu_plugin_tb *tb)
{
    size_t n = qemu_plugin_tb_n_insns(tb);

    if (mode == MODE_COUNT)
        qemu_plugin_register_vcpu_tb_exec_cb(tb, tb_exec,
                                             QEMU_PLUGIN_CB_NO_REGS, NULL);

    for (size_t i = 0; i < n; i++) {
        struct qemu_plugin_insn *insn = qemu_plugin_tb_get_insn(tb, i);

        qemu_plugin_register_vcpu_insn_exec_inline_per_vcpu(
            insn, QEMU_PLUGIN_INLINE_ADD_U64, c_insns, 1);
        qemu_plugin_register_vcpu_mem_inline_per_vcpu(
            insn, QEMU_PLUGIN_MEM_W, QEMU_PLUGIN_INLINE_ADD_U64, c_stores, 1);

        if (mode == MODE_COUNT)
            continue;

        uint32_t iid = g_atomic_int_add((gint *)&next_insn_id, 1);
        if (!armed && qemu_plugin_insn_vaddr(insn) == arm_addr) {
            int k = g_atomic_int_get(&n_arm_ids);
            if (k < MAX_ARM_IDS) {
                arm_ids[k] = iid;
                g_atomic_int_set(&n_arm_ids, k + 1);
            }
        }
        uint8_t bytes[16];
        size_t size = qemu_plugin_insn_data(insn, bytes, sizeof(bytes));
        char *dis = qemu_plugin_insn_disas(insn);
        uint16_t dlen = dis ? strlen(dis) : 0;

        struct rec r = {.n = 0};
        put8(&r, 'I');
        put32(&r, iid);
        put64(&r, qemu_plugin_insn_vaddr(insn));
        put8(&r, (uint8_t)size);
        memcpy(r.b + r.n, bytes, size);
        r.n += size;
        put16(&r, dlen);
        g_mutex_lock(&out_lock);
        if (out) {
            fwrite(r.b, 1, r.n, out);
            fwrite(dis, 1, dlen, out);
        }
        g_mutex_unlock(&out_lock);
        g_free(dis);

        qemu_plugin_register_vcpu_insn_exec_cb(
            insn, insn_exec,
            mode == MODE_FULL ? QEMU_PLUGIN_CB_R_REGS : QEMU_PLUGIN_CB_NO_REGS,
            (void *)(uintptr_t)iid);
        if (mode == MODE_FULL)
            qemu_plugin_register_vcpu_mem_cb(insn, mem_store,
                                             QEMU_PLUGIN_CB_NO_REGS,
                                             QEMU_PLUGIN_MEM_W, NULL);
    }
}

static void at_exit(qemu_plugin_id_t id, void *p)
{
    g_mutex_lock(&out_lock);
    close_segment();
    if (stats) {
        fclose(stats);
        stats = NULL;
    }
    close_file();
    g_mutex_unlock(&out_lock);
}

/* everything qemu_plugin_reset() drops; vcpu_init has run by then */
static void register_cbs(qemu_plugin_id_t id)
{
    qemu_plugin_register_vcpu_tb_trans_cb(id, tb_trans);
    qemu_plugin_register_vcpu_discon_cb(id, QEMU_PLUGIN_DISCON_INTERRUPT |
                                        QEMU_PLUGIN_DISCON_EXCEPTION, discon);
    qemu_plugin_register_atexit_cb(id, at_exit, NULL);
}

/* ---------- install ---------- */

QEMU_PLUGIN_EXPORT int qemu_plugin_install(qemu_plugin_id_t id,
                                           const qemu_info_t *info,
                                           int argc, char **argv)
{
    const char *stats_path = NULL;

    for (int i = 0; i < argc; i++) {
        g_auto(GStrv) kv = g_strsplit(argv[i], "=", 2);
        if (!kv[0] || !kv[1]) {
            fprintf(stderr, "rxtrace: bad argument %s\n", argv[i]);
            return -1;
        }
        if (g_strcmp0(kv[0], "mode") == 0) {
            if (g_strcmp0(kv[1], "count") == 0)
                rec_mode = MODE_COUNT;
            else if (g_strcmp0(kv[1], "pc") == 0)
                rec_mode = MODE_PC;
            else if (g_strcmp0(kv[1], "full") == 0)
                rec_mode = MODE_FULL;
            else
                return -1;
        } else if (g_strcmp0(kv[0], "boot") == 0) {
            if (!qemu_plugin_bool_parse(kv[0], kv[1], &record_boot))
                return -1;
        } else if (g_strcmp0(kv[0], "out") == 0) {
            out_prefix = g_strdup(kv[1]);
        } else if (g_strcmp0(kv[0], "stats") == 0) {
            stats_path = kv[1];
            stats = fopen(stats_path, "w");
        } else if (g_strcmp0(kv[0], "ctl") == 0) {
            ctl_path = g_strdup(kv[1]);
        } else if (g_strcmp0(kv[0], "snap") == 0) {
            snap_prefix = g_strdup(kv[1]);
        } else if (g_strcmp0(kv[0], "arm") == 0) {
            arm_addr = g_ascii_strtoull(kv[1], NULL, 16);
            armed = FALSE;
        } else if (g_strcmp0(kv[0], "snapmb") == 0) {
            snap_bytes = g_ascii_strtoull(kv[1], NULL, 10) << 20;
        } else {
            fprintf(stderr, "rxtrace: unknown option %s\n", kv[0]);
            return -1;
        }
    }
    if (rec_mode == MODE_COUNT)
        out_prefix = NULL;

    score = qemu_plugin_scoreboard_new(sizeof(struct counters));
    c_insns = qemu_plugin_scoreboard_u64_in_struct(score, struct counters, insns);
    c_stores = qemu_plugin_scoreboard_u64_in_struct(score, struct counters, stores);
    c_ints = qemu_plugin_scoreboard_u64_in_struct(score, struct counters, interrupts);
    c_excs = qemu_plugin_scoreboard_u64_in_struct(score, struct counters, exceptions);

    seg_name = g_strdup("boot");
    mode = mode_for(seg_name);
    if (mode != MODE_COUNT)
        open_segment(seg_name, 0);

    mark_queue = g_async_queue_new();
    if (ctl_path) {
        ack_path = g_strdup_printf("%s.ack", ctl_path);
        unlink(ack_path);
        g_thread_new("rxtrace-ctl", ctl_thread, NULL);
    }

    plugin_id = id;
    qemu_plugin_register_vcpu_init_cb(id, vcpu_init);
    register_cbs(id);
    return 0;
}
