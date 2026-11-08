package rxviz.index;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
 * The address space of the page table in CR3 at a step, and how the
 * physical frames are used then. Read from the recorded memory: the
 * page tables themselves, and the kernel's free list (kmem.freelist,
 * through struct run's next pointers; offsets from DWARF).
 *
 * Names of user pages follow exec's layout: the program's ELF segments,
 * then a guard page (mapped without U), the stack page above it, and the
 * heap (sbrk) above that.
 */
public final class AddressSpace {

    /** Virtual [va, end) mapped alike; pa: first frame; contiguous: the frames follow each other. */
    public record Region(long va, long end, String kind, String label, boolean writable,
                         boolean user, boolean executable, long pa, boolean contiguous,
                         long pageSize, int pages) {
    }

    /**
     * paging: false while CR0.PG is off (no regions then: addresses are
     * physical). tables: page-table pages reachable from cr3; userTables:
     * those of the user half (the rest are shared with every process).
     * sz: p->sz of the process this page table belongs to (-1 if none).
     */
    public record Layout(boolean paging, long cr3, String program, List<Region> regions, int tables,
                         int userTables, long sz) {

        /**
         * With p->sz known: the user addresses below it that no page maps
         * (sbrk grew the process, nothing touched them yet) as regions of
         * kind "unmapped".
         */
        Layout withSz(long sz) {
            if (!paging || sz <= 0)
                return this;
            long top = (sz + 4095) & ~4095L;
            List<Region> out = new ArrayList<>();
            long at = 0;
            for (Region g : regions) {
                if (Long.compareUnsigned(at, top) < 0 && Long.compareUnsigned(g.va(), at) > 0) {
                    long end = Long.compareUnsigned(g.va(), top) < 0 ? g.va() : top;
                    out.add(gap(at, end));
                }
                out.add(g);
                if (Long.compareUnsigned(g.end(), at) > 0)
                    at = g.end();
            }
            if (Long.compareUnsigned(at, top) < 0)
                out.add(gap(at, top));
            out.sort((x, y) -> Long.compareUnsigned(x.va(), y.va()));
            return new Layout(paging, cr3, program, out, tables, userTables, sz);
        }

        private static Region gap(long va, long end) {
            return new Region(va, end, "unmapped", "below p->sz, not mapped", false, true, false,
                    -1, false, 4096, (int) ((end - va) >>> 12));
        }
    }

    /**
     * Frame kinds, one character per 4 KB frame: l low memory below the
     * kernel (firmware, boot), k kernel image, f free (on the free list),
     * o allocated and not mapped by this page table, t a page table of
     * this address space, u a user page of it, s a kernel stack mapped in
     * it, x another kernel page mapped in it (trapframe, ...).
     */
    public record PhysMap(long cr3, String frames, Map<String, Integer> counts, long freeHead,
                          int freeCount, String freeError) {
    }

    /** One frame: its kind, where this address space maps it, its place on the free list (-1). */
    public record Frame(long pa, String kind, List<Long> mappedAt, int freeIndex) {
    }

    /** A leaf mapping; pte: physical address of its page-table entry. */
    record Leaf(long va, long pa, long size, boolean w, boolean u, boolean nx, long pte) {
    }

    private final Recording r;
    private final KernelProfile kernel;
    final int step;
    /* the page table asked for, or -1 for CR3's */
    final long root;
    private final long cr3;
    private final boolean paging;
    private final Program prog;
    /* computed once from memory at step: shared by map(), physMap(), frame() */
    private final Walked walked;
    private final int userTables;
    private final String[][] names;
    private final FreeList free;
    private final byte[] frames;
    private Map<Long, Integer> freeIndex;

    /**
     * The address space and physical frames at step (Recording keeps the
     * last one): of CR3, or with root >= 0 of the page table at root (a
     * process's, which need not be the one in use).
     */
    AddressSpace(Recording r, int step, long root) {
        this.r = r;
        this.kernel = r.kernel();
        this.step = step;
        this.root = root;
        long[] regs = r.registers(step);
        this.cr3 = root >= 0 ? root : regs[r.regIndex("cr3")];
        this.paging = root >= 0 || r.paging(step);
        this.prog = paging ? r.programIn(cr3, step) : null;
        Object[] got = r.memory(step, img -> {
            Walked w = paging ? walk(img, cr3, false) : new Walked(List.of(), List.of());
            int own = paging ? walk(img, cr3, true).tables().size() : 0;
            return new Object[] {w, freeList(kernel, img), img.size(), own};
        });
        this.walked = (Walked) got[0];
        this.userTables = (Integer) got[3];
        this.free = (FreeList) got[1];
        this.names = names(kernel, walked.leaves(), prog, (Long) got[2]);
        this.frames = frameKinds((Long) got[2]);
    }

