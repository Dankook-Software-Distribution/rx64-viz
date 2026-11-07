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
        /* function name, valid for fnOf (the kernel profile or a user Program) */
        Object fnOf;
        String fn;

        Insn(long vaddr, byte[] bytes) {
            this.vaddr = vaddr;
            this.bytes = bytes;
            this.kind = InsnKind.of(bytes);
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
              Summary summary) {
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

    private AddressSpace space; /* the last step asked for */

    private synchronized AddressSpace space(int step) {
        check(step);
        if (space == null || space.step != step)
            space = new AddressSpace(this, step);
        return space;
    }

    /** The address space of CR3 at step, as regions (see AddressSpace). */
    public AddressSpace.Layout addressSpace(int step) {
        return space(step).map();
    }

    /** How every physical frame is used at step (see AddressSpace). */
    public AddressSpace.PhysMap physMap(int step) {
        return space(step).physMap();
    }

    /** One physical frame at step: its kind and where CR3's page table maps it. */
    public AddressSpace.Frame frame(int step, long pa) {
        return space(step).frame(pa);
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

    /** Every store made by steps in [from, to), oldest first. */
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

    /** The stack in use at step, slot by slot (see StackView). */
    public StackView.Stack stack(int step) {
        check(step);
        return new StackView(this).at(step);
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
