package rxviz.index;

import static rxviz.elf.Addr.hex;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import rxviz.config.KernelProfile;
import rxviz.config.KernelProfile.Program;
import rxviz.elf.Addr;
import rxviz.elf.ElfFile;
import rxviz.elf.Structs;

/**
 * The kernel's process table at a step, read from the recorded memory:
 * proc[NPROC] and cpus[NCPU] with the field offsets of struct proc,
 * struct cpu, struct file, struct inode and struct pipe from DWARF, and
 * the state names from enum procstate.
 *
 * Besides what a slot holds, a snapshot keeps where each field lives in
 * physical memory and what each process's page table maps, so that
 * StateDiff can compare two steps and find the stores that made a change.
 */
public final class Processes {

    /**
     * An open file descriptor. file: index in ftable.file[] (-1 if not
     * there); type: none, pipe, inode or device; what: the object
     * (console, pipe 0x..., inode 12); detail: its state.
     */
    public record Fd(int fd, String addr, int file, String type, boolean readable,
                     boolean writable, int ref, String what, String detail) {
    }

    /**
     * One proc[] slot. chanName: what chan points at (proc[1], ticks,
     * pipe 0x... nread, ...); where: for a process that is not running,
     * the kernel functions it is stopped in, innermost first (from the
     * saved rbp chain on its kernel stack; empty if it never ran);
     * resume: where swtch() will continue it; cpu: the CPU running it (or
     * null); parent: its parent's slot (-1).
     */
    public record Proc(int slot, String addr, int pid, String name, String state, String chan,
                       String chanName, List<String> where, String resume, int parent,
                       int parentPid, String parentName, long sz, boolean killed, int xstate,
                       String pagetable, String kstack, String trapframe, Integer cpu,
                       List<Fd> files, String cwd) {
    }

    /** A CPU: its index and the slot of the process it runs (cpus[i].proc), or null. */
    public record Cpu(int cpu, Integer slot) {
    }

    /** The process table at step (slots not UNUSED). */
    public record View(int step, int nproc, List<Proc> procs, List<Cpu> cpus, String error) {
    }

    /* ---------------- snapshot, for StateDiff ---------------- */

    /* field names of a process's saved context and trap frame */
    private static final String CONTEXT = "context.", TRAPFRAME = "trapframe->";

    /**
     * A field of a slot, as text, and the physical ranges {pa, len} its
     * value comes from. registers: a saved register (context, trap frame),
     * which changes on every switch and trap: StateDiff shows those only
     * for a process being created (what fork set up).
     */
    record Field(String text, List<long[]> ranges, boolean registers) {
        Field(String text, List<long[]> ranges) {
            this(text, ranges, false);
        }
    }

    /** What a slot's page table maps (user half), with names from AddressSpace. */
    record Mem(long root, List<AddressSpace.Leaf> leaves, String[][] names, List<Long> tables) {
    }

    /** Everything about one step that StateDiff compares. */
    static final class Snap {
        final int step;
        final List<Proc> all = new ArrayList<>(); /* every slot, UNUSED too */
        final List<Map<String, Field>> fields = new ArrayList<>();
        final Map<Integer, Mem> mem = new HashMap<>();
        AddressSpace.FreeList free;
        /* frame -> what it holds, for frames that a process owns */
        final Map<Long, String> owner = new HashMap<>();
        /* "file[i]" (ftable.file[i], "" when ref is 0) and "pipe 0x..." -> state as text */
        final Map<String, Field> files = new LinkedHashMap<>();
        List<Cpu> cpus = List.of();
        String error;

        Snap(int step) {
            this.step = step;
        }
    }

    private static final int PAGE = 4096;
    /* kernel/stat.h */
    private static final String[] INODE_TYPES = {"none", "directory", "file", "device"};
    /* kernel/fs.h: the root directory's inode number */
    private static final int ROOTINO = 1;
    private static final int MAX_FRAMES = 12;