    private static final long USER_TOP = 1L << 47;

    static boolean userHalf(long va) {
        return Long.compareUnsigned(va, USER_TOP) < 0;
    }

    record Walked(List<Leaf> leaves, List<Long> tables) {
    }

    /** The mappings and tables of the page table at cr3 (lower: the user half only). */
    static Walked walk(MemoryImage img, long cr3, boolean lower) {
        List<Leaf> leaves = new ArrayList<>();
        List<Long> tables = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        MemoryImage.Mappings m = new MemoryImage.Mappings() {
            @Override
            public void table(long pa, int level) {
                /* a table two entries point at (the boot page table's shared
                 * PDPT) is one page */
                if (seen.add(pa))
                    tables.add(pa);
            }

            @Override
            public void leaf(long va, long pa, long size, boolean writable, boolean user,
                             boolean noExecute, long entry, long entryAddr) {
                leaves.add(new Leaf(va, pa, size, writable, user, noExecute, entryAddr));
            }
        };
        if (lower)
            img.enumerateLower(cr3, m);
        else
            img.enumerate(cr3, m);
        return new Walked(leaves, tables);
    }

    /** Kind and label of every leaf, in order. */
    static String[][] names(KernelProfile kernel, List<Leaf> leaves, Program prog, long ram) {
        int n = leaves.size();
        String[][] out = new String[n][];
        for (int i = 0; i < n; i++) {
            Leaf l = leaves.get(i);
            if (l.u()) {
                out[i] = userSegment(l.va(), prog);
                continue;
            }
            /* exec's uvmclear(): a user page between user pages, U cleared */
            boolean guard = userHalf(l.va()) && i > 0 && i + 1 < n
                    && leaves.get(i - 1).u() && leaves.get(i + 1).u()
                    && leaves.get(i - 1).va() + leaves.get(i - 1).size() == l.va()
                    && leaves.get(i + 1).va() == l.va() + l.size();
            KernelProfile.Region g = guard ? null : kernel.what(l.va());
            if (g != null && g.kind().equals("device") && l.pa() >= 0
                    && (l.pa() < ram || (g.pa() >= 0 && l.pa() != g.pa() + (l.va() - g.va()))))
                g = null; /* device addresses mapped elsewhere (the boot page table's large pages) */
            if (guard)
                out[i] = new String[] {"guard", "guard page (no U)"};
            else if (g != null)
                out[i] = new String[] {g.kind(), g.label()};
            else if (l.pa() >= ram)
                out[i] = new String[] {"other", "no RAM here (physical addresses past the end of RAM)"};
            else
                out[i] = new String[] {"kernel", "kernel"};
        }
        /* above the guard page: the stack page, then the heap */
        boolean seenGuard = false, seenStack = false;
        for (int i = 0; i < n; i++) {
            if (!leaves.get(i).u()) {
                if (out[i][0].equals("guard"))
                    seenGuard = true;
                continue;
            }
            if (out[i][0].equals("user") && seenGuard) {
                out[i] = seenStack ? new String[] {"heap", "heap (sbrk)"}
                        : new String[] {"user-stack", "user stack"};
                seenStack = true;
            }
        }
        return out;
    }

    private static String[] userSegment(long va, Program prog) {
        if (prog != null)
            for (ElfFile.Segment s : prog.elf().segments())
                if (Addr.in(va, s.vaddr() & ~0xfffL, s.memsz() + (s.vaddr() & 0xfff)))
                    return s.executable()
                            ? new String[] {"user-text", prog.name() + " text"}
                            : new String[] {"user-data", prog.name() + " data"};
        return new String[] {"user", "user"};
    }

    Layout map() {
        if (!paging)
            return new Layout(false, cr3, null, List.of(), 0, 0, -1);
        Walked w = walked;
        {
            List<Region> out = new ArrayList<>();
            Leaf first = null;
            int pages = 0;
            boolean contiguous = true;
            long next = 0, nextPa = 0;
            String[] cur = null;
            for (int i = 0; i <= w.leaves().size(); i++) {
                Leaf l = i < w.leaves().size() ? w.leaves().get(i) : null;
                boolean same = l != null && first != null && l.va() == next
                        && l.size() == first.size() && l.w() == first.w() && l.u() == first.u()
                        && l.nx() == first.nx() && Arrays.equals(names[i], cur);
                if (!same && first != null) {
                    out.add(new Region(first.va(), next, cur[0], cur[1], first.w(), first.u(),
                            !first.nx(), first.pa(), contiguous, first.size(), pages));
                    first = null;
                }
                if (l == null)
                    break;
                if (first == null) {
                    first = l;
                    cur = names[i];
                    pages = 0;
                    contiguous = true;
                } else if (l.pa() != nextPa) {
                    contiguous = false;
                }
                pages++;
                next = l.va() + l.size();
                nextPa = l.pa() + l.size();
            }
            return new Layout(true, cr3, prog == null ? null : prog.name(), out, w.tables().size(),
                    userTables, -1);
        }
    }

