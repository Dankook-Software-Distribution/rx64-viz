package rxviz.elf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal ELF64 little-endian reader: loadable segments (to match code
 * bytes and read instructions) and the symbol table.
 */
public final class ElfFile {

    /** A PT_LOAD segment; flags: PF_X 1, PF_W 2, PF_R 4. */
    public record Segment(long vaddr, long paddr, long offset, long filesz, long memsz, int flags) {
        public boolean executable() { return (flags & 1) != 0; }
    }

    public record Symbol(String name, long addr, long size) {
    }

    private final String name;
    private final Path path;
    private final byte[] data;
    private final List<Segment> loads = new ArrayList<>();
    private final long[] symAddr;
    private final Symbol[] syms;
    private final Map<String, Long> byName = new HashMap<>();
    /* variables (STT_OBJECT with a size), by address */
    private final List<Symbol> objects = new ArrayList<>();
    private final Map<String, Long> sizeByName = new HashMap<>();

    private ElfFile(String name, Path path, byte[] data) {
        this.name = name;
        this.path = path;
        this.data = data;
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 64 || b.getInt(0) != 0x464c457f || data[4] != 2)
            throw new IllegalArgumentException(path + ": not an ELF64 file");

        long phoff = b.getLong(0x20);
        long shoff = b.getLong(0x28);
        int phentsize = Short.toUnsignedInt(b.getShort(0x36));
        int phnum = Short.toUnsignedInt(b.getShort(0x38));
        int shentsize = Short.toUnsignedInt(b.getShort(0x3a));
        int shnum = Short.toUnsignedInt(b.getShort(0x3c));

        for (int i = 0; i < phnum; i++) {
            int p = (int) (phoff + (long) i * phentsize);
            if (b.getInt(p) == 1) /* PT_LOAD */
                loads.add(new Segment(b.getLong(p + 16), b.getLong(p + 24), b.getLong(p + 8),
                        b.getLong(p + 32), b.getLong(p + 40), b.getInt(p + 4)));
        }

        List<Symbol> list = new ArrayList<>();
        for (int i = 0; i < shnum; i++) {
            int s = (int) (shoff + (long) i * shentsize);
            if (b.getInt(s + 4) != 2) /* SHT_SYMTAB */
                continue;
            long off = b.getLong(s + 24);
            long size = b.getLong(s + 32);
            int link = b.getInt(s + 40);
            int str = (int) (shoff + (long) link * shentsize);
            long stroff = b.getLong(str + 24);
            for (long e = off; e + 24 <= off + size; e += 24) {
                int p = (int) e;
                int nameOff = b.getInt(p);
                int type = data[p + 4] & 0xf;
                int shndx = Short.toUnsignedInt(b.getShort(p + 6));
                long value = b.getLong(p + 8);
                long symSize = b.getLong(p + 16);
                /* SHN_UNDEF, and SHN_ABS etc. (assembler counters like
                 * trampoline.S's ".set i, i+1") are not code */
                if (shndx == 0 || shndx >= 0xff00 || type > 2)
                    continue;
                String n = cstring((int) (stroff + nameOff));
                if (n.isEmpty() || n.startsWith(".") || n.startsWith("$"))
                    continue;
                byName.putIfAbsent(n, value);
                sizeByName.putIfAbsent(n, symSize);
                /* code symbols: defined NOTYPE (asm labels) and FUNC;
                   user programs start at address 0 */
                if (type != 1)
                    list.add(new Symbol(n, value, symSize));
                else if (symSize > 0)
                    objects.add(new Symbol(n, value, symSize));
            }
        }
        /* by address; on ties prefer the one with a size (the C function) */
        list.sort(Comparator.comparingLong(Symbol::addr)
                .thenComparing(Comparator.comparingLong(Symbol::size).reversed()));
        List<Symbol> uniq = new ArrayList<>();
        for (Symbol sym : list)
            if (uniq.isEmpty() || uniq.getLast().addr() != sym.addr())
                uniq.add(sym);
        syms = uniq.toArray(Symbol[]::new);
        objects.sort((x, y) -> Long.compareUnsigned(x.addr(), y.addr()));
        symAddr = Arrays.stream(syms).mapToLong(Symbol::addr).toArray();
    }

    public static ElfFile load(String name, Path path) throws IOException {
        return new ElfFile(name, path, Files.readAllBytes(path));
    }

    private String cstring(int off) {
        int end = off;
        while (end < data.length && data[end] != 0)
            end++;
        return new String(data, off, end - off, StandardCharsets.US_ASCII);
    }

    public String name() {
        return name;
    }

    public Path path() {
        return path;
    }

    /** The symbol at or below addr, or null. */
    public Symbol symbolAt(long addr) {
        int i = Arrays.binarySearch(symAddr, addr);
        if (i < 0)
            i = -i - 2;
        return i >= 0 ? syms[i] : null;
    }

    /** The data object (variable) that contains addr, or null. */
    public Symbol objectAt(long addr) {
        int lo = 0, hi = objects.size() - 1;
        Symbol best = null;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (Long.compareUnsigned(objects.get(mid).addr(), addr) <= 0) {
                best = objects.get(mid);
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best != null && Addr.in(addr, best.addr(), best.size()) ? best : null;
    }

    /** End of the code of the symbol that starts at addr: the next symbol, or addr + size. */
    public long symbolEnd(long addr) {
        int i = Arrays.binarySearch(symAddr, addr);
        if (i < 0)
            i = -i - 2;
        if (i >= 0 && i + 1 < symAddr.length)
            return symAddr[i + 1];
        return i >= 0 && syms[i].size() > 0 ? addr + syms[i].size() : addr + 4096;
    }

    /** Address of the named symbol, or -1. */
    public long addressOf(String symbol) {
        return byName.getOrDefault(symbol, -1L);
    }

    /** The loadable segments. */
    public List<Segment> segments() {
        return loads;
    }

    /** Size of the named symbol (0 for most assembler labels), or -1. */
    public long sizeOf(String symbol) {
        return sizeByName.getOrDefault(symbol, -1L);
    }

    /** Load (physical) address of virtual address va, or -1. */
    public long physOf(long va) {
        for (Segment s : loads)
            if (Addr.in(va, s.vaddr(), s.memsz()))
                return s.paddr() + (va - s.vaddr());
        return -1;
    }

    /**
     * The link (virtual) address of code running at its load (physical)
     * address, e.g. boot code before the kernel jumps to its higher-half
     * addresses; -1 if pc is not in a segment loaded somewhere else.
     */
    public long linkedFromLoad(long pc) {
        for (Segment s : loads)
            if (s.paddr() != s.vaddr() && Addr.in(pc, s.paddr(), s.memsz()))
                return s.vaddr() + (pc - s.paddr());
        return -1;
    }

    /** True if the file holds exactly these bytes at virtual address va. */
    public boolean matches(long va, byte[] bytes) {
        for (Segment s : loads) {
            if (!Addr.in(va, s.vaddr(), s.filesz()) || !Addr.in(va + bytes.length - 1, s.vaddr(), s.filesz()))
                continue;
            int off = (int) (s.offset() + (va - s.vaddr()));
            return Arrays.equals(data, off, off + bytes.length, bytes, 0, bytes.length);
        }
        return false;
    }
}
