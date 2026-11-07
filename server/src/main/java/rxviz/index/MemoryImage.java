package rxviz.index;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.github.luben.zstd.ZstdInputStream;

/** Guest physical memory as a byte array, plus x86-64 4-level paging. */
public final class MemoryImage {

    public static final long ADDR_MASK = 0x000ffffffffff000L;
    private static final String[] LEVEL = {"PML4", "PDPT", "PD", "PT"};

    private final byte[] mem;

    public MemoryImage(int size) {
        mem = new byte[size];
    }

    /** Replace the contents with a zstd-compressed image (zeros past its end). */
    void fill(Path zst) throws IOException {
        int off = 0;
        try (InputStream in = new ZstdInputStream(Files.newInputStream(zst))) {
            while (off < mem.length) {
                int r = in.read(mem, off, mem.length - off);
                if (r < 0)
                    break;
                off += r;
            }
        }
        java.util.Arrays.fill(mem, off, mem.length, (byte) 0);
    }

    void clear() {
        java.util.Arrays.fill(mem, (byte) 0);
    }

    void writeTo(OutputStream out) throws IOException {
        out.write(mem);
    }

    public boolean contains(long pa, int len) {
        return pa >= 0 && pa + len <= mem.length;
    }

    public long read(long pa, int size) {
        long v = 0;
        for (int i = 0; i < size; i++)
            v |= (long) (mem[(int) pa + i] & 0xff) << (8 * i);
        return v;
    }

    public void write(long pa, int size, long v) {
        for (int i = 0; i < size; i++)
            mem[(int) pa + i] = (byte) (v >>> (8 * i));
    }

    public void copy(long pa, byte[] dst, int off, int len) {
        System.arraycopy(mem, (int) pa, dst, off, len);
    }

    // ---------------- paging ----------------

    /** One page-table entry on the way from CR3 to a page. */
    public record Level(String table, int index, long entryAddr, long entry) {
        public boolean present() { return (entry & 1) != 0; }
        public boolean writable() { return (entry & 2) != 0; }
        public boolean user() { return (entry & 4) != 0; }
        public boolean largePage() { return (entry & 0x80) != 0; }
        public boolean noExecute() { return entry < 0; }
    }

    /** levels walked; pa is -1 when a level is not present. */
    public record Walk(long va, long cr3, List<Level> levels, long pa, long pageSize) {
    }

    public Walk walk(long cr3, long va) {
        List<Level> levels = new ArrayList<>();
        long table = cr3 & ADDR_MASK;
        for (int l = 0; l < 4; l++) {
            int shift = 39 - 9 * l;
            int idx = (int) ((va >>> shift) & 0x1ff);
            long ea = table + 8L * idx;
            if (!contains(ea, 8))
                return new Walk(va, cr3, levels, -1, 0);
            long e = read(ea, 8);
            Level lv = new Level(LEVEL[l], idx, ea, e);
            levels.add(lv);
            if (!lv.present())
                return new Walk(va, cr3, levels, -1, 0);
            if (l == 3 || (l > 0 && lv.largePage())) {
                long pageSize = 1L << shift;
                long base = (e & ADDR_MASK) & ~(pageSize - 1);
                return new Walk(va, cr3, levels, base | (va & (pageSize - 1)), pageSize);
            }
            table = e & ADDR_MASK;
        }
        return new Walk(va, cr3, levels, -1, 0);
    }

    /** Physical address of va, or -1. */
    public long translate(long cr3, long va) {
        return walk(cr3, va).pa();
    }
}
