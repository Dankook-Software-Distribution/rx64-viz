package rxviz.index;

import static rxviz.elf.Addr.hex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import rxviz.config.KernelProfile;
import rxviz.elf.ElfFile;

/**
 * The CPU's descriptor tables at a step: the GDT, the TSS it points to,
 * and the IDT.
 *
 * QEMU does not give plugins GDTR and IDTR, so they come from the
 * instructions that load them: the last lgdt / lidt executed before the
 * step, its memory operand computed from the registers at that step and
 * read from memory then (10 bytes: limit, base). A command recording
 * that loads none uses the boot recording's last value when every load
 * in the boot came before the first user-mode step (a table the kernel
 * sets up once while it starts). TR is the GDT's busy TSS descriptor.
 * The tables are read through CR3 at the step and decoded in the x86-64
 * formats.
 */
public final class CpuTables {

    /** What an instruction loads: 0 nothing, LGDT, LIDT. */
    static final byte LGDT = 1, LIDT = 2;

    /**
     * How a table register got its value. known: false if no load is
     * recorded before the step (firstLoad: the first one after it, or
     * null); fromBoot: taken from the boot recording; step and function:
     * the lgdt / lidt that loaded it.
     */
    public record Load(boolean known, boolean fromBoot, Integer step, String function,
                       String base, int limit, String name, Integer firstLoad) {
    }

    /**
     * One 8-byte GDT entry. kind: null, code, data, tss, tss-high (the
     * second half of a TSS descriptor), ldt, other; selectors: the
     * segment registers that select it now.
     */
    public record Seg(int index, String raw, String kind, String label, boolean present, int dpl,
                      boolean long64, boolean busy, String base, String limit, List<String> selectors) {
    }

    /** One IDT gate (present ones only). type: interrupt or trap gate. */
    public record Gate(int vector, String name, String handler, String handlerName, String type,
                       int dpl, int ist, int selector) {
    }

    /** The 64-bit TSS: rsp[0..2], ist[1..7], the I/O map base; rsp0Name: what rsp0 points at. */
    public record Tss(int gdtIndex, String base, String name, List<String> rsp, String rsp0Name,
                      List<String> ist, int iomb) {
    }

    /**
     * pastLimit: segment registers whose selector points past the GDT's
     * limit ("ss 0x10"): they keep the descriptor they were loaded with.
     */
    public record View(int step, Load gdtr, List<Seg> gdt, Tss tss, Load idtr, List<Gate> idt,
                       String error, List<String> pastLimit) {
    }

    /* ---------------- finding lgdt / lidt ---------------- */

    /** LGDT or LIDT if the instruction is lgdt m / lidt m (0f 01 /2, /3 with a memory operand), else 0. */
    static byte tableLoad(byte[] b) {
        int i = InsnKind.prefixEnd(b);
        if (i < b.length && (b[i] & 0xf0) == 0x40)
            i++;
        if (i + 2 >= b.length)
            return 0;
        if ((b[i] & 0xff) != 0x0f || (b[i + 1] & 0xff) != 0x01)
            return 0;
        int modrm = b[i + 2] & 0xff;
        if ((modrm >> 6) == 3)
            return 0;
        int reg = (modrm >> 3) & 7;
        return reg == 2 ? LGDT : reg == 3 ? LIDT : 0;
    }

    private static final String[] GPR = {"rax", "rcx", "rdx", "rbx", "rsp", "rbp", "rsi", "rdi",
        "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15"};

