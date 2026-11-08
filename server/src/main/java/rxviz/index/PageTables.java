package rxviz.index;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import rxviz.config.KernelProfile;

/**
 * Every page-table page at a step and whose it is: the tables reachable
 * from kernel_pagetable and from each process's p->pagetable (and CR3's,
 * if it is none of those), with the level and the first virtual address
 * each one maps. Names an address inside one as the entry it is:
 * "PT[4] of proc[2] pid 3 sh (va 0x4000)".
 */
public final class PageTables {

    /** A table page: whose (owner), its level (0 = PML4), the first va it maps. */
    public record Table(String owner, int level, long base) {
    }

    private final Map<Long, Table> tables = new HashMap<>();
    /* page-table roots walked (physical) */
    private final Set<Long> roots = new HashSet<>();

    PageTables(KernelProfile kernel, MemoryImage img, List<Processes.Proc> procs, long cr3) {
        /* the kernel's first: tables a process shares with it (x86-style's
         * kernel half) are the kernel's */
        long kva = kernel.kernelElf().addressOf("kernel_pagetable");
        long kpa = kva == -1 ? -1 : kernel.kernelElf().physOf(kva);
        if (kpa >= 0 && img.contains(kpa, 8))
            add(img, toPhys(kernel, img.read(kpa, 8)), "kernel_pagetable");
        for (Processes.Proc p : procs) {
            long v = Processes.unhex(p.pagetable());
            if (v != 0)
                add(img, toPhys(kernel, v), "proc[" + p.slot() + "] pid " + p.pid()
                        + (p.name().isEmpty() ? "" : " " + p.name()));
        }
        add(img, cr3 & MemoryImage.ADDR_MASK, "CR3's page table");
    }

    private static long toPhys(KernelProfile kernel, long va) {
        long pa = kernel.directMapToPhys(va);
        return pa >= 0 ? pa : va;
    }

    private void add(MemoryImage img, long root, String owner) {
        if (root <= 0 || !img.contains(root, 4096) || !roots.add(root))
            return;
        walk(img, root, 0, 0, owner);
    }

    private void walk(MemoryImage img, long table, int level, long base, String owner) {
        if (tables.containsKey(table))
            return;
        tables.put(table, new Table(owner, level, base));
        if (level == 3)
            return;
        for (int i = 0; i < 512; i++) {
            long e = img.read(table + 8L * i, 8);
            if ((e & 1) == 0 || (level > 0 && (e & 0x80) != 0))
                continue; /* not present, or a large page */
            long next = e & MemoryImage.ADDR_MASK;
            if (img.contains(next, 4096))
                walk(img, next, level + 1, va(base, level, i), owner);
        }
    }

    /** The first va entry i of a table at level maps (sign-extended to 64 bits). */
    private static long va(long base, int level, int i) {
        long v = base + ((long) i << (39 - 9 * level));
        return (v << 16) >> 16;
    }

    /**
     * "PT[4] of proc[2] pid 3 sh (va 0x4000)": the entry at physical pa,
     * or null if pa is in no table. An address (not an access) at a
     * table's start is the table: "the PML4 of proc[2] pid 3 sh".
     */
    public String entry(long pa, boolean address) {
        Table t = tables.get(pa & ~0xfffL);
        if (t == null)
            return null;
        if (address && (pa & 0xfff) == 0)
            return "the " + MemoryImage.LEVEL[t.level()] + " of " + t.owner()
                    + (t.level() > 0 ? " (for va 0x" + Long.toHexString(t.base()) + ")" : "");
        int i = (int) ((pa & 0xfff) / 8);
        return MemoryImage.LEVEL[t.level()] + "[" + i + "] of " + t.owner() + " (va 0x" + Long.toHexString(va(t.base(), t.level(), i)) + ")";
    }
}