    /* ---------------- physical frames ---------------- */

    record FreeList(long head, List<Long> frames, String error) {
    }

    /** Physical address of kmem.freelist (the list's head pointer), or -1 if the kernel has none. */
    static long freeListAddr(KernelProfile kernel) {
        Structs.Struct kmem = kernel.structs().ofVariable("kmem");
        long va = kernel.kernelElf().addressOf("kmem");
        if (kmem == null || kmem.member("freelist") == null || va == -1)
            return -1;
        long pa = kernel.kernelElf().physOf(va);
        return pa < 0 ? -1 : pa + kmem.member("freelist").offset();
    }

    /** kmem.freelist: struct run pages linked through run.next. */
    static FreeList freeList(KernelProfile kernel, MemoryImage img) {
        Structs.Struct run = kernel.structs().get("run");
        long headPa = freeListAddr(kernel);
        if (headPa < 0 || run == null)
            return new FreeList(0, List.of(), "no kmem.freelist in the kernel's symbols");
        if (!img.contains(headPa, 8))
            return new FreeList(0, List.of(), "kmem is not in memory");
        long nextOff = run.member("next") == null ? 0 : run.member("next").offset();
        long head = img.read(headPa, 8);
        List<Long> frames = new ArrayList<>();
        long limit = img.size() / 4096 + 1;
        for (long p = head; p != 0; ) {
            long f = kernel.directMapToPhys(p);
            if (f < 0 || (f & 0xfff) != 0 || !img.contains(f + nextOff, 8))
                return new FreeList(head, frames, "free list points outside RAM: 0x" + Long.toHexString(p));
            if (frames.size() > limit)
                return new FreeList(head, frames, "free list loops");
            frames.add(f);
            p = img.read(f + nextOff, 8);
        }
        return new FreeList(head, frames, null);
    }

    private static boolean kernelMapped(String kind) {
        return switch (kind) {
            case "ram", "device", "kernel-text", "kernel-data", "kernel", "other", "guard" -> false;
            default -> true;
        };
    }

    /** One kind character per frame (see PhysMap), from the snapshot. */
    private byte[] frameKinds(long memSize) {
        byte[] f = new byte[(int) (memSize / 4096)];
        Arrays.fill(f, (byte) 'o');
        long low = Long.MAX_VALUE;
        for (ElfFile.Segment s : kernel.kernelElf().segments()) {
            low = Math.min(low, s.paddr());
            for (long a = s.paddr() & ~0xfffL; a < s.paddr() + s.memsz(); a += 4096)
                set(f, a, 'k');
        }
        for (long a = 0; a < low && a < memSize; a += 4096)
            set(f, a, 'l');
        for (long a : free.frames())
            set(f, a, 'f');
        for (int i = 0; i < walked.leaves().size(); i++) {
            Leaf l = walked.leaves().get(i);
            /* the guard page is the process's own page, mapped without U */
            char c = l.u() || names[i][0].equals("guard") ? 'u' : names[i][0].equals("kstack") ? 's'
                    : kernelMapped(names[i][0]) ? 'x' : 0;
            if (c != 0)
                for (long a = l.pa(); a < l.pa() + l.size(); a += 4096)
                    set(f, a, c);
        }
        for (long t : walked.tables())
            set(f, t, 't');
        return f;
    }

    PhysMap physMap() {
        int[] n = new int[128];
        for (byte b : frames)
            n[b]++;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (char c : "lkfotusx".toCharArray())
            counts.put(String.valueOf(c), n[c]);
        return new PhysMap(cr3, new String(frames, StandardCharsets.US_ASCII), counts, free.head(),
                free.frames().size(), free.error());
    }

    private static void set(byte[] f, long pa, char c) {
        long i = pa >>> 12;
        if (pa >= 0 && i < f.length)
            f[(int) i] = (byte) c;
    }

    Frame frame(long pa) {
        long frame = pa & ~0xfffL;
        List<Long> at = new ArrayList<>();
        for (Leaf l : walked.leaves())
            if (Addr.in(frame, l.pa(), l.size()) && at.size() < 64)
                at.add(l.va() + (frame - l.pa()));
        if (freeIndex == null) {
            freeIndex = new HashMap<>();
            for (int i = 0; i < free.frames().size(); i++)
                freeIndex.putIfAbsent(free.frames().get(i), i);
        }
        long i = frame >>> 12;
        String kind = i < frames.length ? String.valueOf((char) frames[(int) i]) : "?";
        return new Frame(frame, kind, at, freeIndex.getOrDefault(frame, -1));
    }
}
