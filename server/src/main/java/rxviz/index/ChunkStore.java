package rxviz.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.github.luben.zstd.Zstd;

/**
 * Steps of one recording, cut into chunks of {@link #CHUNK} steps that are
 * compressed independently, so any step can be reached by decompressing
 * one chunk.
 *
 * Chunk layout (little endian):
 *   int nsteps, int nregs, long regs[nregs]        registers at the first step
 *   per step: int insnId, int mask, long value per set bit (ignored for
 *             the first step), short nwrites,
 *             per write: long paddr, byte size, long new, long old
 */
public final class ChunkStore implements AutoCloseable {

    public static final int CHUNK = 4096;

    /**
     * One chunk decoded into arrays. Registers stay as per-step change
     * masks; {@link #registers(int)} replays them up to the asked step,
     * so seeking through memory does not pay for a full register table.
     */
    public static final class Decoded {
        public final int base;
        public final int n;
        public final int nregs;
        public final int[] insn;
        private final long[] head;     /* registers at the first step */
        private final int[] mask;
        private final int[] valStart;  /* index into vals per step */
        private final long[] vals;
        public final int nwrites;
        public final int[] wStep;      /* absolute step */
        public final long[] wAddr;
        public final byte[] wSize;
        public final long[] wNew;
        public final long[] wOld;

        Decoded(int base, ByteBuffer b) {
            this.base = base;
            n = b.getInt();
            nregs = b.getInt();
            insn = new int[n];
            mask = new int[n];
            valStart = new int[n + 1];
            head = new long[nregs];
            for (int r = 0; r < nregs; r++)
                head[r] = b.getLong();
            long[] vs = new long[Math.max(16, n)];
            int nv = 0;
            int cap = 64;
            int[] ws = new int[cap];
            long[] wa = new long[cap];
            byte[] wz = new byte[cap];
            long[] wn = new long[cap];
            long[] wo = new long[cap];
            int nw = 0;
            for (int s = 0; s < n; s++) {
                insn[s] = b.getInt();
                int m = b.getInt();
                int nset = Integer.bitCount(m);
                valStart[s] = nv;
                if (s == 0) {
                    /* the header already holds the first step's registers */
                    b.position(b.position() + 8 * nset);
                } else {
                    mask[s] = m;
                    if (nv + nset > vs.length)
                        vs = Arrays.copyOf(vs, Math.max(vs.length * 2, nv + nset));
                    for (int j = 0; j < nset; j++)
                        vs[nv++] = b.getLong();
                }
                int k = Short.toUnsignedInt(b.getShort());
                if (nw + k > cap) {
                    cap = Math.max(cap * 2, nw + k);
                    ws = Arrays.copyOf(ws, cap);
                    wa = Arrays.copyOf(wa, cap);
                    wz = Arrays.copyOf(wz, cap);
                    wn = Arrays.copyOf(wn, cap);
                    wo = Arrays.copyOf(wo, cap);
                }
                for (int w = 0; w < k; w++, nw++) {
                    ws[nw] = base + s;
                    wa[nw] = b.getLong();
                    wz[nw] = b.get();
                    wn[nw] = b.getLong();
                    wo[nw] = b.getLong();
                }
            }
            valStart[n] = nv;
            vals = vs;
            nwrites = nw;
            wStep = ws;
            wAddr = wa;
            wSize = wz;
            wNew = wn;
            wOld = wo;
        }

        /** Registers of step s (index in chunk) from those of s - 1. */
        private void apply(long[] r, int s) {
            int m = mask[s];
            int k = valStart[s];
            while (m != 0) {
                r[Integer.numberOfTrailingZeros(m)] = vals[k++];
                m &= m - 1;
            }
        }

        /** Visits every step of the chunk with its registers (array reused). */
        public void scan(StepVisitor v) {
            long[] r = head.clone();
            for (int s = 0; s < n; s++) {
                if (s > 0)
                    apply(r, s);
                v.visit(base + s, insn[s], r);
            }
        }

        /** Every register at step (which must be in this chunk). */
        public long[] registers(int step) {
            long[] r = head.clone();
            for (int s = 1; s <= step - base; s++)
                apply(r, s);
            return r;
        }
    }

    public interface StepVisitor {
        void visit(int step, int insnId, long[] regs);
    }

    private final FileChannel ch;
    private long[] offset = new long[16];
    private int[] clen = new int[16];
    private int[] ulen = new int[16];
    private int nchunks;