    /**
     * The effective address of lgdt / lidt's ModRM memory operand, or -1.
     * long64: the CPU is in 64-bit mode (EFER.LMA); otherwise 32-bit
     * addressing (no REX, mod 0 rm 5 is an absolute disp32, not
     * rip-relative, registers are their low 32 bits).
     */
    static long operand(byte[] b, long pc, long[] regs, Recording r, boolean long64) {
        int i = InsnKind.prefixEnd(b);
        int rex = 0;
        if (long64 && i < b.length && (b[i] & 0xf0) == 0x40)
            rex = b[i++] & 0xff;
        i += 2; /* 0f 01 */
        int modrm = b[i++] & 0xff;
        int mod = modrm >> 6, rm = modrm & 7;
        long ea;
        if (rm == 4) {
            int sib = b[i++] & 0xff;
            int scale = sib >> 6, index = ((sib >> 3) & 7) | ((rex & 2) << 2),
                    base = (sib & 7) | ((rex & 1) << 3);
            ea = 0;
            if ((sib & 7) == 5 && mod == 0) {
                ea = readInt(b, i);
                i += 4;
            } else {
                ea = reg(regs, r, base);
            }
            if (index != 4)
                ea += reg(regs, r, index) << scale;
        } else if (rm == 5 && mod == 0) {
            int disp = readInt(b, i);
            if (!long64)
                return disp & 0xffffffffL;
            return pc + b.length + disp; /* rip-relative: from the next instruction */
        } else {
            ea = reg(regs, r, rm | ((rex & 1) << 3));
        }
        if (mod == 1)
            ea += b[i];
        else if (mod == 2)
            ea += readInt(b, i);
        return long64 ? ea : ea & 0xffffffffL;
    }

    /** True if the CPU runs in 64-bit mode at these registers (EFER.LMA; no EFER: assume so). */
    static boolean long64(long[] regs, Recording r) {
        int k = r.regIndex("efer");
        return k < 0 || (regs[k] & 0x400) != 0;
    }

    private static long reg(long[] regs, Recording r, int n) {
        int k = r.regIndex(GPR[n]);
        return k < 0 ? 0 : regs[k];
    }

