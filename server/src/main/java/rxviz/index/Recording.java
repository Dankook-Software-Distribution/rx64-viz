package rxviz.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import rxviz.config.KernelProfile;
import rxviz.config.KernelProfile.Location;
import rxviz.config.KernelProfile.Program;
import rxviz.elf.LineTable;

/**
 * One indexed command: its steps, event tree, and the guest's registers
 * and memory at any step. "State at step s" means just before the
 * instruction of step s executes.
 */
public final class Recording {

    /** An instruction as translated by QEMU, with the function it is in (cached). */
    public static final class Insn {
        private final long vaddr;
        private final byte[] bytes;
        private final InsnKind kind;
        /* CpuTables.LGDT / LIDT, or 0 */
        final byte table;
        /* function name, valid for fnOf (the kernel profile or a user Program) */
        Object fnOf;
        String fn;

        Insn(long vaddr, byte[] bytes) {
            this.vaddr = vaddr;
            this.bytes = bytes;
            this.kind = InsnKind.of(bytes);
            this.table = CpuTables.tableLoad(bytes);
        }

        public long vaddr() { return vaddr; }
        public byte[] bytes() { return bytes; }
        public InsnKind kind() { return kind; }
    }

    /** start: where to open it (a command's first user-mode step; 0 for a boot). */
    public record Summary(int steps, long userSteps, long kernelSteps, long stores,
                          int syscalls, int interrupts, int exceptions, int switches,
                          int nodes, long chunkBytes, long indexMillis, int start) {
    }

    /** A store: step, physical address, size in bytes, the value written. */
    public interface StoreVisitor {
        void store(int step, long pa, int size, long value);
    }

    /** A store with the value it overwrote. */
    public interface OldStoreVisitor {
        void store(int step, long pa, int size, long value, long old);
    }

    /** Everything about one step, with its registers decoded once. */
    public record View(int step, long[] regs, Insn insn, int cpl, Program program,
                       Location location) {
    }

    /** A store and the value it overwrote. */
    public record Store(int step, long paddr, int size, long value, long old) {
    }

    /** At most this many 128MB memory images are kept, over all recordings. */
    private static final int MAX_IMAGES = 2;
    private static final Map<Recording, Boolean> images =
            new LinkedHashMap<>(4, 0.75f, true);

    private final String id;
    private final String command;
    private final KernelProfile kernel;
    private final String[] regNames;
    private final int iCs, iCr3, iCr0;
    private final int steps;
    private final Insns insns;
    private final ChunkStore chunks;
    private final Checkpoints ckpts;
    private final Tree tree;
    private final Programs programs;
    private final int memSize;
    private final Summary summary;
    /* user-mode steps before each block of Timeline.BLOCK steps (prefix sums) */
    private final long[] userBefore;
    /* steps that run lgdt / lidt, in order */
    private final int[] gdtLoads, idtLoads;
    private final int firstUser;
    /* control-register changes, table loads, the first user step (Timeline) */
    private final List<Timeline.Milestone> milestones;

    private final int[] cr3Switches;
    private final ProcStates procStates;

    /** Steps of the instructions that loaded cr3 (every page-table switch), in order. */
    int[] cr3Switches() {
        return cr3Switches;
    }

    /** Every change of a proc[k].state, recorded while indexing. */
    ProcStates procStates() {
        return procStates;
    }

    public List<Timeline.Milestone> milestones() {
        return milestones;
    }

    private MemoryImage image;
    private int imageStep;