    private final Map<Integer, Decoded> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Decoded> e) {
            return size() > 12;
        }
    };

    /* writer state: one buffer for the open chunk, one for its compressed form */
    private ByteBuffer cur = ByteBuffer.allocate(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
    private byte[] packed = new byte[0];
    private boolean open;
    private int curSteps;
    private int nregs;
    private int writesAt = -1;   /* position of the open step's nwrites */
    private int openWrites;

    public ChunkStore(Path file) throws IOException {
        this.ch = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
    }

    public int chunks() {
        return nchunks;
    }

    // ---------------- writing ----------------

    /** Start the next step; regs is the full register state at it. */
    public void step(int insnId, int mask, long[] values, int nvalues, long[] regs) {
        closeStep();
        if (!open) {
            open = true;
            nregs = regs.length;
            cur.clear();
            cur.putInt(0).putInt(nregs);
            for (long r : regs)
                cur.putLong(r);
            curSteps = 0;
        }
        ensure(10 + 8 * nvalues);
        cur.putInt(insnId).putInt(mask);
        for (int i = 0; i < nvalues; i++)
            cur.putLong(values[i]);
        writesAt = cur.position();
        cur.putShort((short) 0);
        openWrites = 0;
        curSteps++;
    }

    /** A store by the current step. */
    public void write(long paddr, int size, long newVal, long oldVal) {
        ensure(25);
        cur.putLong(paddr).put((byte) size).putLong(newVal).putLong(oldVal);
        openWrites++;
    }

    private void closeStep() {
        if (writesAt < 0)
            return;
        if (openWrites > 0xffff)
            throw new IllegalStateException("too many stores in one step");
        cur.putShort(writesAt, (short) openWrites);
        writesAt = -1;
        if (curSteps == CHUNK)
            flush();
    }

    private void ensure(int n) {
        if (cur.remaining() >= n)
            return;
        ByteBuffer b = ByteBuffer.allocate(Math.max(cur.capacity() * 2, cur.position() + n))
                .order(ByteOrder.LITTLE_ENDIAN);
        cur.flip();
        b.put(cur);
        cur = b;
    }

    private void flush() {
        if (!open)
            return;
        open = false;
        cur.putInt(0, curSteps);
        int len = cur.position();
        int bound = (int) Zstd.compressBound(len);
        if (packed.length < bound)
            packed = new byte[bound];
        int zlen = (int) Zstd.compressByteArray(packed, 0, packed.length, cur.array(), 0, len, 3);
        if (Zstd.isError(zlen))
            throw new IllegalStateException("zstd: " + Zstd.getErrorName(zlen));
        try {
            long pos = ch.size();
            ch.write(ByteBuffer.wrap(packed, 0, zlen), pos);
            if (nchunks == offset.length) {
                offset = Arrays.copyOf(offset, nchunks * 2);
                clen = Arrays.copyOf(clen, nchunks * 2);
                ulen = Arrays.copyOf(ulen, nchunks * 2);
            }
            offset[nchunks] = pos;
            clen[nchunks] = zlen;
            ulen[nchunks] = len;
            nchunks++;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Finish writing; the store becomes read-only. */
    public void finish() {
        closeStep();
        flush();
    }

    // ---------------- reading ----------------

    public synchronized Decoded chunk(int index) {
        Decoded d = cache.get(index);
        if (d != null)
            return d;
        try {
            ByteBuffer z = ByteBuffer.allocate(clen[index]);
            ch.read(z, offset[index]);
            byte[] raw = Zstd.decompress(z.array(), ulen[index]);
            d = new Decoded(index * CHUNK, ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        cache.put(index, d);
        return d;
    }

    public Decoded chunkOf(int step) {
        return chunk(step / CHUNK);
    }

    public interface WriteVisitor {
        /** false stops the walk */
        boolean visit(int step, long paddr, int size, long newVal, long oldVal);
    }

    /** Stores made by steps in [from, to), oldest first or (reverse) newest first. */
    public void forEachWrite(int from, int to, boolean reverse, WriteVisitor v) {
        int first = from / CHUNK;
        int last = Math.min((to - 1) / CHUNK, nchunks - 1);
        for (int i = 0; i <= last - first; i++) {
            Decoded d = chunk(reverse ? last - i : first + i);
            for (int j = 0; j < d.nwrites; j++) {
                int w = reverse ? d.nwrites - 1 - j : j;
                int st = d.wStep[w];
                if (st >= from && st < to
                        && !v.visit(st, d.wAddr[w], d.wSize[w], d.wNew[w], d.wOld[w]))
                    return;
            }
        }
    }

    public long bytesOnDisk() {
        try {
            return ch.size();
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }
}
