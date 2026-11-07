package rxviz.trace;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.github.luben.zstd.ZstdInputStream;

/**
 * Streams one segment file written by tracer/plugin/rxtrace.c and hands
 * each record to a {@link Visitor}. No objects are created per record;
 * register values arrive in a reused array.
 */
public final class SegmentReader {

    public interface Visitor {
        void mark(String name, long insnCount);

        void registers(String[] names);

        void insn(int id, long vaddr, byte[] bytes);

        /** values[i] for every bit i set in mask (in bit order), nvalues of them. */
        void exec(int vcpu, int id, int mask, long[] values, int nvalues);

        /** cpu: written by the CPU delivering an interrupt/exception (its pushed frame), not by an instruction. */
        void store(int vcpu, boolean io, boolean cpu, int size, long vaddr, long paddr, long lo, long hi);

        void discon(int vcpu, int type, long from, long to);
    }

    private final InputStream in;
    private final byte[] buf = new byte[1 << 20];
    private int pos;
    private int lim;
    private final long[] values = new long[32];

    private SegmentReader(InputStream in) {
        this.in = in;
    }

    public static void read(Path file, Visitor v) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             ZstdInputStream z = new ZstdInputStream(raw)) {
            new SegmentReader(z).run(v);
        }
    }

    /** Make n bytes available at pos; false at a clean end of stream. */
    private boolean fill(int n) throws IOException {
        if (lim - pos >= n)
            return true;
        System.arraycopy(buf, pos, buf, 0, lim - pos);
        lim -= pos;
        pos = 0;
        while (lim < n) {
            int r = in.read(buf, lim, buf.length - lim);
            if (r < 0)
                return false;
            lim += r;
        }
        return true;
    }

    private void need(int n) throws IOException {
        if (!fill(n))
            throw new EOFException("truncated record");
    }

    private int u8() {
        return buf[pos++] & 0xff;
    }

    private static final VarHandle SHORT =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private int u16() {
        int v = Short.toUnsignedInt((short) SHORT.get(buf, pos));
        pos += 2;
        return v;
    }

    private int u32() {
        int v = (int) INT.get(buf, pos);
        pos += 4;
        return v;
    }

    private long u64() {
        long v = (long) LONG.get(buf, pos);
        pos += 8;
        return v;
    }

    private long uN(int size) {
        long v = 0;
        for (int i = 0; i < size; i++)
            v |= (long) (buf[pos + i] & 0xff) << (8 * i);
        pos += size;
        return v;
    }

    private String str(int n) {
        String s = new String(buf, pos, n, StandardCharsets.UTF_8);
        pos += n;
        return s;
    }

    private void run(Visitor v) throws IOException {
        boolean full = false;
        while (fill(1)) {
            int t = u8();
            switch (t) {
                case 'X' -> {
                    need(5);
                    int vcpu = u8();
                    int id = u32();
                    int mask = 0;
                    int n = 0;
                    if (full) {
                        need(4);
                        mask = u32();
                        n = Integer.bitCount(mask);
                        need(8 * n);
                        for (int i = 0; i < n; i++)
                            values[i] = u64();
                    }
                    v.exec(vcpu, id, mask, values, n);
                }
                case 'W' -> {
                    need(19);
                    int vcpu = u8();
                    int flags = u8();
                    int size = u8();
                    long va = u64();
                    long pa = u64();
                    need(size);
                    long lo = uN(Math.min(size, 8));
                    long hi = size > 8 ? uN(size - 8) : 0;
                    v.store(vcpu, (flags & 1) != 0, (flags & 2) != 0, size, va, pa, lo, hi);
                }
                case 'I' -> {
                    need(13);
                    int id = u32();
                    long va = u64();
                    int size = u8();
                    need(size + 2);
                    byte[] bytes = new byte[size];
                    System.arraycopy(buf, pos, bytes, 0, size);
                    pos += size;
                    int dlen = u16();
                    need(dlen);
                    pos += dlen; /* disassembly: empty without capstone */
                    v.insn(id, va, bytes);
                }
                case 'D' -> {
                    need(18);
                    int vcpu = u8();
                    int type = u8();
                    long from = u64();
                    long to = u64();
                    v.discon(vcpu, type, from, to);
                }
                case 'M' -> {
                    need(10);
                    long at = u64();
                    int len = u16();
                    need(len);
                    v.mark(str(len), at);
                }
                case 'R' -> {
                    need(1);
                    int n = u8();
                    String[] names = new String[n];
                    for (int i = 0; i < n; i++) {
                        need(1);
                        int len = u8();
                        need(len);
                        names[i] = str(len);
                    }
                    full = true;
                    v.registers(names);
                }
                default -> throw new IOException("bad record type " + t);
            }
        }
    }
}
