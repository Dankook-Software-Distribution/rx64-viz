package rxviz.index;

import static rxviz.elf.Addr.hex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What changed between two steps (an event's start and end): proc[]
 * fields, physical frames taken from or given back to kmem.freelist, and
 * each process's user mappings. Both sides are read from the recorded
 * memory; for every change, the step of the store that made it (the last
 * store to a field or page-table entry, the first store into a newly
 * allocated frame, the last into a freed one) and the thread that ran it.
 */
public final class StateDiff {

    /** A field of a slot: its text before and after; step: the store that made the change. */
    public record FieldChange(String field, String before, String after, Integer step, String by) {
    }

    /** kind: created (UNUSED before), freed (UNUSED after) or changed. */
    public record ProcChange(int slot, String kind, int pid, String name, List<FieldChange> fields) {
    }

    /**
     * An entry of the open-file table (file[i], ftable.file[i]) or a pipe
     * (pipe 0x...): its state before and after ("" = not in use).
     */
    public record FileChange(String object, String before, String after, Integer step, String by) {
    }

    /** One frame taken from (allocated) or put on (freed) the free list, and what it holds. */
    public record FrameChange(String pa, String what, Integer step, String by) {
    }

    /**
     * Consecutive user pages of one process that changed alike, in the
     * page table at table. kind: mapped, unmapped, remapped (another
     * frame), rights (other permissions). When the process got a new page
     * table (exec), the old one's pages are unmapped and the new one's
     * mapped.
     */
    public record MapChange(int slot, int pid, String name, String table, String kind, String va, String end,
                            int pages, String label, String paBefore, String paAfter,
                            String rightsBefore, String rightsAfter, Integer step, String by) {
    }

    /**
     * One change in time order: object (proc[3] cat, file[1], pipe 0x...)
     * and field went from before to after through the stores ending at
     * step, made by `by`.
     */
    public record Moment(int step, String by, String call, String object, String field,
                         String before, String after) {
    }

    /**
     * stepsFound: false if [from, to) was too long to scan for the stores
     * (steps are null then). timeline: when other threads ran during
     * [from, to) (a call that slept), every change to proc[] fields, open
     * files and pipes in time order, one per stretch a thread ran; null
     * otherwise (or if there were too many to show). passing: frames taken
     * from the free list and put back during [from, to) (not in allocated
     * / freed).
     */
    public record Diff(int from, int to, List<ProcChange> procs, List<FileChange> files,
                       int freeBefore, int freeAfter,
                       String freeError, List<FrameChange> allocated, List<FrameChange> freed,
                       List<MapChange> mappings, boolean stepsFound, List<Moment> timeline,
                       Integer passing) {
    }

    /** Ranges longer than this are not scanned for the stores that made each change. */
    private static final int MAX_SCAN = 64 << 20;
    private static final int MAX_FRAMES = 512;
    private static final int MAX_MAPS = 256;
    private static final long PAGE = 4096;
    /* values not worth listing for a new process */
    private static final Set<String> DEFAULTS = Set.of("", "0", "no", "nothing", "none");

    private final Recording r;
    private final Processes procs;

    StateDiff(Recording r, Processes procs) {
        this.r = r;
        this.procs = procs;
    }

    /* ---------------- which store made a change ---------------- */

    /** Which stores of a target count: all of them, or those whose (new, old) value pass. */
    private interface Accept {
        boolean test(long value, long old);
    }

    /** A physical range whose first or last store in [from, to) is wanted (of those it accepts). */
    private static final class Target {
        final long pa, len;
        final boolean last;
        final Accept accept;
        int step = -1;

        Target(long pa, long len, boolean last, Accept accept) {
            this.pa = pa;
            this.len = len;
            this.last = last;
            this.accept = accept;
        }
    }

    private final List<Target> targets = new ArrayList<>();

    private Target target(List<long[]> ranges, boolean last) {
        if (ranges.isEmpty())
            return null;
        /* several ranges: one target per range, the field's step is their latest */
        Target t = null;
        for (long[] rg : ranges) {
            Target x = new Target(rg[0], rg[1], last, null);
            targets.add(x);
            if (t == null)
                t = x;
            else
                linked.computeIfAbsent(t, k -> new ArrayList<>()).add(x);
        }
        return t;
    }

    private final Map<Target, List<Target>> linked = new HashMap<>();

    private Target target(long pa, long len, boolean last, Accept accept) {
        Target t = new Target(pa, len, last, accept);
        targets.add(t);
        return t;
    }

    /* frames popped and pushed back during the scan (kmem.freelist followed exactly) */
    private int passing;

    /** One pass over the stores of [from, to): each target's first or last overlapping store. */
    private void findSteps(int from, int to, List<Long> freeAtStart) {
        long head = AddressSpace.freeListAddr(r.kernel());
        FreeListFollower free = new FreeListFollower(r.kernel(), freeAtStart);
        Set<Long> popped = new HashSet<>();
        Map<Long, List<Target>> byPage = new HashMap<>();
        for (Target t : targets)
            for (long p = t.pa >>> 12; p <= (t.pa + t.len - 1) >>> 12; p++)
                byPage.computeIfAbsent(p, k -> new ArrayList<>()).add(t);
        r.scanStores(from, to, (step, pa, size, value, old) -> {
            if (pa == head && size == 8 && head >= 0) {
                long f = free.apply(value);
                if (f > 0)
                    popped.add(f);
                else if (f < 0 && popped.remove(-f - 1))
                    passing++;
            }
            List<Target> ts = byPage.get(pa >>> 12);
            if (ts == null && (pa + size - 1) >>> 12 != pa >>> 12)
                ts = byPage.get((pa + size - 1) >>> 12);
            if (ts == null)
                return;
            for (Target t : ts)
                if (pa < t.pa + t.len && pa + size > t.pa && (t.last || t.step < 0)
                        && (t.accept == null || (pa == t.pa && size == 8 && t.accept.test(value, old))))
                    t.step = step;
        });
    }

    private Integer stepOf(Target t) {
        if (t == null || !scanned)
            return null;
        int s = t.step;
        for (Target x : linked.getOrDefault(t, List.of()))
            s = Math.max(s, x.step);
        return s < 0 ? null : s;
    }

    private boolean scanned;

    /** The thread that ran step: its run's label (proc[1] sh, scheduler, ...). */
    private String by(Integer step) {
        return step == null ? null : r.threadLabel(step);
    }

    /* ---------------- the diff ---------------- */

    Diff diff(int from, int to) {
        Processes.Snap a = procs.snap(from, true);
        Processes.Snap b = procs.snap(to, true);

        /* proc[] fields */
        record Pending(int slot, String kind, int pid, String name, List<Object[]> fields) {
        }
        List<Pending> pend = new ArrayList<>();
        int n = Math.min(a.all.size(), b.all.size());
        for (int i = 0; i < n; i++) {
            Processes.Proc pa = a.all.get(i), pb = b.all.get(i);
            boolean ua = !pa.state().equals("UNUSED"), ub = !pb.state().equals("UNUSED");
            if (!ua && !ub)
                continue;
            List<Object[]> fs = new ArrayList<>();
            Map<String, Processes.Field> fa = a.fields.get(i), fb = b.fields.get(i);
            for (Map.Entry<String, Processes.Field> e : fb.entrySet()) {
                /* the context and trap frame change on every switch and trap:
                 * shown only for a process being created (what fork set up) */
                if (ua && e.getValue().registers())
                    continue;
                Processes.Field x = fa.get(e.getKey()), y = e.getValue();
                String before = x == null ? "" : x.text(), after = y.text();
                if (!ua) {
                    /* an UNUSED slot keeps its last process's values: not this one's */
                    if (!e.getKey().equals("state"))
                        before = "";
                    if (DEFAULTS.contains(after) && !e.getKey().equals("state"))
                        continue;
                }
                if (before.equals(after))
                    continue;
                List<long[]> rr = new ArrayList<>(y.ranges());
                if (x != null)
                    for (long[] q : x.ranges())
                        if (rr.stream().noneMatch(z -> z[0] == q[0] && z[1] == q[1]))
                            rr.add(q);
                fs.add(new Object[] {e.getKey(), before, after, target(rr, true)});
            }
            if (fs.isEmpty())
                continue;
            String kind = !ua ? "created" : !ub ? "freed" : "changed";
            Processes.Proc who = ub ? pb : pa;
            pend.add(new Pending(i, kind, who.pid(), who.name(), fs));
        }

        /* open files */
        List<Object[]> filesT = new ArrayList<>();
        Set<String> objs = new java.util.LinkedHashSet<>(b.files.keySet());
        objs.addAll(a.files.keySet());
        for (String k : objs) {
            Processes.Field x = a.files.get(k), y = b.files.get(k);
            String before = x == null ? "" : x.text(), after = y == null ? "" : y.text();
            if (!before.equals(after))
                filesT.add(new Object[] {k, before, after, target((y != null ? y : x).ranges(), true)});
        }

        /* frames */
        Set<Long> freeA = new HashSet<>(a.free.frames()), freeB = new HashSet<>(b.free.frames());
        List<Long> alloc = new ArrayList<>(), freed = new ArrayList<>();
        for (long f : a.free.frames())
            if (!freeB.contains(f))
                alloc.add(f);
        for (long f : b.free.frames())
            if (!freeA.contains(f))
                freed.add(f);
        alloc.sort(Long::compare);
        freed.sort(Long::compare);
        List<Object[]> allocT = new ArrayList<>(), freedT = new ArrayList<>();
        /* a frame leaves the list when kalloc stores its next into
         * kmem.freelist (the old value was the frame), and joins it when
         * kfree stores the frame there */
        long head = AddressSpace.freeListAddr(r.kernel());
        var kernel = r.kernel();
        for (long f : alloc.subList(0, Math.min(alloc.size(), MAX_FRAMES)))
            allocT.add(new Object[] {f, b.owner.getOrDefault(f, null), head < 0 ? target(f, PAGE, false, null)
                    : target(head, 8, false, (v, o) -> kernel.directMapToPhys(o) == f)});
        for (long f : freed.subList(0, Math.min(freed.size(), MAX_FRAMES)))
            freedT.add(new Object[] {f, a.owner.getOrDefault(f, null), head < 0 ? target(f, PAGE, true, null)
                    : target(head, 8, true, (v, o) -> kernel.directMapToPhys(v) == f)});

        /* user mappings */
        List<Object[]> maps = new ArrayList<>();
        Set<Integer> slots = new TreeSet<>(a.mem.keySet());
        slots.addAll(b.mem.keySet());
        for (int slot : slots)
            mapChanges(slot, a, b, maps);

        scanned = to - from <= MAX_SCAN;
        if (scanned)
            findSteps(from, to, a.free.frames());

        List<ProcChange> pc = new ArrayList<>();
        for (Pending p : pend) {
            List<FieldChange> fs = new ArrayList<>();
            for (Object[] f : p.fields()) {
                Integer s = stepOf((Target) f[3]);
                fs.add(new FieldChange((String) f[0], (String) f[1], (String) f[2], s, by(s)));
            }
            pc.add(new ProcChange(p.slot(), p.kind(), p.pid(), p.name(), fs));
        }
        List<FileChange> fc = new ArrayList<>();
        for (Object[] f : filesT) {
            Integer s = stepOf((Target) f[3]);
            fc.add(new FileChange((String) f[0], (String) f[1], (String) f[2], s, by(s)));
        }
        List<FrameChange> al = frames(allocT), fr = frames(freedT);
        List<MapChange> mc = new ArrayList<>();
        for (Object[] m : maps) {
            MapChange c = (MapChange) m[0];
            @SuppressWarnings("unchecked")
            List<Target> ts = (List<Target>) m[1];
            Integer s = null;
            for (Target t : ts) {
                Integer x = stepOf(t);
                if (x != null && (s == null || x < s))
                    s = x;
            }
            mc.add(new MapChange(c.slot(), c.pid(), c.name(), c.table(), c.kind(), c.va(), c.end(), c.pages(),
                    c.label(), c.paBefore(), c.paAfter(), c.rightsBefore(), c.rightsAfter(), s, by(s)));
        }
        List<Moment> tl = scanned && spansRuns(from, to) ? timeline(from, to, a, b) : null;
        return new Diff(from, to, pc, fc, a.free.frames().size(), b.free.frames().size(),
                a.free.error() != null ? a.free.error() : b.free.error(), al, fr, mc, scanned, tl,
                scanned ? passing : null);
    }

    /* ---------------- in time order ---------------- */

    /** At most this many points in time are read back for a timeline. */
    private static final int MAX_MOMENTS = 400;

    private boolean spansRuns(int from, int to) {
        Tree t = r.tree();
        int a = t.rootAt(from), b = t.rootAt(Math.max(from, to - 1));
        return a != b;
    }

    /** A proc[] field ("p", slot, field) or an open file / pipe ("f", name) and its ranges. */
    private record Tracked(String key, int slot, String field, List<long[]> ranges) {
    }

    /** The text of a tracked thing in a snapshot ("" if absent). */
    private static String text(Processes.Snap s, Tracked k) {
        Processes.Field f = k.slot() >= 0 ? s.fields.get(k.slot()).get(k.field()) : s.files.get(k.field());
        return f == null ? "" : f.text();
    }

    private List<Moment> timeline(int from, int to, Processes.Snap a, Processes.Snap b) {
        /* what to follow: every proc[] field and open-file entry of either side */
        List<Tracked> tracked = new ArrayList<>();
        for (int i = 0; i < Math.min(a.fields.size(), b.fields.size()); i++) {
            boolean used = !a.all.get(i).state().equals("UNUSED") || !b.all.get(i).state().equals("UNUSED");
            if (!used)
                continue;
            for (var e : b.fields.get(i).entrySet()) {
                if (e.getValue().registers())
                    continue;
                List<long[]> rr = new ArrayList<>(e.getValue().ranges());
                Processes.Field x = a.fields.get(i).get(e.getKey());
                if (x != null)
                    rr.addAll(x.ranges());
                tracked.add(new Tracked("p" + i + ":" + e.getKey(), i, e.getKey(), rr));
            }
        }
        Set<String> names = new java.util.LinkedHashSet<>(a.files.keySet());
        names.addAll(b.files.keySet());
        for (String n : names) {
            List<long[]> rr = new ArrayList<>();
            if (a.files.get(n) != null)
                rr.addAll(a.files.get(n).ranges());
            if (b.files.get(n) != null)
                rr.addAll(b.files.get(n).ranges());
            tracked.add(new Tracked("f:" + n, -1, n, rr));
        }
        Map<Long, List<Integer>> byPage = new HashMap<>();
        for (int k = 0; k < tracked.size(); k++)
            for (long[] rg : tracked.get(k).ranges())
                for (long p = rg[0] >>> 12; p <= (rg[0] + rg[1] - 1) >>> 12; p++)
                    byPage.computeIfAbsent(p, x -> new ArrayList<>()).add(k);

        /* the stores to each, cut where another thread's run or another
         * system call starts: {tracked, last step, run, call node} */
        Tree t = r.tree();
        int[] lastStep = new int[tracked.size()], lastRun = new int[tracked.size()],
                lastCall = new int[tracked.size()];
        java.util.Arrays.fill(lastStep, -1);
        List<int[]> ends = new ArrayList<>();
        int[] root = {-1, -1, -1}; /* cached run: id, start, end */
        int[] call = {-1, -1, -1}; /* cached trap: origin, start, end of this part */
        /* kmem.freelist, followed exactly: pops take the head, pushes add one */
        long head = AddressSpace.freeListAddr(r.kernel());
        FreeListFollower free = new FreeListFollower(r.kernel(), a.free.frames());
        int[] freeCount = {free.size()};
        List<int[]> freeEnds = new ArrayList<>(); /* {last step, run, call, count after} */
        int[] freeLast = {-1, -1, -1};
        r.scanStores(from, to, (step, pa, size, value, old) -> {
            boolean isHead = pa == head && size == 8;
            List<Integer> ks = byPage.get(pa >>> 12);
            if (ks == null && !isHead)
                return;
            if (step < root[1] || step >= root[2]) {
                root[0] = t.rootAt(step);
                root[1] = root[0] < 0 ? step : t.start(root[0]);
                root[2] = root[0] < 0 ? step + 1 : t.end(root[0]);
            }
            if (step < call[1] || step >= call[2]) {
                int n = outerTrap(t, step);
                call[0] = n < 0 ? -1 : t.origin(n);
                call[1] = n < 0 ? step : t.start(n);
                call[2] = n < 0 ? step + 1 : t.end(n);
            }
            if (isHead) {
                free.apply(value);
                if (freeLast[0] >= 0 && (freeLast[1] != root[0] || freeLast[2] != call[0]))
                    freeEnds.add(new int[] {freeLast[0], freeLast[1], freeLast[2], freeCount[0]});
                freeCount[0] = free.size();
                freeLast[0] = step;
                freeLast[1] = root[0];
                freeLast[2] = call[0];
            }
            if (ks == null)
                return;
            for (int k : ks) {
                boolean hit = false;
                for (long[] rg : tracked.get(k).ranges())
                    if (pa < rg[0] + rg[1] && pa + size > rg[0]) {
                        hit = true;
                        break;
                    }
                if (!hit)
                    continue;
                if (lastStep[k] >= 0 && (lastRun[k] != root[0] || lastCall[k] != call[0]))
                    ends.add(new int[] {k, lastStep[k], lastRun[k], lastCall[k]});
                lastStep[k] = step;
                lastRun[k] = root[0];
                lastCall[k] = call[0];
            }
        });
        for (int k = 0; k < tracked.size(); k++)
            if (lastStep[k] >= 0)
                ends.add(new int[] {k, lastStep[k], lastRun[k], lastCall[k]});
        if (freeLast[0] >= 0)
            freeEnds.add(new int[] {freeLast[0], freeLast[1], freeLast[2], freeCount[0]});
        ends.sort((x, y) -> Integer.compare(x[1], y[1]));
        if (ends.stream().map(x -> x[1]).distinct().count() > MAX_MOMENTS)
            return null;

        /* read each back right after its last store, and keep the ones that changed */
        String[] prev = new String[tracked.size()];
        for (int k = 0; k < tracked.size(); k++)
            prev[k] = text(a, tracked.get(k));
        boolean[] unused = new boolean[a.all.size()];
        for (int i = 0; i < unused.length; i++)
            unused[i] = a.all.get(i).state().equals("UNUSED");
        List<Moment> out = new ArrayList<>();
        Processes.Snap snap = null;
        for (int[] e : ends) {
            if (snap == null || snap.step != e[1] + 1)
                snap = procs.snap(e[1] + 1, false);
            Tracked k = tracked.get(e[0]);
            String now = text(snap, k);
            if (now.equals(prev[e[0]]))
                continue;
            String before = prev[e[0]];
            String object = k.field();
            if (k.slot() >= 0) {
                Processes.Proc p = snap.all.get(k.slot());
                object = "proc[" + k.slot() + "]" + (p.name().isEmpty() ? "" : " " + p.name());
                if (unused[k.slot()] && !k.field().equals("state"))
                    before = ""; /* a slot being filled: its old values are not this process's */
                if (k.field().equals("state"))
                    unused[k.slot()] = now.equals("UNUSED");
            }
            out.add(new Moment(e[1], e[2] < 0 ? null : r.threadLabel(e[1]), callName(e[3]), object,
                    k.slot() >= 0 ? k.field() : "", before, now));
            prev[e[0]] = now;
        }
        /* the free list's size after each stretch that changed it */
        int count = a.free.frames().size();
        for (int[] f : freeEnds) {
            if (f[3] == count)
                continue;
            out.add(new Moment(f[0], f[1] < 0 ? null : r.threadLabel(f[0]), callName(f[2]), "kmem.freelist",
                    "free frames", Integer.toString(count), Integer.toString(f[3])));
            count = f[3];
        }
        out.sort((x, y) -> Integer.compare(x.step(), y.step()));
        return out;
    }

    /** The outermost trap open at step in its run (a system call, an exception...), or -1. */
    private static int outerTrap(Tree t, int step) {
        int found = -1;
        for (int n = t.innermost(step); n >= 0 && t.kind(n) != Tree.RUN; n = t.parent(n))
            if (t.kind(n) == Tree.INTR)
                found = n;
        return found;
    }

    /** "read" for a system call's trap node, the exception's name, or null. */
    private String callName(int node) {
        if (node < 0)
            return null;
        Syscalls.Call c = r.syscalls().of(node);
        if (c != null)
            return c.kind().equals("syscall") ? c.name() + "()" : c.text();
        return r.tree().label(node);
    }

    private List<FrameChange> frames(List<Object[]> in) {
        List<FrameChange> out = new ArrayList<>();
        for (Object[] f : in) {
            Integer s = stepOf((Target) f[2]);
            out.add(new FrameChange(hex((Long) f[0]), (String) f[1], s, by(s)));
        }
        return out;
    }

    private record Page(long pa, String rights, String label, long pte) {
    }

    private static TreeMap<Long, Page> pages(Processes.Mem m) {
        TreeMap<Long, Page> out = new TreeMap<>(Long::compareUnsigned);
        if (m == null)
            return out;
        for (int i = 0; i < m.leaves().size(); i++) {
            AddressSpace.Leaf l = m.leaves().get(i);
            String rights = "r" + (l.w() ? "w" : "-") + (l.nx() ? "-" : "x") + (l.u() ? " user" : " kernel-only");
            for (long o = 0; o < l.size() && o < 512 * PAGE; o += PAGE)
                out.put(l.va() + o, new Page(l.pa() + o, rights, m.names()[i][1], l.pte()));
        }
        return out;
    }

    private void mapChanges(int slot, Processes.Snap a, Processes.Snap b, List<Object[]> out) {
        Processes.Mem ma = a.mem.get(slot), mb = b.mem.get(slot);
        Processes.Proc who = !b.all.get(slot).state().equals("UNUSED") ? b.all.get(slot) : a.all.get(slot);
        if (ma != null && mb != null && ma.root() != mb.root()) {
            /* a new page table (exec): the old one's pages go, the new one's
             * come; the old table is named by the program that had it */
            compare(slot, a.all.get(slot), ma.root(), pages(ma), new TreeMap<>(), out);
            compare(slot, who, mb.root(), new TreeMap<>(), pages(mb), out);
        } else {
            compare(slot, who, (mb != null ? mb : ma) == null ? 0 : (mb != null ? mb : ma).root(),
                    pages(ma), pages(mb), out);
        }
    }

    private void compare(int slot, Processes.Proc who, long root, TreeMap<Long, Page> pa,
                         TreeMap<Long, Page> pb, List<Object[]> out) {
        TreeSet<Long> vas = new TreeSet<>(Long::compareUnsigned);
        vas.addAll(pa.keySet());
        vas.addAll(pb.keySet());
        MapChange cur = null;
        List<Target> curT = null;
        long next = 0;
        for (long va : vas) {
            Page x = pa.get(va), y = pb.get(va);
            String kind = x == null ? "mapped" : y == null ? "unmapped"
                    : x.pa() != y.pa() ? "remapped" : !x.rights().equals(y.rights()) ? "rights" : null;
            if (kind == null)
                continue;
            String label = (y != null ? y : x).label();
            if (cur != null && va == next && cur.kind().equals(kind) && cur.label().equals(label)) {
                cur = new MapChange(slot, who.pid(), who.name(), cur.table(), kind, cur.va(), hex(va + PAGE),
                        cur.pages() + 1, label, cur.paBefore(), cur.paAfter(), cur.rightsBefore(),
                        cur.rightsAfter(), null, null);
                out.set(out.size() - 1, new Object[] {cur, curT});
            } else {
                if (out.size() >= MAX_MAPS)
                    return;
                cur = new MapChange(slot, who.pid(), who.name(), hex(root), kind, hex(va), hex(va + PAGE), 1,
                        label, x == null ? null : hex(x.pa()), y == null ? null : hex(y.pa()),
                        x == null ? null : x.rights(), y == null ? null : y.rights(), null, null);
                curT = new ArrayList<>();
                out.add(new Object[] {cur, curT});
            }
            /* the store that wrote the entry's new value (present, this
             * frame), or for an entry that went away the first store to it
             * (*pte = 0; later stores are its table page being freed) */
            if (y != null) {
                long frame = y.pa();
                curT.add(target(y.pte(), 8, true, (v, o) -> (v & 1) != 0 && (v & MemoryImage.ADDR_MASK) == frame));
            }
            if (x != null && (y == null || x.pte() != y.pte()))
                curT.add(target(x.pte(), 8, false, null));
            next = va + PAGE;
        }
    }
}