    private final Recording r;
    private final KernelProfile kernel;
    private final ElfFile elf;
    private final Structs st;
    private final Structs.Struct sProc, sCpu, sFile, sInode, sPipe, sContext, sDevsw;
    private final Map<Long, String> states, fileTypes;
    private final long procVa, cpusVa, ftableVa, devswVa, kptVa;
    private final int nproc, ncpu, nofile;
    private final long ftableFileOff;
    private final int nfile;

    Processes(Recording r) {
        this.r = r;
        this.kernel = r.kernel();
        this.elf = kernel.kernelElf();
        this.st = kernel.structs();
        sProc = st.get("proc");
        sCpu = st.get("cpu");
        sFile = st.get("file");
        sInode = st.get("inode");
        sPipe = st.get("pipe");
        sContext = st.get("context");
        sDevsw = st.get("devsw");
        states = st.enumWith("RUNNABLE");
        fileTypes = st.enumWith("FD_PIPE");
        procVa = elf.addressOf("proc");
        cpusVa = elf.addressOf("cpus");
        ftableVa = elf.addressOf("ftable");
        devswVa = elf.addressOf("devsw");
        kptVa = elf.addressOf("kernel_pagetable");
        nproc = sProc == null || procVa == -1 ? 0 : (int) (elf.sizeOf("proc") / sProc.size());
        ncpu = sCpu == null || cpusVa == -1 ? 0 : (int) (elf.sizeOf("cpus") / sCpu.size());
        Structs.Member of = sProc == null ? null : sProc.member("ofile");
        Structs.Member cwd = sProc == null ? null : sProc.member("cwd");
        nofile = of == null || cwd == null ? 0 : (int) ((cwd.offset() - of.offset()) / 8);
        Structs.Struct ft = st.ofVariable("ftable");
        ftableFileOff = ft == null || ft.member("file") == null ? -1 : ft.member("file").offset();
        nfile = ftableFileOff < 0 || sFile == null || ftableVa == -1 ? 0
                : (int) ((elf.sizeOf("ftable") - ftableFileOff) / sFile.size());
    }

    /** The process table at step. */
    View view(int step) {
        Snap s = snap(step, false);
        List<Proc> used = s.all.stream().filter(p -> !p.state().equals("UNUSED")).toList();
        return new View(step, nproc, used, s.cpus, s.error);
    }

    /** "0xffffffff80001000" -> its 64-bit value. */
    static long unhex(String h) {
        return h == null ? 0 : Long.parseUnsignedLong(h.substring(2), 16);
    }

    /** Physical address of proc[slot].field (in the kernel image), or -1. */
    long fieldPa(int slot, String field) {
        Structs.Member m = sProc == null ? null : sProc.member(field);
        if (slot < 0 || slot >= nproc || m == null)
            return -1;
        long pa = elf.physOf(procVa + slot * sProc.size() + m.offset());
        return pa < 0 ? -1 : pa;
    }

    /** proc[slot].pagetable at step as a physical address; -1 if the slot is UNUSED or has none. */
    long pageTable(int step, int slot) {
        if (slot < 0 || slot >= nproc || states == null)
            return -1;
        long base = elf.physOf(procVa) + slot * sProc.size();
        return r.memory(step, img -> {
            Structs.Member st = sProc.member("state"), pt = sProc.member("pagetable");
            if (st == null || pt == null || !img.contains(base + pt.offset(), 8))
                return -1L;
            String state = states.get(img.read(base + st.offset(), 4));
            long v = img.read(base + pt.offset(), 8);
            if (v == 0 || "UNUSED".equals(state))
                return -1L;
            long pa = kernel.directMapToPhys(v);
            return pa >= 0 && pa < img.size() ? pa : v;
        });
    }

    /** A snapshot at step; withMemory: also walk each process's page table and name the frames. */
    Snap snap(int step, boolean withMemory) {
        Snap s = new Snap(step);
        if (nproc == 0 || states == null) {
            s.error = "no proc[] or struct proc in the kernel's symbols";
            return s;
        }
        r.memory(step, img -> {
            new Reader(img, s, step, withMemory).read();
            return null;
        });
        return s;
    }

