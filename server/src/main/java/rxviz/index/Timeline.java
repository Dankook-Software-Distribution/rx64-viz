package rxviz.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * A recording over time, cut into buckets (one per pixel column of the
 * UI): per thread (lane), how much of each bucket it ran and how much of
 * that in user mode; and the traps that started in each bucket.
 * User-mode counts come from per-block counts made while indexing.
 */
public final class Timeline {

    /** Steps per block of user-mode counts. */
    public static final int BLOCK = 256;
    private static final int MAX_BUCKETS = 4096;

    /** run[b]: percent of bucket b this thread ran; user[b]: percent of that in user mode. */
    public record Lane(int thread, String label, int[] run, int[] user) {
    }

    /** kind: syscall, interrupt, exception; label and step of the first of count in the bucket. */
    public record Marker(int bucket, int step, String kind, String label, int count, int lane) {
    }

    public record View(int from, int to, int buckets, List<Lane> lanes, List<Marker> markers) {
    }

    /* by KernelProfile.TrapKind ordinal */
    private static final String[] KINDS = {"syscall", "interrupt", "exception"};

    private Timeline() {
    }

    static View of(Recording r, int from, int to, int buckets) {
        Tree t = r.tree();
        int steps = r.steps();
        from = Math.max(0, Math.min(from, steps - 1));
        to = Math.max(from + 1, Math.min(to, steps));
        buckets = Math.max(1, Math.min(buckets, Math.min(MAX_BUCKETS, to - from)));
        long span = to - from;

        /* lanes: every thread, in order of first appearance; named by its last run */
        int[] runs = t.runs();
        Map<Integer, Integer> laneOf = new LinkedHashMap<>();
        Map<Integer, String> name = new HashMap<>();
        for (int run : runs) {
            laneOf.putIfAbsent(t.thread(run), laneOf.size());
            name.put(t.thread(run), t.label(run));
        }
        long[][] ran = new long[laneOf.size()][buckets];
        double[][] user = new double[laneOf.size()][buckets];

        int first = from;
        int i = lowerBound(runs, n -> t.end(n) <= first);
        for (; i < runs.length && t.start(runs[i]) < to; i++) {
            int run = runs[i];
            int lane = laneOf.get(t.thread(run));
            int a = Math.max(t.start(run), from), e = Math.min(t.end(run), to);
            for (int b = bucketOf(a, from, span, buckets); b < buckets; b++) {
                int b0 = (int) (from + span * b / buckets), b1 = (int) (from + span * (b + 1) / buckets);
                int lo = Math.max(a, b0), hi = Math.min(e, b1);
                if (lo >= hi) {
                    if (b0 >= e)
                        break;
                    continue;
                }
                ran[lane][b] += hi - lo;
                user[lane][b] += r.userSteps(lo, hi);
            }
        }
        List<Lane> lanes = new ArrayList<>();
        for (var e : laneOf.entrySet()) {
            int lane = e.getValue();
            int[] runPct = new int[buckets], userPct = new int[buckets];
            for (int b = 0; b < buckets; b++) {
                long size = (from + span * (b + 1) / buckets) - (from + span * b / buckets);
                runPct[b] = size == 0 ? 0 : (int) Math.ceil(100.0 * ran[lane][b] / size);
                userPct[b] = ran[lane][b] == 0 ? 0 : (int) Math.ceil(100.0 * user[lane][b] / ran[lane][b] - 1e-9);
            }
            lanes.add(new Lane(e.getKey(), name.get(e.getKey()), runPct, userPct));
        }

        /* traps, one marker per bucket, kind and lane */
        int[] traps = t.traps();
        Map<Long, Marker> marks = new LinkedHashMap<>();
        for (int k = lowerBound(traps, n -> t.start(n) < first); k < traps.length && t.start(traps[k]) < to; k++) {
            int n = traps[k];
            int v = t.vector(n);
            int ki = r.kernel().trapKind(v).ordinal();
            String kind = KINDS[ki];
            int b = bucketOf(t.start(n), from, span, buckets);
            Integer lane = laneOf.get(t.thread(n));
            int ln = lane == null ? -1 : lane;
            long key = ((long) b << 20) | (long) ki << 16 | (ln & 0xffff);
            Marker m = marks.get(key);
            if (m == null) {
                String label = r.trapDetail(n);
                marks.put(key, new Marker(b, t.start(n), kind, label != null ? label : t.label(n), 1, ln));
            } else {
                marks.put(key, new Marker(b, m.step(), kind, m.label(), m.count() + 1, ln));
            }
        }
        return new View(from, to, buckets, lanes, new ArrayList<>(marks.values()));
    }

    private static int bucketOf(int step, int from, long span, int buckets) {
        return (int) Math.min(buckets - 1, (step - from) * (long) buckets / span);
    }

    /** Index of the first node of a list (sorted so that `before` holds for a prefix) where it does not hold. */
    private static int lowerBound(int[] nodes, IntPredicate before) {
        int lo = 0, hi = nodes.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (before.test(nodes[mid]))
                lo = mid + 1;
            else
                hi = mid;
        }
        return lo;
    }
}
