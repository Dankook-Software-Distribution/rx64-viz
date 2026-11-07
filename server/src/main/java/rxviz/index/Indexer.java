package rxviz.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;

import rxviz.config.KernelProfile;
import rxviz.config.KernelProfile.Program;
import rxviz.trace.SegmentReader;

/**
 * Reads one segment file once and produces a {@link Recording}: steps
 * re-cut into seekable chunks (with the old value of every store, so
 * memory can be rewound), the event tree, and which program runs where.
 */
public final class Indexer implements SegmentReader.Visitor {

    private final KernelProfile kernel;
    private final MemoryImage image;
    private final ChunkStore chunks;
    private final Checkpoints ckpts;
    private final TreeBuilder tree;
    private final Programs programs;
    private final Insns insns = new Insns();

    private String[] regNames = new String[0];
    private long[] regs = new long[0];
    private int iRax = -1, iRsp = -1, iCs = -1, iCr2 = -1, iCr3 = -1;
    private int step;
    private long pendingDisc = -1;
    private long userSteps, stores;
    private String name = "";

    private Indexer(KernelProfile kernel, MemoryImage image, ChunkStore chunks,
                    Checkpoints ckpts) {
        this.kernel = kernel;
        this.image = image;
        this.chunks = chunks;
        this.ckpts = ckpts;
        this.tree = new TreeBuilder(kernel);
        this.programs = new Programs(kernel);
    }

    public static Recording index(String id, String command, KernelProfile kernel,
                                  SegmentFiles files, int memSize) throws IOException {
        long t0 = System.currentTimeMillis();
        Checkpoints ckpts = new Checkpoints(files.checkpoints(), files.snapshot());
        MemoryImage image = new MemoryImage(memSize);
        ckpts.load(0, image);
        ChunkStore chunks = new ChunkStore(files.chunks());
        Indexer ix = new Indexer(kernel, image, chunks, ckpts);
        SegmentReader.read(files.trace(), ix);
        chunks.finish();
        Tree t = ix.tree.finish(ix.step);
        Recording.Summary sum = new Recording.Summary(ix.step, ix.userSteps,
                ix.step - ix.userSteps, ix.stores, ix.tree.syscalls, ix.tree.interrupts,
                ix.tree.exceptions, ix.tree.switches, t.size(),
                chunks.bytesOnDisk() + ckpts.bytesOnDisk(), System.currentTimeMillis() - t0);
        return new Recording(id, command.isEmpty() ? ix.name : command, kernel,
                ix.regNames, ix.step, ix.insns, chunks, ckpts, t, ix.programs,
                memSize, image, sum);
    }

    @Override
    public void mark(String name, long insnCount) {
        this.name = name;
    }

    @Override
    public void registers(String[] names) {
        regNames = names;
        regs = new long[names.length];
        List<String> l = Arrays.asList(names);
        iRax = l.indexOf("rax");
        iRsp = l.indexOf("rsp");
        iCs = l.indexOf("cs");
        iCr2 = l.indexOf("cr2");
        iCr3 = l.indexOf("cr3");
        if (iRax < 0 || iRsp < 0 || iCs < 0 || iCr2 < 0 || iCr3 < 0)
            throw new IllegalStateException("trace lacks a needed register");
    }

    @Override
    public void insn(int id, long vaddr, byte[] bytes) {
        insns.put(id, new Recording.Insn(vaddr, bytes));
    }

    @Override
    public void exec(int vcpu, int id, int mask, long[] values, int nvalues) {
        if (regs.length == 0)
            throw new IllegalStateException("not a full-mode trace (no register list)");
        if (step > 0 && step % Checkpoints.INTERVAL == 0) {
            try {
                ckpts.write(step, image);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        int k = 0;
        for (int r = 0; r < regs.length; r++)
            if ((mask & (1 << r)) != 0)
                regs[r] = values[k++];

        Recording.Insn in = insns.get(id);
        if (in == null)
            throw new IllegalStateException("step " + step + ": unknown insn " + id);
        long pc = in.vaddr();
        int cpl = (int) (regs[iCs] & 3);
        String fn = "";
        String userFn = null;
        String program = null;
        if (cpl == 3) {
            userSteps++;
            Program p = programs.observe(step, regs[iCr3], pc, in.bytes());
            if (p != null) {
                program = p.name();
                userFn = function(in, pc, p);
            }
        } else {
            String f = function(in, pc, null);
            fn = f == null ? "" : f;
        }
        tree.step(step, pc, in.kind(), cpl, regs[iRsp], regs[iCr3], regs[iRax],
                regs[iCr2], fn, userFn, program, pendingDisc);
        pendingDisc = -1;
        chunks.step(id, mask, values, nvalues, regs);
        step++;
    }

    /** The function of an instruction, cached on it per image (kernel or program). */
    private String function(Recording.Insn in, long pc, Program p) {
        Object of = p == null ? kernel : p;
        if (in.fnOf != of) {
            in.fn = kernel.function(pc, p != null, p);
            in.fnOf = of;
        }
        return in.fn;
    }

    @Override
    public void store(int vcpu, boolean io, int size, long vaddr, long paddr, long lo, long hi) {
        if (step == 0 || io || paddr < 0)
            return;
        for (int off = 0; off < size; off += 8) {
            int part = Math.min(8, size - off);
            long pa = paddr + off;
            if (!image.contains(pa, part))
                continue;
            long val = off == 0 ? lo : hi;
            long old = image.read(pa, part);
            image.write(pa, part, val);
            chunks.write(pa, part, val, old);
            stores++;
        }
    }

    @Override
    public void discon(int vcpu, int type, long from, long to) {
        /* int $n is reported twice (exception, then interrupt into the
         * gate); the last target is where the next step starts */
        pendingDisc = to;
    }
}
