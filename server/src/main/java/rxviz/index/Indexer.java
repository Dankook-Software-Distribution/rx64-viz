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
    private int iRax = -1, iRsp = -1, iRbp = -1, iRdi = -1, iRsi = -1, iCs = -1, iCr2 = -1, iCr3 = -1;
    private int step;
    private long pendingDisc = -1;
    private long userSteps, stores;
    /* the first user-mode step (and the first one in C code); whether step
     * 0 is the kernel's entry (a boot) */
    private int firstUser = -1, firstUserC = -1;
    /* the program of firstUserC (the shell), and the first C step of another
     * one (the command it runs): where a command's recording opens */
    private Program firstUserProg;
    private int firstOtherC = -1;
    private boolean bootRecording;
    /* user-mode steps per block of Timeline.BLOCK steps */
    private short[] userBlocks = new short[1024];
    private String name = "";
    /* steps that run lgdt / lidt */
    private int[] gdtLoads = new int[16], idtLoads = new int[1024];
    private int nGdt, nIdt;
    /* Timeline milestones: control registers, table loads, first user step */
    private final List<Timeline.Milestone> milestones = new java.util.ArrayList<>();
    private int iCr0 = -1, iCr4 = -1, iEfer = -1;
    private long cr0, cr4, efer;
    private static final int MAX_MILESTONES = 64, MAX_CR3 = 2, MAX_TABLE_LOADS = 2;
    private static final String[] CR0_BITS = new String[32], CR4_BITS = new String[32], EFER_BITS = new String[32];
    static {
        String[][] c0 = {{"0", "PE"}, {"1", "MP"}, {"2", "EM"}, {"3", "TS"}, {"4", "ET"}, {"5", "NE"},
            {"16", "WP"}, {"18", "AM"}, {"29", "NW"}, {"30", "CD"}, {"31", "PG"}};
        String[][] c4 = {{"0", "VME"}, {"1", "PVI"}, {"2", "TSD"}, {"3", "DE"}, {"4", "PSE"}, {"5", "PAE"},
            {"6", "MCE"}, {"7", "PGE"}, {"8", "PCE"}, {"9", "OSFXSR"}, {"10", "OSXMMEXCPT"}, {"11", "UMIP"},
            {"12", "LA57"}, {"16", "FSGSBASE"}, {"17", "PCIDE"}, {"18", "OSXSAVE"}, {"20", "SMEP"}, {"21", "SMAP"}};
        String[][] ef = {{"0", "SCE"}, {"8", "LME"}, {"10", "LMA"}, {"11", "NXE"}};
        for (String[] b : c0) CR0_BITS[Integer.parseInt(b[0])] = b[1];
        for (String[] b : c4) CR4_BITS[Integer.parseInt(b[0])] = b[1];
        for (String[] b : ef) EFER_BITS[Integer.parseInt(b[0])] = b[1];
    }

    /** "cr0: +PG +WP": the named bits of cr0, cr4 or efer that changed from old to now. */
    static String controlBits(String reg, long old, long now) {
        String[] names = switch (reg) {
            case "cr0" -> CR0_BITS;
            case "cr4" -> CR4_BITS;
            default -> EFER_BITS;
        };
        return bits(reg, names, old, now);
    }

    /** "cr0: +PG +WP" for the bits that changed from old to now. */
    private static String bits(String reg, String[] names, long old, long now) {
        StringBuilder b = new StringBuilder(reg).append(':');
        long diff = old ^ now;
        for (int i = 0; i < 32; i++)
            if ((diff >>> i & 1) != 0)
                b.append(' ').append((now >>> i & 1) != 0 ? '+' : '-')
                        .append(names[i] != null ? names[i] : "bit" + i);
        return b.toString();
    }

    /* proc[k].state: where the field is (physical) and every change, for the Timeline */
    private final long procBase, procSize, stateOff;
    private final int nproc;
    private final int[] lastState;
    private final ProcStates.Builder states;

    /* steps of the instructions that loaded cr3 */
    private static final int MAX_CR3_SWITCHES = 1 << 20;
    private int[] cr3Switches = new int[64];
    private int nCr3;

    private void milestone(String label) {
        milestone(step, label);
    }

    /** Only a boot recording has them: later, cr3 and the IDT change on every switch or trap. */
    private void milestone(int at, String label) {
        if (bootRecording && milestones.size() < MAX_MILESTONES)
            milestones.add(new Timeline.Milestone(at, label));
    }

    private Indexer(KernelProfile kernel, MemoryImage image, ChunkStore chunks,
                    Checkpoints ckpts) {
        this.kernel = kernel;
        this.image = image;
        this.chunks = chunks;
        this.ckpts = ckpts;
        this.tree = new TreeBuilder(kernel, image);
        this.programs = new Programs(kernel);
        long va = kernel.kernelElf().addressOf("proc");
        rxviz.elf.Structs.Struct sp = kernel.structs().elementOf("proc");
        rxviz.elf.Structs.Member st = sp == null ? null : sp.member("state");
        boolean ok = va != -1 && st != null && sp.size() > 0;
        procBase = ok ? kernel.kernelElf().physOf(va) : -1;
        procSize = ok ? sp.size() : 1;
        stateOff = ok ? st.offset() : 0;
        nproc = ok && procBase >= 0 ? (int) (kernel.kernelElf().sizeOf("proc") / sp.size()) : 0;
        lastState = new int[nproc];
        for (int k = 0; k < nproc; k++) {
            long pa = procBase + k * procSize + stateOff;
            lastState[k] = image.contains(pa, 4) ? (int) image.read(pa, 4) : 0;
        }
        states = new ProcStates.Builder(lastState);
    }

    /** After a store to [pa, pa + len): a proc[k].state it changed. */
    private void watchState(long pa, int len) {
        if (nproc == 0 || pa + len <= procBase || pa >= procBase + nproc * procSize)
            return;
        int k0 = (int) Math.max(0, (pa - procBase) / procSize), k1 = (int) Math.min(nproc - 1, (pa + len - 1 - procBase) / procSize);
        for (int k = k0; k <= k1; k++) {
            long f = procBase + k * procSize + stateOff;
            if (f + 4 <= pa || f >= pa + len)
                continue;
            int v = (int) image.read(f, 4);
            if (v != lastState[k]) {
                lastState[k] = v;
                states.add(step - 1, k, v); /* stores belong to the step that ran */
            }
        }
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
        short[] blocks = Arrays.copyOf(ix.userBlocks, (ix.step + Timeline.BLOCK - 1) / Timeline.BLOCK);
        Recording.Summary sum = new Recording.Summary(ix.step, ix.userSteps,
                ix.step - ix.userSteps, ix.stores, ix.tree.syscalls, ix.tree.interrupts,
                ix.tree.exceptions, ix.tree.switches, t.size(),
                chunks.bytesOnDisk() + ckpts.bytesOnDisk(), System.currentTimeMillis() - t0,
                ix.bootRecording || ix.firstUser < 0 ? 0
                        : ix.firstOtherC >= 0 ? ix.firstOtherC
                        : ix.firstUserC >= 0 ? ix.firstUserC : ix.firstUser);
        return new Recording(id, command.isEmpty() ? ix.name : command, kernel,
                ix.regNames, ix.step, ix.insns, chunks, ckpts, t, ix.programs,
                memSize, image, blocks, Arrays.copyOf(ix.gdtLoads, ix.nGdt),
                Arrays.copyOf(ix.idtLoads, ix.nIdt), ix.firstUser, sum, ix.milestones,
                Arrays.copyOf(ix.cr3Switches, ix.nCr3), ix.states.build());
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
        iRbp = l.indexOf("rbp");
        iRdi = l.indexOf("rdi");
        iRsi = l.indexOf("rsi");
        iCs = l.indexOf("cs");
        iCr2 = l.indexOf("cr2");
        iCr3 = l.indexOf("cr3");
        iCr0 = l.indexOf("cr0");
        iCr4 = l.indexOf("cr4");
        iEfer = l.indexOf("efer");
        if (iRax < 0 || iRsp < 0 || iRbp < 0 || iRdi < 0 || iRsi < 0 || iCs < 0 || iCr2 < 0 || iCr3 < 0)
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
        /* the registers are those before this step runs: a change shows at
         * the step after the instruction that made it; the milestone is
         * that instruction (step - 1), like lgdt's */
        if (step == 0) {
            cr0 = iCr0 < 0 ? 0 : regs[iCr0];
            cr4 = iCr4 < 0 ? 0 : regs[iCr4];
            efer = iEfer < 0 ? 0 : regs[iEfer];
        } else {
            if (iCr0 >= 0 && (mask & (1 << iCr0)) != 0 && regs[iCr0] != cr0) {
                milestone(step - 1, bits("cr0", CR0_BITS, cr0, regs[iCr0]));
                cr0 = regs[iCr0];
            }
            if (iCr4 >= 0 && (mask & (1 << iCr4)) != 0 && regs[iCr4] != cr4) {
                milestone(step - 1, bits("cr4", CR4_BITS, cr4, regs[iCr4]));
                cr4 = regs[iCr4];
            }
            if (iEfer >= 0 && (mask & (1 << iEfer)) != 0 && regs[iEfer] != efer) {
                milestone(step - 1, bits("efer", EFER_BITS, efer, regs[iEfer]));
                efer = regs[iEfer];
            }
            if ((mask & (1 << iCr3)) != 0) {
                if (nCr3 < MAX_CR3) /* the first loads; later ones are every switch */
                    milestone(step - 1, "cr3 = 0x" + Long.toHexString(regs[iCr3]));
                /* every switch of page table, for the Timeline */
                if (nCr3 < MAX_CR3_SWITCHES) {
                    if (nCr3 == cr3Switches.length)
                        cr3Switches = Arrays.copyOf(cr3Switches, nCr3 * 2);
                    cr3Switches[nCr3++] = step - 1;
                }
            }
        }
        long pc = in.vaddr();
        int cpl = (int) (regs[iCs] & 3);
        if (in.table == CpuTables.LGDT && nGdt < MAX_TABLE_LOADS)
            milestone("lgdt");
        else if (in.table == CpuTables.LIDT && nIdt < MAX_TABLE_LOADS)
            milestone("lidt");
        if (in.table == CpuTables.LGDT) {
            if (nGdt == gdtLoads.length)
                gdtLoads = Arrays.copyOf(gdtLoads, nGdt * 2);
            gdtLoads[nGdt++] = step;
        } else if (in.table == CpuTables.LIDT) {
            if (nIdt == idtLoads.length)
                idtLoads = Arrays.copyOf(idtLoads, nIdt * 2);
            idtLoads[nIdt++] = step;
        }
        String fn = "";
        String userFn = null;
        String program = null;
        if (step == 0 && pc == kernel.bootEntry())
            bootRecording = true;
        if (cpl == 3) {
            if (firstUser < 0) {
                firstUser = step;
                milestone("first user-mode instruction");
            }
            userSteps++;
            int b = step / Timeline.BLOCK;
            if (b >= userBlocks.length)
                userBlocks = Arrays.copyOf(userBlocks, Math.max(userBlocks.length * 2, b + 1));
            userBlocks[b]++;
            Program p = programs.observe(step, regs[iCr3], pc, in.bytes());
            if (p != null && (firstUserC < 0 || (firstOtherC < 0 && p != firstUserProg))) {
                var src = kernel.source(pc, true, p);
                if (src != null && src.file().endsWith(".c")) {
                    if (firstUserC < 0) {
                        firstUserC = step;
                        firstUserProg = p;
                    } else if ("main".equals(function(in, pc, p))) {
                        firstOtherC = step; /* its main(), not crt0's start() */
                    }
                }
            }
            if (p != null) {
                program = p.name();
                userFn = function(in, pc, p);
            }
        } else {
            String f = function(in, pc, null);
            fn = f == null ? "" : f;
        }
        tree.step(step, pc, in.kind(), cpl, regs[iRsp], regs[iRbp], regs[iCr3], regs[iRax],
                regs[iRdi], regs[iRsi], regs[iCr2], fn, userFn, program, pendingDisc);
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
    public void store(int vcpu, boolean io, boolean cpu, int size, long vaddr, long paddr,
                      long lo, long hi) {
        if (step == 0 || io || paddr < 0)
            return;
        for (int off = 0; off < size; off += 8) {
            int part = Math.min(8, size - off);
            long pa = paddr + off;
            if (!image.contains(pa, part))
                continue;
            long val = off == 0 ? lo : hi;
            long old = image.read(pa, part);
            /* the plugin reads back one word more than a frame without
             * an error code; a CPU word that did not change was not
             * (or need not be) written */
            if (cpu && val == old)
                continue;
            image.write(pa, part, val);
            chunks.write(pa, part, val, old);
            stores++;
            watchState(pa, part);
        }
    }

    @Override
    public void discon(int vcpu, int type, long from, long to) {
        /* int $n is reported twice (exception, then interrupt into the
         * gate); the last target is where the next step starts */
        pendingDisc = to;
    }
}