    /** Reads one snapshot from the memory image. */
    private final class Reader {
        final MemoryImage img;
        final Snap s;
        final int step;
        final boolean withMemory;
        /* the kernel page table (0 before kvminit) */
        final long kroot;
        final long procPa;
        /* paging on at step (read once: each r.paging() replays registers) */
        final boolean paging;
        /* pipes of open files: pipe address -> its fields, for chan names */
        final Map<Long, Long> pipes = new HashMap<>();

        Reader(MemoryImage img, Snap s, int step, boolean withMemory) {
            this.img = img;
            this.s = s;
            this.step = step;
            this.withMemory = withMemory;
            long kpt = kptVa == -1 ? 0 : read(kernel.directMapToPhys(kptVa), 8);
            this.kroot = kpt == 0 ? 0 : toPhys(kpt);
            this.procPa = elf.physOf(procVa);
            this.paging = r.paging(step);
        }

        /** A kernel pointer's physical address (direct map; a physical one stays as it is). */
        long toPhys(long kva) {
            long pa = kernel.directMapToPhys(kva);
            return pa >= 0 && pa < img.size() ? pa : kva;
        }

        /** Physical address of a kernel virtual address: through the kernel page table, else the direct map. */
        long kpa(long kva) {
            if (kroot != 0 && paging) {
                long pa = img.translate(kroot, kva);
                if (pa >= 0)
                    return pa;
            }
            return toPhys(kva);
        }

        long read(long pa, int size) {
            return pa >= 0 && img.contains(pa, size) ? img.read(pa, size) : 0;
        }

        long field(long base, Structs.Struct t, String m, int size) {
            Structs.Member mm = t.member(m);
            return mm == null ? 0 : read(base + mm.offset(), size);
        }

        long off(Structs.Struct t, String m) {
            Structs.Member mm = t.member(m);
            return mm == null ? -1 : mm.offset();
        }

        String cstring(long pa, int max) {
            byte[] b = new byte[max];
            int n = 0;
            while (n < max && img.contains(pa + n, 1)) {
                byte c = (byte) img.read(pa + n, 1);
                if (c == 0)
                    break;
                b[n++] = c;
            }
            return new String(b, 0, n, StandardCharsets.ISO_8859_1);
        }

        void read() {
            long size = sProc.size();
            int[] cpuOf = new int[nproc];
            java.util.Arrays.fill(cpuOf, -1);
            List<Cpu> cpus = new ArrayList<>();
            long cpusPa = elf.physOf(cpusVa);
            for (int i = 0; i < ncpu; i++) {
                long p = field(cpusPa + i * sCpu.size(), sCpu, "proc", 8);
                int slot = slotOf(p);
                if (slot >= 0)
                    cpuOf[slot] = i;
                if (i == 0 || p != 0)
                    cpus.add(new Cpu(i, slot >= 0 ? slot : null));
            }
            s.cpus = cpus;
            for (int i = 0; i < nproc; i++)
                readSlot(i, procPa + i * size, cpuOf[i]);
            fileTable();
            if (withMemory) {
                s.free = AddressSpace.freeList(kernel, img);
                owners();
            }
        }

        int slotOf(long p) {
            if (p == 0 || !Addr.in(p, procVa, nproc * sProc.size()))
                return -1;
            long d = p - procVa;
            return d % sProc.size() == 0 ? (int) (d / sProc.size()) : -1;
        }

        String procName(int slot) {
            long base = procPa + slot * sProc.size();
            Structs.Member nm = sProc.member("name");
            return nm == null ? "" : cstring(base + nm.offset(), 16);
        }

