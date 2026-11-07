package rxviz.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import rxviz.elf.Addr;
import rxviz.elf.Disassembly;
import rxviz.elf.ElfFile;
import rxviz.elf.LineTable;

/** A kernel build with its symbols, disassembly and user programs loaded. */
public final class KernelProfile {

    /** Code from the image that runs at another virtual address (not in the ELF). */
    private record Alias(long va, long target, long size) {
    }

    /** Where an instruction address comes from; file/line from DWARF, may be null. */
    public record Location(String binary, String symbol, long offset, String disas,
                           String file, int line) {
        static final Location UNKNOWN = new Location(null, null, 0, null, null, 0);

        public String label() {
            if (symbol == null)
                return "?";
            return offset == 0 ? symbol : symbol + "+0x" + Long.toHexString(offset);
        }
    }

    /** An executable image: the kernel or a user program. */
    public record Program(ElfFile elf, Disassembly disas, LineTable lines) {
        public String name() {
            return elf.name();
        }

        static Program load(String name, Path file, LineTable.SourceTree src) {
            try {
                ElfFile elf = ElfFile.load(name, file);
                return new Program(elf, Disassembly.of(elf), LineTable.of(elf, src));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** The image pc runs from, and the address pc is linked at in it. */
    private record Resolved(Program image, long linked) {
    }

    /** One line of a disassembly listing, at its runtime address. */
    public record Line(long addr, String text, String function) {
    }

    /** x86 exception vectors 0-31: architectural, the same in every kernel. */
    private static final String[] EXCEPTIONS = {
        "divide error", "debug", "NMI", "breakpoint", "overflow", "bound range",
        "invalid opcode", "device not available", "double fault", "coprocessor overrun",
        "invalid TSS", "segment not present", "stack fault", "general protection fault",
        "page fault", "vector 15", "x87 FP error", "alignment check", "machine check",
        "SIMD FP error", "virtualization", "control protection",
    };

    private final String id;
    private final Path dir;
    private final Program kernel;
    private final List<Program> programs;
    private final long kstackBase;
    private final long kstackStride;
    private final int syscallVector;
    private final Map<Integer, String> vectors;
    private final List<String> syscalls;
    private final List<Alias> aliases = new ArrayList<>();
    private final long[] userTrapStackPages;
    private final long bootEntry;

    KernelProfile(RxvizProperties.Kernel k, List<String> syscalls) throws IOException {
        this.id = k.id();
        this.dir = Path.of(k.dir());
        LineTable.SourceTree src = LineTable.SourceTree.of(dir);
        List<Path> users;
        try (Stream<Path> files = Files.list(dir.resolve("user"))) {
            users = files.filter(p -> p.getFileName().toString().startsWith("_")).sorted().toList();
        }
        try {
            this.kernel = Program.load("kernel", dir.resolve("kernel/kernel"), src);
            /* objdump runs per file: load them side by side */
            this.programs = users.parallelStream()
                    .map(p -> Program.load(p.getFileName().toString().substring(1), p, src))
                    .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        this.kstackBase = RxvizProperties.hex(k.kstackBase());
        this.kstackStride = RxvizProperties.hex(k.kstackStride());
        this.syscallVector = k.syscallVector();
        this.vectors = k.vectors() == null ? Map.of() : Map.copyOf(k.vectors());
        this.syscalls = List.copyOf(syscalls);
        if (k.aliases() != null)
            for (RxvizProperties.Alias a : k.aliases()) {
                long target = kernel.elf().addressOf(a.symbol());
                if (target == -1)
                    throw new IllegalArgumentException(id + ": no symbol " + a.symbol());
                aliases.add(new Alias(RxvizProperties.hex(a.va()), target, a.size()));
            }
        String entry = k.bootEntry() == null ? "_entry" : k.bootEntry();
        long entryVa = kernel.elf().addressOf(entry);
        this.bootEntry = entryVa == -1 ? -1 : kernel.elf().physOf(entryVa);
        this.userTrapStackPages = k.userTrapStackPages() == null ? new long[0]
                : k.userTrapStackPages().stream().mapToLong(RxvizProperties::hex).toArray();
    }

    public String id() {
        return id;
    }

    public Path dir() {
        return dir;
    }

    public List<Program> programs() {
        return programs;
    }

    /**
     * Physical address where the kernel starts running (the loader jumps
     * there with paging off), or -1.
     */
    public long bootEntry() {
        return bootEntry;
    }

    /** Process table slot whose kernel stack holds rsp, or -1. */
    public int kstackSlot(long rsp) {
        long page = (rsp - 1) & ~0xfffL;
        long d = page - kstackBase;
        if (d % kstackStride != 0)
            return -1;
        long slot = d / kstackStride;
        return slot >= 0 && slot < 64 ? (int) slot : -1;
    }

    /**
     * True if rsp is on a page that the CPU uses as a stack when trapping
     * from user mode before the kernel switches to the process's kernel
     * stack (riscv-style's TRAPFRAME, mapped in every user page table).
     */
    public boolean isUserTrapStack(long rsp) {
        long page = (rsp - 1) & ~0xfffL;
        for (long p : userTrapStackPages)
            if (p == page)
                return true;
        return false;
    }

    public String vectorName(int vector) {
        String n = vectors.get(vector);
        if (n != null)
            return n;
        if (vector == syscallVector)
            return "syscall";
        return vector < EXCEPTIONS.length ? EXCEPTIONS[vector] : "vector " + vector;
    }

    public boolean isSyscallVector(int vector) {
        return vector == syscallVector;
    }

    public String syscallName(long number) {
        if (number > 0 && number < syscalls.size())
            return syscalls.get((int) number);
        return "#" + number;
    }

    /**
     * User code (CPL 3) runs from program; kernel code from the kernel
     * image, at its link address: code in an alias window (riscv-style's
     * trampoline) or at its load address (x86-style boot code before it
     * jumps high) is mapped to where it is linked. Null if unknown.
     */
    private Resolved resolve(long pc, boolean user, Program program) {
        if (user)
            return program == null ? null : new Resolved(program, pc);
        for (Alias al : aliases)
            if (Addr.in(pc, al.va, al.size))
                return new Resolved(kernel, al.target + (pc - al.va));
        long low = kernel.elf().linkedFromLoad(pc);
        return new Resolved(kernel, low == -1 ? pc : low);
    }

    /** Symbol, disassembly and source line for pc. */
    public Location locate(long pc, boolean user, Program program) {
        Resolved r = resolve(pc, user, program);
        if (r == null)
            return Location.UNKNOWN;
        long a = r.linked();
        ElfFile.Symbol s = r.image().elf().symbolAt(a);
        LineTable.Src src = r.image().lines().at(a);
        return new Location(r.image().name(), s == null ? null : s.name(),
                s == null ? 0 : a - s.addr(), r.image().disas().at(a),
                src == null ? null : src.file(), src == null ? 0 : src.line());
    }

    /** Source line of pc (null if unknown). */
    public LineTable.Src source(long pc, boolean user, Program program) {
        Resolved r = resolve(pc, user, program);
        return r == null ? null : r.image().lines().at(r.linked());
    }

    /** Function symbol at pc, or null. */
    public String function(long pc, boolean user, Program program) {
        Resolved r = resolve(pc, user, program);
        ElfFile.Symbol s = r == null ? null : r.image().elf().symbolAt(r.linked());
        return s == null ? null : s.name();
    }

    /** A kernel or user source file, by path relative to the kernel tree. */
    public Optional<Path> sourceFile(String rel) {
        Path p = dir.resolve(rel).normalize();
        if (!p.startsWith(dir) || !LineTable.isSource(p.getFileName().toString())
                || !Files.isRegularFile(p))
            return Optional.empty();
        return Optional.of(p);
    }

    /** The static disassembly around pc, listed at runtime addresses. */
    public List<Line> listing(long pc, boolean user, Program program, int before, int after) {
        Resolved r = resolve(pc, user, program);
        if (r == null)
            return List.of();
        ElfFile elf = r.image().elf();
        long delta = pc - r.linked();
        List<Line> out = new ArrayList<>();
        for (var e : r.image().disas().around(r.linked(), before, after)) {
            ElfFile.Symbol s = elf.symbolAt(e.getKey());
            String fn = s != null && s.addr() == e.getKey() ? s.name() : null;
            out.add(new Line(e.getKey() + delta, e.getValue(), fn));
        }
        return out;
    }

    /**
     * The instructions of the function containing pc that come from one
     * source line: `line`, or pc's own line when line <= 0. A line can
     * compile to several separate pieces (loops); all of them are listed.
     */
    public List<Line> lineInstructions(long pc, boolean user, Program program, int line) {
        Resolved r = resolve(pc, user, program);
        if (r == null)
            return List.of();
        ElfFile elf = r.image().elf();
        LineTable lines = r.image().lines();
        LineTable.Src here = lines.at(r.linked());
        ElfFile.Symbol fn = elf.symbolAt(r.linked());
        if (here == null || fn == null)
            return List.of();
        int want = line > 0 ? line : here.line();
        long delta = pc - r.linked();
        List<Line> out = new ArrayList<>();
        for (var e : r.image().disas().between(fn.addr(), elf.symbolEnd(fn.addr()))) {
            LineTable.Src src = lines.at(e.getKey());
            if (src != null && src.line() == want && src.file().equals(here.file()))
                out.add(new Line(e.getKey() + delta, e.getValue(), null));
        }
        return out;
    }
}
