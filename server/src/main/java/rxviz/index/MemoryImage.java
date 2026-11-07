package rxviz.index;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
        Arrays.fill(mem, off, mem.length, (byte) 0);
    }

    void clear() {
        Arrays.fill(mem, (byte) 0);
    }

    void writeTo(OutputStream out) throws IOException {
        out.write(mem);
    }

    public long size() {
        return mem.length;
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

    /** What enumerate() finds: tables (level 0 = PML4) and leaf mappings with their effective rights. */
    public interface Mappings {
        void table(long pa, int level);

        void leaf(long va, long pa, long size, boolean writable, boolean user, boolean noExecute,
                  long entry);
    }

    /**
     * Every present mapping of the page table at cr3, in address order
     * (lower half first). A right holds only if every level grants it
     * (NX: if any level sets it), as the MMU checks them.
     */
    public void enumerate(long cr3, Mappings out) {
        enumerate(cr3 & ADDR_MASK, 0, 0, true, true, false, out);
    }

    private void enumerate(long table, int level, long base, boolean w, boolean u, boolean nx,
                           Mappings out) {
        if (!contains(table, 4096))
            return;
        out.table(table, level);
        int shift = 39 - 9 * level;
        for (int i = 0; i < 512; i++) {
            long e = read(table + 8L * i, 8);
            if ((e & 1) == 0)
                continue;
            long va = base | ((long) i << shift);
            if (level == 0 && (va & (1L << 47)) != 0)
                va |= 0xffff000000000000L; /* canonical upper half */
            boolean w2 = w && (e & 2) != 0, u2 = u && (e & 4) != 0, nx2 = nx || e < 0;
            if (level == 3 || (level > 0 && (e & 0x80) != 0)) {
                long size = 1L << shift;
                out.leaf(va, (e & ADDR_MASK) & ~(size - 1), size, w2, u2, nx2, e);
            } else {
                enumerate(e & ADDR_MASK, level + 1, va, w2, u2, nx2, out);
            }
        }
    }

    /** Physical address of va through the page table at cr3, or -1 (like walk(), without the levels). */
    public long translate(long cr3, long va) {
        long table = cr3 & ADDR_MASK;
        for (int l = 0; l < 4; l++) {
            int shift = 39 - 9 * l;
            long ea = table + 8L * ((va >>> shift) & 0x1ff);
            if (!contains(ea, 8))
                return -1;
            long e = read(ea, 8);
            if ((e & 1) == 0)
                return -1;
            if (l == 3 || (l > 0 && (e & 0x80) != 0)) {
                long size = 1L << shift;
                return ((e & ADDR_MASK) & ~(size - 1)) | (va & (size - 1));
            }
            table = e & ADDR_MASK;
        }
        return -1;
    }

    /** Virtual memory as the page table at cr3 maps it; while paging is off, addresses are physical. */
    public Virtual virtual(long cr3, boolean paging) {
        return new Virtual(cr3, paging);
    }

    /** Reads virtual addresses, translating once per 4 KB page. */
    public final class Virtual {
        private final long cr3;
        private final boolean paging;
        private long page = -1, pagePa = -1;

        private Virtual(long cr3, boolean paging) {
            this.cr3 = cr3;
            this.paging = paging;
        }

        public MemoryImage image() {
            return MemoryImage.this;
        }

        /** Physical address of va, or -1 if it is not mapped. */
        public long pa(long va) {
            long p = va & ~0xfffL;
            if (p != page) {
                page = p;
                pagePa = paging ? translate(cr3, p) : p;
            }
            return pagePa < 0 ? -1 : pagePa | (va & 0xfff);
        }

        /** The byte at va, or -1 if it is not mapped (or not in RAM). */
        public int byteAt(long va) {
            long p = pa(va);
            return p >= 0 && contains(p, 1) ? mem[(int) p] & 0xff : -1;
        }

        /** The little-endian word of size bytes at va, or null if any byte is not mapped. */
        public Long read(long va, int size) {
            long v = 0;
            for (int i = 0; i < size; i++) {
                int b = byteAt(va + i);
                if (b < 0)
                    return null;
                v |= (long) b << (8 * i);
            }
            return v;
        }

        /** Up to n bytes at va, fewer if an unmapped page comes first. */
        public byte[] bytes(long va, int n) {
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                int b = byteAt(va + i);
                if (b < 0)
                    return Arrays.copyOf(out, i);
                out[i] = (byte) b;
            }
            return out;
        }

        /** The NUL-terminated string at va, at most max bytes. */
        public String cstring(long va, int max) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < max; i++) {
                int c = byteAt(va + i);
                if (c <= 0)
                    break;
                b.append((char) c);
            }
            return b.toString();
        }
    }
}
