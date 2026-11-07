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

    public record Summary(int steps, long userSteps, long kernelSteps, long stores,
                          int syscalls, int interrupts, int exceptions, int switches,
                          int nodes, long chunkBytes, long indexMillis) {
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
    private final int iCs, iCr3;
    private final int steps;
    private final Insns insns;
    private final ChunkStore chunks;
    private final Checkpoints ckpts;
    private final Tree tree;
    private final Programs programs;
    private final int memSize;
    private final Summary summary;

    private MemoryImage image;
    private int imageStep;

    /* source lines: one small integer per distinct line, per insn id, and
     * per chunk (one key per step) */
    private final Map<LineTable.Src, Integer> srcKey = new HashMap<>();
    private final Map<Integer, Integer> insnKey = new HashMap<>();
    private final Map<Integer, int[]> lineChunks = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, int[]> e) {
            return size() > 64;
        }
    };

    Recording(String id, String command, KernelProfile kernel, String[] regNames,
              int steps, Insns insns, ChunkStore chunks, Checkpoints ckpts, Tree tree,
              Programs programs, int memSize, MemoryImage finalImage, Summary summary) {
        this.id = id;
        this.command = command;
        this.kernel = kernel;
        this.regNames = regNames;
        List<String> names = Arrays.asList(regNames);
        this.iCs = names.indexOf("cs");
        this.iCr3 = names.indexOf("cr3");
        this.steps = steps;
        this.insns = insns;
        this.chunks = chunks;
        this.ckpts = ckpts;
        this.tree = tree;
        this.programs = programs;
        this.memSize = memSize;
        this.summary = summary;
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

    /** Page table root (CR3) at step; the last step's for step == steps. */
    public long cr3(int step) {
        int s = Math.min(step, steps - 1);
        check(s);
        return chunks.chunkOf(s).registers(s)[iCr3];
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
        int key = src == null ? -1 : srcKey.computeIfAbsent(src, x -> srcKey.size());
        insnKey.put(id, key);
        return key;
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