        void readSlot(int slot, long base, int cpu) {
            Map<String, Field> f = new LinkedHashMap<>();
            int stv = (int) field(base, sProc, "state", 4);
            String state = states.getOrDefault((long) stv, Integer.toString(stv));
            long chan = field(base, sProc, "chan", 8);
            int killed = (int) field(base, sProc, "killed", 4);
            int xstate = (int) field(base, sProc, "xstate", 4);
            int pid = (int) field(base, sProc, "pid", 4);
            long parent = field(base, sProc, "parent", 8);
            long kstack = field(base, sProc, "kstack", 8);
            long sz = field(base, sProc, "sz", 8);
            long pt = field(base, sProc, "pagetable", 8);
            long tf = field(base, sProc, "trapframe", 8);
            long cwd = field(base, sProc, "cwd", 8);
            String name = procName(slot);
            int pslot = slotOf(parent);
            boolean used = !state.equals("UNUSED");

            List<Fd> files = new ArrayList<>();
            long ofOff = off(sProc, "ofile");
            List<long[]> fdRanges = new ArrayList<>();
            for (int fd = 0; fd < nofile && used; fd++) {
                long slotPa = base + ofOff + 8L * fd;
                long fp = read(slotPa, 8);
                if (fp == 0)
                    continue;
                long[] ranges = new long[4];
                Fd d = file(fd, fp, ranges);
                files.add(d);
                f.put("fd " + fd, new Field(fdText(d), List.<long[]>of(new long[] {slotPa, 8})));
            }
            /* descriptors that were closed: their ofile slots are 0 now */
            for (int fd = 0; fd < nofile; fd++)
                if (!f.containsKey("fd " + fd))
                    f.put("fd " + fd, new Field("", List.of(new long[] {base + ofOff + 8L * fd, 8})));

            String cwdText = used && cwd != 0 ? inodeName(cwd) : "";
            List<String> where = List.of();
            String resume = null;
            if (used && cpu < 0 && sContext != null) {
                long ctx = base + off(sProc, "context");
                long rbp = field(ctx, sContext, "rbp", 8);
                where = chain(rbp);
                Structs.Member pc = sContext.member("rip") != null ? sContext.member("rip")
                        : sContext.member("ra");
                if (pc != null)
                    resume = function(read(ctx + pc.offset(), 8));
            }
            String chanName = chan == 0 ? null : chanName(chan);
            Proc p = new Proc(slot, hex(procVa + slot * sProc.size()), pid, name, state, hex(chan),
                    chanName, where, resume, pslot, pslot >= 0 ? pidOf(pslot) : 0,
                    pslot >= 0 ? procName(pslot) : null, sz, killed != 0, xstate, hex(pt),
                    hex(kstack), hex(tf), cpu >= 0 ? cpu : null, files, cwdText);
            s.all.add(p);

            put(f, "state", state, base, "state", 4);
            put(f, "pid", Integer.toString(pid), base, "pid", 4);
            put(f, "name", name, base, "name", 16);
            put(f, "parent", pslot >= 0 ? "proc[" + pslot + "] " + procName(pslot) : parent == 0 ? "none" : hex(parent),
                    base, "parent", 8);
            put(f, "size", sz + " bytes" + (sz % PAGE == 0 && sz > 0 ? " (" + sz / PAGE + " pages)" : ""),
                    base, "sz", 8);
            put(f, "sleeping on", chan == 0 ? "nothing" : chanName != null ? chanName : hex(chan), base, "chan", 8);
            put(f, "killed", killed != 0 ? "yes" : "no", base, "killed", 4);
            put(f, "exit status", Integer.toString(xstate), base, "xstate", 4);
            put(f, "page table", hex(toPhys(pt)), base, "pagetable", 8);
            put(f, "cwd", cwdText, base, "cwd", 8);
            /* where the process will start or go on (its saved context), and
             * the user registers it returns to (its trap frame): shown for a
             * process being created (fork: rip = forkret, rax = 0) */
            if (used && sContext != null) {
                long ctx = base + off(sProc, "context");
                Structs.Member pc = sContext.member("rip") != null ? sContext.member("rip") : sContext.member("ra");
                Structs.Member sp = sContext.member("rsp") != null ? sContext.member("rsp") : sContext.member("sp");
                if (pc != null) {
                    long v = read(ctx + pc.offset(), 8);
                    String fn = function(v);
                    f.put(CONTEXT + pc.name(), new Field(v == 0 ? "0" : hex(v) + (fn != null ? " (" + fn + ")" : ""),
                            List.<long[]>of(new long[] {ctx + pc.offset(), 8}), true));
                }
                if (sp != null)
                    f.put(CONTEXT + sp.name(), new Field(hex(read(ctx + sp.offset(), 8)),
                            List.<long[]>of(new long[] {ctx + sp.offset(), 8}), true));
            }
            Structs.Struct stf = kernel.structs().get("trapframe");
            if (used && tf != 0 && stf != null) {
                long tpa = kpa(tf);
                for (String m : new String[] {"rip", "rsp", "rax"}) {
                    Structs.Member mm = stf.member(m);
                    if (mm != null && img.contains(tpa + mm.offset(), 8))
                        f.put(TRAPFRAME + m, new Field(hex(read(tpa + mm.offset(), 8)),
                                List.<long[]>of(new long[] {tpa + mm.offset(), 8}), true));
                }
            }
            /* fds after the scalar fields */
            Map<String, Field> ordered = new LinkedHashMap<>();
            f.forEach((k, v) -> {
                if (!k.startsWith("fd "))
                    ordered.put(k, v);
            });
            f.forEach((k, v) -> {
                if (k.startsWith("fd "))
                    ordered.put(k, v);
            });
            s.fields.add(ordered);

            if (withMemory && used && pt != 0 && paging) {
                long root = toPhys(pt);
                AddressSpace.Walked w = AddressSpace.walk(img, root, true);
                Program prog = r.programAt(root, step);
                String[][] names = AddressSpace.names(kernel, w.leaves(), prog, img.size());
                s.mem.put(slot, new Mem(root, w.leaves(), names, w.tables()));
            }
        }

