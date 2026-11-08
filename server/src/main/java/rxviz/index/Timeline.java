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

    /** kind: syscall, interrupt, exception, cr3 (a page-table switch); label and step of the first of count in the bucket. */
    public record Marker(int bucket, int step, String kind, String label, int count, int lane) {
    }

    /**
     * A point the CPU's setup changed, read from the registers: a control
     * register's bits (cr0: +PG), a page table loaded into cr3, a
     * descriptor table loaded (lgdt / lidt), the first user-mode step.
     */
    public record Milestone(int step, String label) {
    }

    /**
     * A stretch a process did not run, by the state its proc[] slot held
     * (SLEEPING, RUNNABLE, ZOMBIE), from the kernel's stores to p->state.
     */
    public record Span(int lane, int from, int to, String state) {
    }

    /** A store that made a sleeping process RUNNABLE: by the thread in lane `by`, for the one in lane `lane`. */
    public record Wake(int step, int by, int lane, String label) {
    }

    public record View(int from, int to, int buckets, List<Lane> lanes, List<Marker> markers,
                       List<Milestone> milestones, List<Span> spans, List<Wake> wakes) {
    }

    private static final int MAX_SPANS = 20000;

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
        Map<Integer, String> firstName = new HashMap<>();
        for (int run : runs) {
            laneOf.putIfAbsent(t.thread(run), laneOf.size());
            firstName.putIfAbsent(t.thread(run), t.label(run));
            name.put(t.thread(run), t.label(run));
        }
        /* the boot CPU's thread runs main(), then becomes the scheduler */
        for (var e : firstName.entrySet())
            if ("boot".equals(e.getValue()) && !"boot".equals(name.get(e.getKey())))
                name.put(e.getKey(), "boot (main), then " + name.get(e.getKey()));
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
        /* page-table switches (mov to cr3), counted per bucket and lane */
        int[] sw = r.cr3Switches();
        Map<Long, Marker> cr3 = new LinkedHashMap<>();
        for (int k = lowerBound(sw, x -> x < first); k < sw.length && sw[k] < to; k++) {
            int b = bucketOf(sw[k], from, span, buckets);
            int root = t.rootAt(sw[k]);
            Integer lane = root < 0 ? null : laneOf.get(t.thread(root));
            int ln = lane == null ? -1 : lane;
            long key = ((long) b << 20) | (ln & 0xffff);
            Marker m = cr3.get(key);
            cr3.put(key, m == null ? new Marker(b, sw[k], "cr3", "page table switch (mov to cr3)", 1, ln)
                    : new Marker(b, m.step(), "cr3", m.label(), m.count() + 1, ln));
        }
        List<Marker> markers = new ArrayList<>(marks.values());
        markers.addAll(cr3.values());
        List<Milestone> ms = new ArrayList<>();
        for (Milestone m : r.milestones())
            if (m.step() >= from && m.step() < to)
                ms.add(m);
        List<Span> spans = new ArrayList<>();
        List<Wake> wakes = new ArrayList<>();
        states(r, laneOf, from, to, spans, wakes);
        return new View(from, to, buckets, lanes, markers, ms, spans, wakes);
    }

    /**
     * Each process lane's stretches between its runs, cut where its
     * slot's state changed, and the wakeups (SLEEPING -> RUNNABLE) into
     * it. A slot used by two processes in turn belongs to the earlier one
     * until it is freed (UNUSED), to the later one after.
     */
    private static void states(Recording r, Map<Integer, Integer> laneOf, int from, int to,
                               List<Span> spans, List<Wake> wakes) {
        Tree t = r.tree();
        ProcStates ps = r.procStates();
        Map<Long, String> names = r.kernel().structs().enumWith("SLEEPING");
        if (names == null || ps.slots() == 0)
            return;
        int steps = r.steps();
        /* each thread's runs, in order */
        Map<Integer, List<int[]>> runsOf = new LinkedHashMap<>();
        for (int run : t.runs())
            runsOf.computeIfAbsent(t.thread(run), k -> new ArrayList<>()).add(new int[] {t.start(run), t.end(run)});
        /* the threads of each slot, in order of first run */
        Map<Integer, List<Integer>> bySlot = new HashMap<>();
        for (int th : runsOf.keySet()) {
            int slot = t.threadSlot(th);
            if (slot >= 0 && slot < ps.slots())
                bySlot.computeIfAbsent(slot, k -> new ArrayList<>()).add(th);
        }
        for (var e : bySlot.entrySet()) {
            int slot = e.getKey();
            List<Integer> ths = e.getValue();
            for (int j = 0; j < ths.size(); j++) {
                int th = ths.get(j);
                List<int[]> rs = runsOf.get(th);
                int lo = j == 0 ? 0 : freedAfter(ps, names, slot, runsOf.get(ths.get(j - 1)).getLast()[1], rs.getFirst()[0]);
                int hi = j + 1 == ths.size() ? steps
                        : freedAfter(ps, names, slot, rs.getLast()[1], runsOf.get(ths.get(j + 1)).getFirst()[0]);
                Integer lane = laneOf.get(th);
                if (lane == null)
                    continue;
                /* the stretches it did not run: before, between and after its runs */
                int at = lo;
                for (int k = 0; k <= rs.size(); k++) {
                    int gapEnd = k < rs.size() ? rs.get(k)[0] : hi;
                    gap(ps, names, slot, lane, Math.max(at, from), Math.min(gapEnd, to), spans);
                    if (k < rs.size())
                        at = rs.get(k)[1];
                }
            }
        }
        /* wakeups: the thread whose store made a sleeping slot RUNNABLE */
        for (int i = ps.firstAt(from); i < ps.size() && ps.step(i) < to && wakes.size() < MAX_SPANS; i++) {
            if (!"RUNNABLE".equals(names.get((long) ps.value(i))))
                continue;
            int s = ps.step(i), slot = ps.slot(i);
            if (!"SLEEPING".equals(names.get((long) ps.at(slot, s))))
                continue;
            int root = t.rootAt(s);
            Integer by = root < 0 ? null : laneOf.get(t.thread(root));
            Integer lane = null;
            List<Integer> ths = bySlot.get(slot);
            for (int k = 0; ths != null && k < ths.size(); k++)
                if (runsOf.get(ths.get(k)).getFirst()[0] <= s || k == 0)
                    lane = laneOf.get(ths.get(k));
            if (by == null || lane == null)
                continue;
            wakes.add(new Wake(s, by, lane, r.threadLabel(s) + " woke proc[" + slot + "] (SLEEPING \u2192 RUNNABLE)"));
        }
    }

    /** The first step in [a, b) at which slot became UNUSED (freed), else b. */
    private static int freedAfter(ProcStates ps, Map<Long, String> names, int slot, int a, int b) {
        for (int i = ps.firstAt(a); i < ps.size() && ps.step(i) < b; i++)
            if (ps.slot(i) == slot && "UNUSED".equals(names.get((long) ps.value(i))))
                return ps.step(i);
        return b;
    }

    /** [a, b) of a lane that did not run, cut by its slot's state; only the states worth showing. */
    private static void gap(ProcStates ps, Map<Long, String> names, int slot, int lane, int a, int b,
                            List<Span> spans) {
        if (a >= b || spans.size() >= MAX_SPANS)
            return;
        int start = a;
        String state = names.get((long) ps.at(slot, a));
        for (int i = ps.firstAt(a); i < ps.size() && ps.step(i) < b; i++) {
            if (ps.slot(i) != slot)
                continue;
            /* a store is part of its step: the new state holds from the next one */
            int s = ps.step(i) + 1;
            add(spans, lane, start, s, state);
            start = s;
            state = names.get((long) ps.value(i));
        }
        add(spans, lane, start, b, state);
    }

    private static void add(List<Span> spans, int lane, int a, int b, String state) {
        if (a < b && state != null && (state.equals("SLEEPING") || state.equals("RUNNABLE") || state.equals("ZOMBIE")))
            spans.add(new Span(lane, a, b, state));
    }

    private static int bucketOf(int step, int from, long span, int buckets) {
        return (int) Math.min(buckets - 1, (step - from) * (long) buckets / span);
    }

    /** Index of the first node of a list (sorted so that `before` holds for a prefix) where it does not hold. */
    private static int lowerBound(int[] nodes, IntPredicate before) {
        return ProcStates.lowerBound(nodes.length, k -> before.test(nodes[k]));
    }
}