    private static int readInt(byte[] b, int i) {
        return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff) << 16 | (b[i + 3] & 0xff) << 24;
    }

    /* ---------------- reading the tables ---------------- */

    private final Recording r;
    private final KernelProfile kernel;
    private final ElfFile elf;

    CpuTables(Recording r) {
        this.r = r;
        this.kernel = r.kernel();
        this.elf = kernel.kernelElf();
    }

    /* what each lgdt / lidt loaded: fixed once recorded */
    private final Map<Integer, long[]> loads = new java.util.concurrent.ConcurrentHashMap<>();

    /** {base, limit} loaded by the lgdt / lidt at step (null if unreadable). */
    long[] loaded(int step) {
        long[] v = loads.get(step);
        if (v == null) {
            v = read(step);
            if (v != null)
                loads.put(step, v);
        }
        return v;
    }

    private long[] read(int step) {
        Recording.Insn in = r.insn(step);
        long[] regs = r.registers(step);
        boolean l64 = long64(regs, r) && !kernel.isCode32(in.vaddr());
        long ea = operand(in.bytes(), in.vaddr(), regs, r, l64);
        return r.virtual(step, vm -> {
            /* the base is 8 bytes in 64-bit mode, 4 otherwise */
            Long limit = vm.read(ea, 2), base = vm.read(ea + 2, l64 ? 8 : 4);
            return limit == null || base == null ? null : new long[] {base, limit};
        });
    }

    /** The table register at step: the last load before it (or the boot's). */
    private Load load(int step, byte kind, long[] got) {
        int[] loads = r.tableLoads(kind);
        int i = java.util.Arrays.binarySearch(loads, step);
        int at = i >= 0 ? i - 1 : -i - 2; /* a load at step has not run yet */
        if (at >= 0) {
            long[] v = loaded(loads[at]);
            if (v != null) {
                got[0] = v[0];
                got[1] = v[1];
                return new Load(true, false, loads[at], kernel.function(r.insn(loads[at]).vaddr(), false, null),
                        hex(v[0]), (int) v[1], null, null);
            }
        }
        Integer first = at + 1 < loads.length ? loads[at + 1] : null;
        Recording boot = Recording.bootOf(kernel.id());
        if (boot != null && boot != r && loads.length == 0) {
            int[] bl = boot.tableLoads(kind);
            /* loaded only while the kernel set itself up, before any user code ran */
            int fu = boot.firstUserStep();
            if (bl.length > 0 && fu >= 0 && bl[bl.length - 1] < fu) {
                long[] v = boot.cpuTables().loaded(bl[bl.length - 1]);
                if (v != null) {
                    got[0] = v[0];
                    got[1] = v[1];
                    return new Load(true, true, null,
                            kernel.function(boot.insn(bl[bl.length - 1]).vaddr(), false, null),
                            hex(v[0]), (int) v[1], null, null);
                }
            }
        }
        return new Load(false, false, null, null, null, 0, null, first);
    }

    View view(int step) {
        long[] g = new long[2], d = new long[2];
        Load gdtr = load(step, LGDT, g);
        Load idtr = load(step, LIDT, d);
        long[] regs = r.registers(step);
        return r.virtual(step, vm -> {
            Load gl = gdtr.known() ? named(gdtr, g[0], vm) : gdtr;
            Load il = idtr.known() ? named(idtr, d[0], vm) : idtr;
            List<Seg> gdt = gdtr.known() ? gdt(vm, g[0], (int) g[1], regs) : List.of();
            Tss tss = null;
            for (Seg s : gdt)
                if (s.kind().equals("tss") && s.busy())
                    tss = tss(vm, s);
            List<Gate> idt = idtr.known() ? idt(vm, d[0], (int) d[1]) : List.of();
            List<String> past = new ArrayList<>();
            for (String name : SELECTORS) {
                int k = r.regIndex(name);
                long sel = k < 0 ? 0 : regs[k];
                if (gdtr.known() && sel != 0 && (sel & 4) == 0 && (sel | 7) > g[1])
                    past.add(name + " " + hex(sel));
            }
            return new View(step, gl, gdt, tss, il, idt, null, past);
        });
    }

    private Load named(Load l, long base, MemoryImage.Virtual vm) {
        return new Load(l.known(), l.fromBoot(), l.step(), l.function(), l.base(), l.limit(),
                dataName(base, vm), l.firstLoad());
    }

    /** A kernel data address as C: cpus[0].gdt, kidt, cpuentry.uidt (also through an alias such as CPUENTRY). */
    private String dataName(long va, MemoryImage.Virtual vm) {
        ElfFile.Symbol s = elf.objectAt(va);
        String alias = null;
        if (s == null) {
            long pa = vm.pa(va);
            long kva = pa < 0 ? -1 : elf.linkedFromLoad(pa);
            if (kva == -1 && pa >= 0)
                kva = pa;
            s = kva == -1 ? null : elf.objectAt(kva);
            if (s != null) {
                KernelProfile.Region g = kernel.what(va);
                alias = g != null ? g.label() : hex(va);
                va = kva;
            }
        }
        if (s == null)
            return null;
        String p = kernel.structs().tablePath(s.name(), va - s.addr());
        return alias == null ? p : p + " (at " + alias + ")";
    }

    private static final String[] SELECTORS = {"cs", "ss", "ds", "es", "fs", "gs"};

    private List<Seg> gdt(MemoryImage.Virtual vm, long base, int limit, long[] regs) {
        List<Seg> out = new ArrayList<>();
        int n = Math.min((limit + 1) / 8, 64);
        boolean high = false;
        for (int i = 0; i < n; i++) {
            Long e = vm.read(base + 8L * i, 8);
            if (e == null)
                break;
            List<String> sel = new ArrayList<>();
            for (String name : SELECTORS) {
                int k = r.regIndex(name);
                if (k >= 0 && regs[k] != 0 && (regs[k] & 4) == 0 && (regs[k] >> 3) == i)
                    sel.add(name);
            }
            if (high) {
                high = false;
                out.add(new Seg(i, hex(e), "tss-high", "TSS base, high 32 bits", false, 0, false, false,
                        null, null, sel));
                continue;
            }
            boolean p = (e >>> 47 & 1) != 0, s = (e >>> 44 & 1) != 0, l = (e >>> 53 & 1) != 0;
            int dpl = (int) (e >>> 45 & 3), type = (int) (e >>> 40 & 0xf);
            long segBase = (e >>> 16 & 0xffffffL) | (e >>> 56 & 0xff) << 24;
            long segLimit = (e & 0xffff) | (e >>> 48 & 0xf) << 16;
            String kind, label;
            boolean busy = false;
            if (e == 0) {
                kind = "null";
                label = i == 0 ? "null descriptor" : "unused";
            } else if (s) {
                boolean code = (type & 8) != 0;
                kind = code ? "code" : "data";
                label = (dpl == 0 ? "kernel " : dpl == 3 ? "user " : "DPL " + dpl + " ") + kind
                        + (code ? (l ? ", 64-bit" : ", 32-bit") : "");
            } else if (type == 9 || type == 0xb) {
                kind = "tss";
                busy = type == 0xb;
                label = "TSS" + (busy ? " (busy: loaded in TR)" : " (available)");
                Long hi = i + 1 < n ? vm.read(base + 8L * (i + 1), 8) : null;
                if (hi != null)
                    segBase |= (hi & 0xffffffffL) << 32;
                high = true;
            } else if (type == 2) {
                kind = "ldt";
                label = "LDT";
                high = true;
            } else {
                kind = "other";
                label = "system type " + type;
            }
            boolean desc = kind.equals("tss") || kind.equals("ldt");
            out.add(new Seg(i, hex(e), kind, label, p, dpl, l, busy,
                    desc ? hex(segBase) : null, desc ? hex(segLimit) : null, sel));
        }
        return out;
    }

    private Tss tss(MemoryImage.Virtual vm, Seg s) {
        long base = Long.parseUnsignedLong(s.base().substring(2), 16);
        List<String> rsp = new ArrayList<>(), ist = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            Long v = vm.read(base + 4 + 8L * k, 8);
            rsp.add(v == null ? null : hex(v));
        }
        for (int k = 0; k < 7; k++) {
            Long v = vm.read(base + 36 + 8L * k, 8);
            ist.add(v == null ? null : hex(v));
        }
        Long iomb = vm.read(base + 102, 2);
        String rsp0Name = null;
        if (rsp.get(0) != null) {
            long v = Long.parseUnsignedLong(rsp.get(0).substring(2), 16);
            KernelProfile.StackArea a = kernel.stackOf(v, false);
            var tf = kernel.structs().get("trapframe");
            if (a.kind().equals("trap") && tf != null && v - a.base() == tf.size())
                rsp0Name = "end of struct trapframe in the TRAPFRAME page";
            else if (a.kind().equals("trap"))
                rsp0Name = "TRAPFRAME page + " + hex(v - a.base());
            else
                rsp0Name = !a.kind().equals("unknown") ? "top of " + a.label() : null;
            if (rsp0Name == null) {
                ElfFile.Symbol sym = elf.objectAt(v - 1);
                rsp0Name = sym == null ? null : "end of " + kernel.structs().path(sym.name(), v - 1 - sym.addr());
            }
        }
        return new Tss(s.index(), s.base(), dataName(base, vm), rsp, rsp0Name, ist,
                iomb == null ? 0 : (int) (long) iomb);
    }

    private List<Gate> idt(MemoryImage.Virtual vm, long base, int limit) {
        List<Gate> out = new ArrayList<>();
        int n = Math.min((limit + 1) / 16, 256);
        for (int v = 0; v < n; v++) {
            Long lo = vm.read(base + 16L * v, 8), hi = vm.read(base + 16L * v + 8, 8);
            if (lo == null || hi == null)
                break;
            if ((lo >>> 47 & 1) == 0)
                continue;
            long off = (lo & 0xffff) | (lo >>> 48 & 0xffff) << 16 | (hi & 0xffffffffL) << 32;
            int type = (int) (lo >>> 40 & 0xf);
            String fn = kernel.function(off, false, null);
            out.add(new Gate(v, kernel.vectorName(v), hex(off), fn,
                    type == 0xe ? "interrupt" : type == 0xf ? "trap" : "type " + type,
                    (int) (lo >>> 45 & 3), (int) (lo >>> 32 & 7), (int) (lo >>> 16 & 0xffff)));
        }
        return out;
    }
}