        void put(Map<String, Field> f, String key, String text, long base, String member, int size) {
            Structs.Member m = sProc.member(member);
            List<long[]> rr = m == null ? List.of() : List.<long[]>of(new long[] {base + m.offset(), size});
            f.put(key, new Field(text, rr));
        }

        int pidOf(int slot) {
            return (int) field(procPa + slot * sProc.size(), sProc, "pid", 4);
        }

        /** fd -> struct file; ranges gets {file pa, size, pipe pa, size}. */
        Fd file(int fd, long fp, long[] ranges) {
            if (sFile == null)
                return new Fd(fd, hex(fp), -1, "?", false, false, 0, hex(fp), "");
            long fpa = kpa(fp);
            ranges[0] = fpa;
            ranges[1] = sFile.size();
            long t = field(fpa, sFile, "type", 4);
            String type = fileTypes == null ? Long.toString(t)
                    : fileTypes.getOrDefault(t, Long.toString(t)).replace("FD_", "").toLowerCase();
            int ref = (int) field(fpa, sFile, "ref", 4);
            boolean rd = field(fpa, sFile, "readable", 1) != 0;
            boolean wr = field(fpa, sFile, "writable", 1) != 0;
            int index = -1;
            if (ftableVa != -1 && ftableFileOff >= 0) {
                long d = fp - (ftableVa + ftableFileOff);
                if (d >= 0 && d % sFile.size() == 0)
                    index = (int) (d / sFile.size());
            }
            String what, detail;
            switch (type) {
                case "pipe" -> {
                    long pp = field(fpa, sFile, "pipe", 8);
                    what = "pipe " + hex(pp);
                    detail = pipeDetail(pp, ranges);
                }
                case "inode" -> {
                    long ip = field(fpa, sFile, "ip", 8);
                    what = inodeName(ip);
                    int off = (int) field(fpa, sFile, "off", 4);
                    detail = inodeDetail(ip) + " · offset " + off;
                }
                case "device" -> {
                    int major = (int) field(fpa, sFile, "major", 2);
                    String dev = device(major);
                    what = dev != null ? dev : "device " + major;
                    detail = "device " + major + (dev != null ? " (devsw[" + major + "])" : "");
                }
                default -> {
                    what = type;
                    detail = "";
                }
            }
            return new Fd(fd, hex(fp), index, type, rd, wr, ref, what, detail);
        }