    /* source lines: one small integer per distinct line, per insn id, and
     * per chunk (one key per step) */
    private final Map<LineTable.Src, Integer> srcKey = new HashMap<>();
    private final List<LineTable.Src> keySrc = new ArrayList<>();
    private final Map<Integer, Integer> insnKey = new HashMap<>();
    private final Map<Integer, int[]> lineChunks = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, int[]> e) {
            return size() > 64;
        }
    };

    Recording(String id, String command, KernelProfile kernel, String[] regNames,
              int steps, Insns insns, ChunkStore chunks, Checkpoints ckpts, Tree tree,
              Programs programs, int memSize, MemoryImage finalImage, short[] userBlocks,
              int[] gdtLoads, int[] idtLoads, int firstUser, Summary summary,
              List<Timeline.Milestone> milestones, int[] cr3Switches, ProcStates procStates) {
        this.firstUser = firstUser;
        this.cr3Switches = cr3Switches;
        this.procStates = procStates;
        this.milestones = List.copyOf(milestones);
        this.gdtLoads = gdtLoads;
        this.idtLoads = idtLoads;
        this.id = id;
        this.command = command;
        this.kernel = kernel;
        this.regNames = regNames;
        List<String> names = Arrays.asList(regNames);
        this.iCs = names.indexOf("cs");
        this.iCr0 = names.indexOf("cr0");
        this.iCr3 = names.indexOf("cr3");
        this.steps = steps;
        this.insns = insns;
        this.chunks = chunks;
        this.ckpts = ckpts;
        this.tree = tree;
        this.programs = programs;
        this.memSize = memSize;
        this.summary = summary;
        this.userBefore = new long[userBlocks.length + 1];
        for (int i = 0; i < userBlocks.length; i++)
            userBefore[i + 1] = userBefore[i] + userBlocks[i];
        this.image = finalImage;
        this.imageStep = steps;
        keepImage(this);
    }

    public String id() { return id; }
    public String command() { return command; }
    public KernelProfile kernel() { return kernel; }
    public int steps() { return steps; }
    public Tree tree() { return tree; }
    public Summary summary() { return summary; }
    public String[] regNames() { return regNames; }

    private void check(int step) {
        if (step < 0 || step >= steps)
            throw new IllegalArgumentException("step " + step + " not in [0, " + steps + ")");
    }

    public View view(int step) {
        check(step);
        ChunkStore.Decoded d = chunks.chunkOf(step);
        long[] regs = d.registers(step);
        Insn in = insns.get(d.insn[step - d.base]);
        int cpl = (int) (regs[iCs] & 3);
        Program p = cpl == 3 ? programs.at(regs[iCr3], step) : null;
        return new View(step, regs, in, cpl, p, kernel.locate(in.vaddr(), cpl == 3, p));
    }

    public Insn insn(int step) {
        check(step);
        ChunkStore.Decoded d = chunks.chunkOf(step);
        return insns.get(d.insn[step - d.base]);
    }

    /** The names of the registers the instruction before step wrote. */
    public List<String> written(int step) {
        check(step);
        List<String> out = new ArrayList<>();
        if (step == 0)
            return out;
        int m = chunks.chunkOf(step).written(step);
        for (int i = 0; i < regNames.length; i++)
            if ((m & (1 << i)) != 0)
                out.add(regNames[i]);
        return out;
    }

    /** Registers at step (the last step's for step == steps), without locating the instruction. */
    public long[] registers(int step) {
        int s = Math.min(step, steps - 1);
        check(s);
        return chunks.chunkOf(s).registers(s);
    }

    /** Index of a register in registers() and View.regs(), or -1. */
    public int regIndex(String name) {
        return Arrays.asList(regNames).indexOf(name);
    }

    /** Page table root (CR3) at step; the last step's for step == steps. */
    public long cr3(int step) {
        return registers(step)[iCr3];
    }

    /**
     * Whether paging is on (CR0.PG) at step; the last step's for step ==
     * steps. While it is off (early boot) addresses are physical.
     */
    public boolean paging(int step) {
        return pagingOn(registers(step));
    }

    private boolean pagingOn(long[] regs) {
        return iCr0 < 0 || (regs[iCr0] & 0x80000000L) != 0;
    }

    /** Runs f on physical memory at step, read through the same interface as virtual(). */
    public <T> T physical(int step, Function<MemoryImage.Virtual, T> f) {
        return memory(step, img -> f.apply(img.virtual(0, false)));
    }

    /** Runs f on virtual memory as the CPU sees it at step (CR3, or physical while paging is off). */
    public <T> T virtual(int step, Function<MemoryImage.Virtual, T> f) {
        long[] regs = registers(step);
        long cr3 = regs[iCr3];
        boolean on = pagingOn(regs);
        return memory(step, img -> f.apply(img.virtual(cr3, on)));
    }

    /** Source line keys of every step of chunk c (equal keys = same line; -1 unknown). */
    public synchronized int[] lineKeys(int c) {
        int[] keys = lineChunks.get(c);
        if (keys == null) {
            ChunkStore.Decoded d = chunks.chunk(c);
            int[] out = new int[d.n];
            d.scan((s, id, regs) -> out[s - d.base] = keyOf(s, id, regs));
            lineChunks.put(c, out);
            keys = out;
        }
        return keys;
    }

    private int keyOf(int step, int id, long[] regs) {
        Integer k = insnKey.get(id);
        if (k != null)
            return k;
        boolean user = (regs[iCs] & 3) == 3;
        Program p = user ? programs.at(regs[iCr3], step) : null;
        if (user && p == null)
            return -1; /* program not known yet: do not cache */
        LineTable.Src src = kernel.source(insns.get(id).vaddr(), user, p);
        int key = src == null ? -1 : srcKey.computeIfAbsent(src, x -> {
            keySrc.add(x);
            return keySrc.size() - 1;
        });
        insnKey.put(id, key);
        return key;
    }

    /** The user program running in address space cr3 at step, or null if not known. */
    public Program programAt(long cr3, int step) {
        return programs.at(cr3, step);
    }

    /**
     * The program whose code the address space with page-table root cr3
     * holds at step: the one last seen running there if its code is still
     * there, else the one whose code is (a page-table frame freed and
     * reused, or a forked child that has not run yet); null if none.
     */
    public Program programIn(long cr3, int step) {
        Program seen = programAt(cr3, step);
        List<Program> all = new ArrayList<>();
        if (seen != null)
            all.add(seen);
        all.addAll(kernel.programs());
        return memory(step, img -> {
            MemoryImage.Virtual vm = img.virtual(cr3, true);
            for (Program p : all) {
                for (var seg : p.elf().segments()) {
                    if (!seg.executable() || seg.filesz() < 32)
                        continue;
                    byte[] b = vm.bytes(seg.vaddr(), 32);
                    if (b.length == 32 && p.elf().matches(seg.vaddr(), b))
                        return p;
                    break;
                }
            }
            return null;
        });
    }

    private AddressSpace space; /* the last one asked for */

    /**
     * The page-table root (physical) a request names: proc[slot]'s table
     * (slot >= 0), a table by its root (root >= 0: exec's new one, held by
     * a local variable), or -1: CR3's.
     */
    public long root(int step, int slot, long root) {
        check(step);
        if (root >= 0)
            return root & MemoryImage.ADDR_MASK;
        if (slot < 0)
            return -1;
        long pt = procs().pageTable(step, slot);
        if (pt < 0)
            throw new IllegalArgumentException("proc[" + slot + "] has no page table at step " + step);
        return pt;
    }

    /** root < 0: CR3's address space; else the page table at root (see root()). */
    private synchronized AddressSpace space(int step, long root) {
        check(step);
        if (space == null || space.step != step || space.root != root)
            space = new AddressSpace(this, step, root);
        return space;
    }

    /** A map that keeps the n most recently used entries. */
    static <K, V> Map<K, V> lru(int n) {
        return new LinkedHashMap<>(n, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> e) {
                return size() > n;
            }
        };
    }

    private final Map<Integer, PageTables> pageTables = lru(4);

    /**
     * Whose page table root is at step: "kernel_pagetable", "proc[2] pid
     * 3 sh", or null. Only the roots are compared (kernel_pagetable and
     * every p->pagetable); no table is walked.
     */
    public synchronized String tableOwner(int step, long root) {
        long r0 = root & MemoryImage.ADDR_MASK;
        long kva = kernel.kernelElf().addressOf("kernel_pagetable");
        long kpa = kva == -1 ? -1 : kernel.kernelElf().physOf(kva);
        Long k = kpa < 0 ? null : memory(step, img -> img.contains(kpa, 8) ? img.read(kpa, 8) : null);
        if (k != null && k != 0 && toPhys(k) == r0)
            return "kernel_pagetable";
        try {
            for (Processes.Proc p : processes(step).procs()) {
                long v = Processes.unhex(p.pagetable());
                if (v != 0 && toPhys(v) == r0)
                    return "proc[" + p.slot() + "] pid " + p.pid() + (p.name().isEmpty() ? "" : " " + p.name());
            }
        } catch (RuntimeException e) {
            /* no process table yet */
        }
        return null;
    }

    /** A kernel pointer's physical address (the direct map; identity-mapped kernels: itself). */
    long toPhys(long kva) {
        long pa = kernel.directMapToPhys(kva);
        return pa >= 0 ? pa : kva;
    }

    /** Every page-table page at step and whose it is (see PageTables). */
    public synchronized PageTables pageTables(int step) {
        check(step);
        PageTables pt = pageTables.get(step);
        if (pt != null)
            return pt;
        List<Processes.Proc> procs;
        try {
            procs = paging(step) ? processes(step).procs() : List.of();
        } catch (RuntimeException e) {
            procs = List.of(); /* no process table yet (early boot) */
        }
        long cr3 = paging(step) ? cr3(step) : 0;
        List<Processes.Proc> ps = procs;
        pt = memory(step, img -> new PageTables(kernel, img, ps, cr3));
        pageTables.put(step, pt);
        return pt;
    }

    /** The address space of CR3 (root < 0) or of the page table at root at step, as regions (see AddressSpace). */
    public AddressSpace.Layout addressSpace(int step, long root) {
        AddressSpace.Layout l = space(step, root).map();
        if (!l.paging())
            return l;
        /* the process this page table belongs to: its p->sz */
        long table = l.cr3() & MemoryImage.ADDR_MASK;
        try {
            for (Processes.Proc p : processes(step).procs()) {
                long v = Processes.unhex(p.pagetable());
                if (v != 0 && toPhys(v) == table)
                    return l.withSz(p.sz());
            }
        } catch (RuntimeException e) {
            /* no process table (early boot): no p->sz */
        }
        return l;
    }

    /** How every physical frame is used at step, seen from that address space (see AddressSpace). */
    public AddressSpace.PhysMap physMap(int step, long root) {
        return space(step, root).physMap();
    }

    /** One physical frame at step: its kind and where that address space maps it. */
    public AddressSpace.Frame frame(int step, long pa, long root) {
        return space(step, root).frame(pa);
    }

    /**
     * User-mode steps in [from, to): exact over whole blocks, prorated
     * over the partial blocks at the ends.
     */
    public double userSteps(int from, int to) {
        return userUpTo(to) - userUpTo(from);
    }

    private double userUpTo(int s) {
        int b = s / Timeline.BLOCK;
        if (b >= userBefore.length - 1)
            return userBefore[userBefore.length - 1];
        double part = (double) (s - b * Timeline.BLOCK) / Timeline.BLOCK;
        return userBefore[b] + part * (userBefore[b + 1] - userBefore[b]);
    }

    /** The timeline of [from, to) in buckets (see Timeline). */
    public Timeline.View timeline(int from, int to, int buckets) {
        return Timeline.of(this, from, to, buckets);
    }

    /** Stores to one trap frame: {store step, pa >>> 3} in step order, scanned up to `to`. */
    private record FrameStores(java.util.Set<Long> pas, List<int[]> stores, int[] to) {
    }

    private final Map<Integer, FrameStores> frameStores = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, FrameStores> e) {
            return size() > 8;
        }
    };

    /**
     * The stores to the 8-byte words at pas (a trap frame) from the trap's
     * start up to `to`, as {step, pa >>> 3}: scanned once per trap and
     * extended as the cursor moves on (StackView's writers).
     */
    synchronized List<int[]> trapFrameStores(int trapStep, java.util.Set<Long> pas, int to) {
        FrameStores f = frameStores.get(trapStep);
        if (f == null || !f.pas().equals(pas)) {
            f = new FrameStores(java.util.Set.copyOf(pas), new ArrayList<>(), new int[] {Math.max(0, trapStep - 1)});
            frameStores.put(trapStep, f);
        }
        if (to > f.to()[0]) {
            FrameStores g = f;
            scanStores(f.to()[0], to, (st, pa, size, value) -> {
                if (g.pas().contains(pa & ~7L))
                    g.stores().add(new int[] {st, (int) (pa >>> 3)});
            });
            f.to()[0] = to;
        }
        return f.stores();
    }

    /** The first store in [from, to) that overlaps [pa, pa + len), or -1 (the scan stops there). */
    public int firstStore(int from, int to, long pa, int len) {
        int[] at = {-1};
        chunks.forEachWrite(Math.max(0, from), Math.min(to, steps), false, (s, spa, size, nv, ov) -> {
            if (spa < pa + len && spa + size > pa) {
                at[0] = s;
                return false;
            }
            return true;
        });
        return at[0];
    }

    /** Every store made by steps in [from, to), oldest first. */
    public void scanStores(int from, int to, OldStoreVisitor v) {
        chunks.forEachWrite(Math.max(0, from), Math.min(to, steps), false, (s, pa, size, nv, ov) -> {
            v.store(s, pa, size, nv, ov);
            return true;
        });
    }

    public void scanStores(int from, int to, StoreVisitor v) {
        chunks.forEachWrite(Math.max(0, from), Math.min(to, steps), false, (s, pa, size, nv, ov) -> {
            v.store(s, pa, size, nv);
            return true;
        });
    }

    private Syscalls syscalls;

    /** The recording's system calls and exceptions (computed once). */
    public synchronized Syscalls syscalls() {
        if (syscalls == null)
            syscalls = Syscalls.of(this);
        return syscalls;
    }

    /** The one-line form of a trap node (write(1, "hi", 2) = 2), or null. */
    public String trapDetail(int node) {
        if (tree.kind(node) != Tree.INTR)
            return null;
        Syscalls.Call c = syscalls().of(node);
        return c == null ? null : c.detail();
    }

    private Processes processes;

    synchronized Processes procs() {
        if (processes == null)
            processes = new Processes(this);
        return processes;
    }

    private final Map<Integer, Processes.View> procViews = lru(4);

    /** The process table at step (see Processes); the last few are kept (Memory, Names, Processes ask for the same step). */
    public Processes.View processes(int step) {
        if (step < 0 || step > steps)
            throw new IllegalArgumentException("step " + step);
        synchronized (procViews) {
            Processes.View v = procViews.get(step);
            if (v != null)
                return v;
        }
        Processes.View v = procs().view(step);
        synchronized (procViews) {
            procViews.put(step, v);
        }
        return v;
    }

    private final Map<Long, StateDiff.Diff> diffs = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, StateDiff.Diff> e) {
            return size() > 16;
        }
    };

    /** What changed from step from to step to (see StateDiff). */
    public StateDiff.Diff diff(int from, int to) {
        if (from < 0 || to > steps || from > to)
            throw new IllegalArgumentException("need 0 <= from <= to <= " + steps);
        long key = ((long) from << 32) | to;
        synchronized (diffs) {
            StateDiff.Diff d = diffs.get(key);
            if (d != null)
                return d;
        }
        StateDiff.Diff d = new StateDiff(this, procs()).diff(from, to);
        synchronized (diffs) {
            diffs.put(key, d);
        }
        return d;
    }

    /** Steps that run lgdt (CpuTables.LGDT) or lidt (LIDT), in order. Do not modify. */
    int[] tableLoads(byte kind) {
        return kind == CpuTables.LGDT ? gdtLoads : idtLoads;
    }

    /** The first user-mode step, or -1 if none. */
    int firstUserStep() {
        return firstUser;
    }

    private CpuTables cpuTables;

    synchronized CpuTables cpuTables() {
        if (cpuTables == null)
            cpuTables = new CpuTables(this);
        return cpuTables;
    }

    /**
     * Why threads took turns (a folded run of runs): what each process in
     * it waited on when it was switched out the first time, from proc[]
     * then ("proc[0] pid 1 init sleeps on bcache.buf[24]"); null if none slept.
     */
    public String turnsDetail(List<Integer> runs) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int run : runs) {
            int slot = tree.slotOf(run);
            if (slot < 0 || !seen.add(slot) || out.size() >= 3)
                continue;
            int at = Math.min(tree.end(run), steps - 1);
            /* only a sleeper says why: its state from the stores, then its chan from memory */
            Map<Long, String> names = kernel.structs().enumWith("SLEEPING");
            if (names == null || !"SLEEPING".equals(names.get((long) procStates.at(slot, at))))
                continue;
            try {
                for (Processes.Proc p : processes(at).procs())
                    if (p.slot() == slot && p.state().equals("SLEEPING"))
                        out.add(tree.processOf(run) + (p.name().isEmpty() ? "" : " " + p.name()) + " sleeps on "
                                + (p.chanName() != null ? p.chanName() : p.chan()));
            } catch (RuntimeException e) {
                /* no process table to read */
            }
        }
        return out.isEmpty() ? null : String.join("; ", out);
    }

    /** The GDT, TSS and IDT at step (see CpuTables). */
    public CpuTables.View cpu(int step) {
        check(step);
        return cpuTables().view(step);
    }

    private static final Map<String, Recording> boots = new java.util.concurrent.ConcurrentHashMap<>();

    /** Make r the boot recording of its kernel (CpuTables takes once-loaded tables from it). */
    public static void registerBoot(Recording r) {
        boots.put(r.kernel().id(), r);
    }

    static Recording bootOf(String kernelId) {
        return boots.get(kernelId);
    }

    /**
     * Who runs at step, named the same everywhere: "proc[4] pid 7 grep"
     * (the thread's process, the program its address space holds then),
     * or the run's label (scheduler, boot).
     */
    public String threadLabel(int step) {
        int root = tree.rootAt(step);
        String run = root < 0 ? null : tree.label(root);
        String slot = root < 0 ? null : tree.processOf(root);
        if (slot == null)
            return run;
        Program p = programAt(cr3(step), step);
        if (p != null)
            return slot + " " + p.name();
        int arrow = run.lastIndexOf('\u2192');
        return arrow < 0 ? run : slot + " " + run.substring(arrow + 1);
    }

    /**
     * The threads other than the one at from that ran between from and to
     * (forward moves only), by label, in order of first run; at most 6.
     */
    public List<String> othersBetween(int from, int to) {
        if (to <= from + 1)
            return List.of();
        int a = tree.rootAt(from);
        if (a < 0 || tree.end(a) >= to)
            return List.of();
        int me = tree.thread(a);
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        int[] runs = tree.runs();
        /* runs are in start order: from the one at `from` until `to` */
        for (int i = Arrays.binarySearch(runs, a) + 1; i < runs.length && tree.start(runs[i]) < to; i++) {
            if (tree.thread(runs[i]) != me)
                out.add(tree.label(runs[i]));
            if (out.size() >= 6)
                break;
        }
        return List.copyOf(out);
    }

    /**
     * The CPU's mode at step: real (CR0.PE off), protected 32-bit, or with
     * long mode on (EFER.LMA) 64-bit, unless the code is the kernel's
     * .code32 entry code (then compatibility mode: cs still holds a 32-bit
     * segment until the far jump).
     */
    public String cpuMode(View v) {
        int cr0 = regIndex("cr0");
        if (cr0 >= 0 && (v.regs()[cr0] & 1) == 0)
            return "real mode";
        boolean c32 = v.cpl() == 0 && kernel.isCode32(v.insn().vaddr());
        if (!CpuTables.long64(v.regs(), this))
            return "protected mode, 32-bit";
        return c32 ? "compatibility mode (long mode on, 32-bit code)" : "64-bit mode";
    }

    /** The running function's parameters and locals at step, with their values (see Variables). */
    public Variables.View variables(int step) {
        check(step);
        return Variables.at(this, step);
    }

    /** What addresses in the registers and the instruction at step are (see Names). */
    public Names.View names(int step, boolean line) {
        check(step);
        return Names.at(this, step, line);
    }

    private StackView.Stack lastStack, lastLabels; /* the last ones asked for */

    /** The stack in use at step, slot by slot (see StackView), with who wrote each trap frame slot. */
    public synchronized StackView.Stack stack(int step) {
        check(step);
        if (lastStack == null || lastStack.step() != step)
            lastStack = new StackView(this).at(step, true);
        return lastStack;
    }

    /** The same without the writers: for naming slots. */
    synchronized StackView.Stack stackLabels(int step) {
        check(step);
        if (lastStack != null && lastStack.step() == step)
            return lastStack;
        if (lastLabels == null || lastLabels.step() != step)
            lastLabels = new StackView(this).at(step, false);
        return lastLabels;
    }

    /** The source line of a line key (see lineKeys), or null. */
    public synchronized LineTable.Src lineOfKey(int key) {
        return key >= 0 && key < keySrc.size() ? keySrc.get(key) : null;
    }

    /** The innermost loop around the instruction of step, as runtime addresses {lo, hi}, or null. */
    public long[] loopAround(int step) {
        View v = view(step);
        return kernel.loopAround(v.insn().vaddr(), v.cpl() == 3, v.program());
    }

    /** Static disassembly around the instruction of step. */
    public List<KernelProfile.Line> listing(int step, int before, int after) {
        View v = view(step);
        return kernel.listing(v.insn().vaddr(), v.cpl() == 3, v.program(), before, after);
    }

    /** Instructions of a source line in the function running at step (see KernelProfile). */
    public List<KernelProfile.Line> lineInstructions(int step, int line) {
        View v = view(step);
        return kernel.lineInstructions(v.insn().vaddr(), v.cpl() == 3, v.program(), line);
    }

    /** Runs f on the memory image as it is at step. */
    public synchronized <T> T memory(int step, Function<MemoryImage, T> f) {
        if (step < 0 || step > steps)
            throw new IllegalArgumentException("step " + step);
        seek(step);
        keepImage(this);
        return f.apply(image);
    }

    /** Move the image to target: from the nearest checkpoint or from where it is. */
    private void seek(int target) {
        int ck = ckpts.floor(target);
        try {
            if (image == null) {
                image = new MemoryImage(memSize);
                ckpts.load(ck, image);
                imageStep = ck;
            } else if (target - ck < Math.abs(target - imageStep)) {
                ckpts.load(ck, image);
                imageStep = ck;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        MemoryImage img = image;
        if (target > imageStep)
            chunks.forEachWrite(imageStep, target, false, (s, pa, size, nv, ov) -> {
                img.write(pa, size, nv);
                return true;
            });
        else if (target < imageStep)
            chunks.forEachWrite(target, imageStep, true, (s, pa, size, nv, ov) -> {
                img.write(pa, size, ov);
                return true;
            });
        imageStep = target;
    }

    private static void keepImage(Recording r) {
        synchronized (images) {
            images.put(r, Boolean.TRUE);
            List<Recording> drop = new ArrayList<>();
            int extra = images.size() - MAX_IMAGES;
            for (Recording old : images.keySet()) {
                if (extra-- <= 0)
                    break;
                drop.add(old);
            }
            for (Recording old : drop) {
                images.remove(old);
                old.releaseImage();
            }
        }
    }

    private synchronized void releaseImage() {
        image = null;
    }

    /** At most limit stores made by steps in [from, to): the oldest, or (newest) the newest first. */
    public List<Store> stores(int from, int to, int limit, boolean newest) {
        List<Store> out = new ArrayList<>();
        chunks.forEachWrite(Math.max(0, from), Math.min(to, steps), newest, (s, pa, size, nv, ov) -> {
            out.add(new Store(s, pa, size, nv, ov));
            return out.size() < limit;
        });
        return out;
    }
}