        String pipeDetail(long pp, long[] ranges) {
            if (sPipe == null || pp == 0)
                return "";
            long pa = kpa(pp);
            /* not its lock: sleep() releases it after the change that matters */
            Structs.Member data = sPipe.member("data");
            long from = data != null ? data.offset() : 0;
            ranges[2] = pa + from;
            ranges[3] = sPipe.size() - from;
            pipes.put(pp, pa);
            long nread = field(pa, sPipe, "nread", 4) & 0xffffffffL;
            long nwrite = field(pa, sPipe, "nwrite", 4) & 0xffffffffL;
            boolean ro = field(pa, sPipe, "readopen", 4) != 0;
            boolean wo = field(pa, sPipe, "writeopen", 4) != 0;
            long n = nwrite - nread;
            return n + (n == 1 ? " byte" : " bytes") + " in the pipe · read end "
                    + (ro ? "open" : "closed") + " · write end " + (wo ? "open" : "closed");
        }

        String inodeName(long ip) {
            if (sInode == null || ip == 0)
                return ip == 0 ? "" : hex(ip);
            long pa = kpa(ip);
            int inum = (int) field(pa, sInode, "inum", 4);
            return "inode " + inum + (inum == ROOTINO ? " (/)" : "");
        }

        String inodeDetail(long ip) {
            if (sInode == null || ip == 0)
                return "";
            long pa = kpa(ip);
            int type = (int) field(pa, sInode, "type", 2);
            long size = field(pa, sInode, "size", 4) & 0xffffffffL;
            String t = type >= 0 && type < INODE_TYPES.length ? INODE_TYPES[type] : "type " + type;
            return t + ", " + size + " bytes";
        }

        /** A device's name from its read function in devsw[] (consoleread -> console), or null. */
        String device(int major) {
            if (sDevsw == null || devswVa == -1 || major < 0
                    || (major + 1) * sDevsw.size() > elf.sizeOf("devsw"))
                return null;
            long fn = field(elf.physOf(devswVa) + major * sDevsw.size(), sDevsw, "read", 8);
            ElfFile.Symbol sym = fn == 0 ? null : elf.symbolAt(fn);
            if (sym == null || sym.addr() != fn)
                return null;
            return sym.name().endsWith("read") ? sym.name().substring(0, sym.name().length() - 4) : sym.name();
        }

        /** An fd as the file object it points to: "file[0] console (rw)". */
        String fdText(Fd d) {
            String rw = (d.readable() ? "r" : "") + (d.writable() ? "w" : "");
            return (d.file() >= 0 ? "file[" + d.file() + "] " : "") + d.what()
                    + (rw.isEmpty() ? "" : " (" + rw + ")");
        }

        /** Every ftable.file[i] in use, as text, with the ranges it is read from. */
        void fileTable() {
            long base = elf.physOf(ftableVa) + ftableFileOff;
            for (int i = 0; i < nfile; i++) {
                long fpa = base + i * sFile.size();
                int ref = (int) field(fpa, sFile, "ref", 4);
                List<long[]> rr = new ArrayList<>();
                rr.add(new long[] {fpa, sFile.size()});
                if (ref == 0) {
                    s.files.put("file[" + i + "]", new Field("", rr));
                    continue;
                }
                long[] ranges = new long[4];
                Fd d = file(-1, ftableVa + ftableFileOff + i * sFile.size(), ranges);
                String rw = (d.readable() ? "r" : "") + (d.writable() ? "w" : "");
                String t = d.what() + (rw.isEmpty() ? "" : " (" + rw + ")") + " · ref " + d.ref()
                        + (d.type().equals("inode") ? " · " + d.detail() : "");
                s.files.put("file[" + i + "]", new Field(t, rr));
                if (d.type().equals("pipe") && ranges[3] > 0)
                    s.files.putIfAbsent(d.what(), new Field(d.detail(),
                            List.<long[]>of(new long[] {ranges[2], ranges[3]})));
            }
        }

        /** Return addresses up the saved rbp chain from a swtch()ed context, as function names. */
        List<String> chain(long rbp) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < 16 && rbp != 0 && (rbp & 7) == 0; i++) {
                long pa = kpa(rbp);
                if (pa < 0 || !img.contains(pa, 16))
                    break;
                String fn = kernel.callerOf(img.read(pa + 8, 8), false, null);
                if (fn == null)
                    break;
                out.add(fn);
                long next = img.read(pa, 8);
                if (Long.compareUnsigned(next, rbp) <= 0)
                    break;
                rbp = next;
            }
            return out;
        }

        String function(long pc) {
            return pc == 0 ? null : kernel.function(pc, false, null);
        }

        /** What a sleep channel points at, in the kernel's own names. */
        String chanName(long chan) {
            if (Addr.in(chan, procVa, nproc * sProc.size())) {
                long d = chan - procVa;
                int slot = (int) (d / sProc.size());
                Structs.Member m = sProc.memberAt(d % sProc.size());
                return "proc[" + slot + "]" + (m == null || d % sProc.size() == 0 ? "" : "." + m.name());
            }
            for (Map.Entry<Long, Long> e : pipes.entrySet())
                if (sPipe != null && Addr.in(chan, e.getKey(), sPipe.size())) {
                    Structs.Member m = sPipe.memberAt(chan - e.getKey());
                    return "pipe " + hex(e.getKey()) + (m == null ? "" : " " + m.name());
                }
            ElfFile.Symbol sym = elf.objectAt(chan);
            if (sym == null)
                return null;
            return st.path(sym.name(), chan - sym.addr());
        }

        /** Names the frames processes own: page tables, user pages, kernel stacks, trapframes, pipes. */
        void owners() {
            Map<Long, String> o = s.owner;
            if (kroot != 0 && paging) {
                AddressSpace.Walked kw = AddressSpace.walk(img, kroot, false);
                for (long t : kw.tables())
                    o.put(t, "kernel page table");
            }
            for (Proc p : s.all) {
                if (p.state().equals("UNUSED"))
                    continue;
                String who = "proc[" + p.slot() + "]" + (p.name().isEmpty() ? "" : " " + p.name());
                Mem m = s.mem.get(p.slot());
                if (m != null) {
                    for (int i = 0; i < m.leaves().size(); i++) {
                        AddressSpace.Leaf l = m.leaves().get(i);
                        for (long a = l.pa(); a < l.pa() + l.size() && a - l.pa() < MAX_FRAMES * PAGE; a += PAGE)
                            o.putIfAbsent(a, m.names()[i][1] + " of " + who);
                    }
                    for (long t : m.tables())
                        o.put(t, "page table of " + who);
                }
                long ks = unhex(p.kstack());
                if (ks != 0) {
                    long pa = kpa(ks);
                    if (pa >= 0)
                        o.put(pa & ~0xfffL, "kernel stack of " + who);
                }
                long tf = unhex(p.trapframe());
                if (tf != 0) {
                    long pa = kpa(tf);
                    if (pa >= 0)
                        o.putIfAbsent(pa & ~0xfffL, "trapframe of " + who);
                }
                for (Fd d : p.files())
                    if (d.type().equals("pipe")) {
                        long pp = unhex(d.what().substring(5));
                        o.put(kpa(pp) & ~0xfffL, d.what());
                    }
            }
        }
    }
}
