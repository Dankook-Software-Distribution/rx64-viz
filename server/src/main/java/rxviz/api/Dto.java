package rxviz.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import rxviz.config.KernelProfile;
import rxviz.elf.Addr;
import rxviz.index.AddressSpace;
import rxviz.index.MemoryImage;
import rxviz.index.Recording;
import rxviz.index.StackView;
import rxviz.index.Syscalls;
import rxviz.index.Tree;
import rxviz.session.BootService;
import rxviz.session.Session;

/** JSON shapes. 64-bit values travel as "0x..." strings (JS numbers lose bits). */
public final class Dto {

    private Dto() {
    }

    public static String hex(long v) {
        return Addr.hex(v);
    }

    public record BootDto(String id, String status, String error, Recording.Summary summary) {
        static BootDto of(String kernelId, BootService.Boot b) {
            return new BootDto(BootService.recordingId(kernelId), b.status.name(), b.error,
                    b.recording == null ? null : b.recording.summary());
        }
    }

    /** stepSkip: functions the step filter skips. */
    public record KernelDto(String id, List<String> programs, BootDto boot, List<String> stepSkip) {
        static KernelDto of(KernelProfile k, BootService boots) {
            return new KernelDto(k.id(),
                    k.programs().stream().map(KernelProfile.Program::name).toList(),
                    BootDto.of(k.id(), boots.boot(k.id())),
                    k.stepSkip().stream().sorted().toList());
        }
    }

    public record RecDto(String id, int seq, String command, String status, String error,
                         Recording.Summary summary) {
        static RecDto of(Session.Rec r) {
            return new RecDto(r.id, r.seq, r.command, r.status, r.error,
                    r.recording == null ? null : r.recording.summary());
        }
    }

    public record SessionDto(String id, String kernel, String state, List<RecDto> recordings) {
        static SessionDto of(Session s) {
            return new SessionDto(s.id(), s.kernel().id(), s.state().name(),
                    s.recordingList().stream().map(RecDto::of).toList());
        }
    }

    public record RecordingDto(String id, String command, String kernel, int steps,
                               Recording.Summary summary, List<String> registers) {
        static RecordingDto of(Recording r) {
            return new RecordingDto(r.id(), r.command(), r.kernel().id(), r.steps(),
                    r.summary(), List.of(r.regNames()));
        }
    }

    /** detail: for a syscall or exception, its one-line form (write(1, "hi", 2) = 2). */
    public record NodeDto(int id, String kind, String label, int start, int end,
                          int children, boolean continued, boolean suspended,
                          Integer vector, int count, List<Integer> members, String detail) {
        NodeDto withDetail(Recording r) {
            if (!kind.equals("trap") || count != 1)
                return this;
            return new NodeDto(id, kind, label, start, end, children, continued, suspended,
                    vector, count, members, r.trapDetail(id));
        }
    }

    public record SyscallDto(int step, int end, boolean returned, Integer resumeStep,
                             String process, String kind, String name, String text,
                             String result, String output, int node) {
        static SyscallDto of(Syscalls.Call c) {
            return new SyscallDto(c.step(), c.end(), c.returned(),
                    c.resumeStep() < 0 ? null : c.resumeStep(), c.process(), c.kind(), c.name(),
                    c.text(), c.result(), c.output(), c.node());
        }
    }

    static String kindName(byte k) {
        return switch (k) {
            case Tree.RUN -> "run";
            case Tree.FUNC -> "call";
            case Tree.INTR -> "trap";
            default -> "?";
        };
    }

    static NodeDto node(Tree t, int i) {
        return node(t, i, null);
    }

    /** With hide: the child count leaves out hidden calls (their children count instead). */
    static NodeDto node(Tree t, int i, Set<String> hide) {
        return new NodeDto(i, kindName(t.kind(i)), t.label(i), t.start(i), t.end(i),
                hide == null ? t.childCount(i) : visible(t, i, hide).length,
                (t.flags(i) & Tree.CONTINUED) != 0,
                (t.flags(i) & Tree.SUSPENDED) != 0,
                t.vector(i) >= 0 ? t.vector(i) : null, 1, null, null);
    }

    private static final int MAX_PERIOD = 4;

    /**
     * Children of a node. A call sequence that repeats back to back
     * (memset in a loop, acquire/release over the process table...)
     * becomes one entry: label "acquire, release", count = repetitions,
     * members = every node it covers. Among the runs (parent -1), a
     * sequence of threads that repeats at least 3 times (the scheduler
     * and a process taking turns while it waits) is folded the same way.
     */
    static List<NodeDto> children(Tree t, int parent, Set<String> hide) {
        int[] kids = hide == null ? t.children(parent) : visible(t, parent, hide);
        boolean runs = parent < 0;
        int minReps = runs ? 3 : 2;
        List<NodeDto> out = new ArrayList<>();
        int i = 0;
        while (i < kids.length) {
            int bestP = 1, bestR = 1;
            for (int p = 1; p <= MAX_PERIOD && i + 2 * p <= kids.length; p++) {
                if (!foldable(t, kids, i, p))
                    break;
                int r = 1;
                while (i + (r + 1) * p <= kids.length && same(t, kids, i, i + r * p, p))
                    r++;
                if (r >= minReps && r * p > bestR * bestP) {
                    bestP = p;
                    bestR = r;
                }
            }
            int j = i + bestP * bestR;
            if (bestR == 1) {
                out.add(node(t, kids[i], hide));
                i++;
                continue;
            }
            List<Integer> members = new ArrayList<>();
            int nkids = 0;
            for (int k = i; k < j; k++) {
                members.add(kids[k]);
                nkids += hide == null ? t.childCount(kids[k]) : visible(t, kids[k], hide).length;
            }
            StringBuilder lab = new StringBuilder();
            for (int k = i; k < i + bestP; k++)
                lab.append(k > i ? ", " : "").append(t.label(kids[k]));
            out.add(new NodeDto(kids[i], runs ? "run" : "call", lab.toString(), t.start(kids[i]),
                    t.end(kids[j - 1]), nkids, false, false, null, bestR, members, null));
            i = j;
        }
        return out;
    }

    /**
     * Children of parent with every call of a hidden function replaced by
     * its own (visible) children: a trap taken inside one stays.
     */
    static int[] visible(Tree t, int parent, Set<String> hide) {
        List<Integer> out = new ArrayList<>();
        addVisible(t, t.children(parent), hide, out);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void addVisible(Tree t, int[] kids, Set<String> hide, List<Integer> out) {
        for (int k : kids)
            if (t.kind(k) == Tree.FUNC && hide.contains(t.label(k)))
                addVisible(t, t.children(k), hide, out);
            else
                out.add(k);
    }

    /** Calls (not continued from an earlier run), or runs. */
    private static boolean foldable(Tree t, int[] kids, int from, int n) {
        for (int k = from; k < from + n; k++)
            if (!foldable(t, kids[k]))
                return false;
        return true;
    }

    private static boolean foldable(Tree t, int node) {
        return t.kind(node) == Tree.RUN
                || t.kind(node) == Tree.FUNC && (t.flags(node) & Tree.CONTINUED) == 0;
    }

    private static boolean same(Tree t, int[] kids, int a, int b, int n) {
        for (int k = 0; k < n; k++)
            if (!foldable(t, kids[b + k]) || t.kind(kids[b + k]) != t.kind(kids[a + k])
                    || !t.label(kids[b + k]).equals(t.label(kids[a + k])))
                return false;
        return true;
    }

    public record LocationDto(String binary, String symbol, String label, String disas,
                              String file, int line) {
        static LocationDto of(KernelProfile.Location l) {
            return new LocationDto(l.binary(), l.symbol(), l.label(),
                    l.disas() == null ? null : l.disas().replace('\t', ' '), l.file(), l.line());
        }
    }

    public record StateDto(int step, String pc, int cpl, LocationDto location,
                           Map<String, String> registers, List<NodeDto> path) {
        static StateDto of(Recording r, int step) {
            Recording.View v = r.view(step);
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < v.regs().length; i++)
                m.put(r.regNames()[i], hex(v.regs()[i]));
            Tree t = r.tree();
            List<NodeDto> path = new ArrayList<>();
            for (int n = t.innermost(step); n >= 0; n = t.parent(n))
                path.addFirst(node(t, n).withDetail(r));
            return new StateDto(step, hex(v.insn().vaddr()), v.cpl(),
                    LocationDto.of(v.location()), m, path);
        }
    }

    public record PageDto(String va, String pa) {
    }

    public record MemoryDto(int step, String space, String addr, int len, String data,
                            List<PageDto> pages) {
    }

    public record LevelDto(String table, int index, String entryAddr, String entry,
                           boolean present, boolean writable, boolean user,
                           boolean largePage, boolean noExecute) {
        static LevelDto of(MemoryImage.Level l) {
            return new LevelDto(l.table(), l.index(), hex(l.entryAddr()), hex(l.entry()),
                    l.present(), l.writable(), l.user(), l.largePage(), l.noExecute());
        }
    }

    /** paging: false while CR0.PG is off (then pa = va and there are no levels). */
    public record WalkDto(int step, boolean paging, String va, String cr3, List<LevelDto> levels,
                          String pa, long pageSize) {
        static WalkDto of(int step, boolean paging, MemoryImage.Walk w) {
            return new WalkDto(step, paging, hex(w.va()), hex(w.cr3()),
                    w.levels().stream().map(LevelDto::of).toList(),
                    w.pa() < 0 ? null : hex(w.pa()), w.pageSize());
        }
    }

    public record StoreDto(int step, String pa, int size, String value, String old) {
    }

    public record SlotDto(String addr, String value, boolean mapped, String label, String target) {
    }

    public record FrameDto(String function, String from, String to, String kind, Integer step) {
    }

    public record StackDto(int step, String kind, String label, String base, String top,
                           String rsp, String rbp, List<SlotDto> slots, List<FrameDto> frames) {
        static StackDto of(StackView.Stack s) {
            return new StackDto(s.step(), s.area().kind(), s.area().label(),
                    hex(s.area().base()), hex(s.area().top()), hex(s.rsp()), hex(s.rbp()),
                    s.slots().stream().map(x -> new SlotDto(hex(x.addr()), hex(x.value()),
                            x.mapped(), x.label(), x.target())).toList(),
                    s.frames().stream().map(f -> new FrameDto(f.function(), hex(f.from()),
                            hex(f.to()), f.kind(), f.step() < 0 ? null : f.step())).toList());
        }
    }

    public record RegionDto(String va, String end, String kind, String label, boolean writable,
                            boolean user, boolean executable, String pa, boolean contiguous,
                            long pageSize, int pages) {
    }

    public record RegionsDto(int step, boolean paging, String cr3, String program, int tables,
                             List<RegionDto> regions) {
        static RegionsDto of(int step, AddressSpace.Layout m) {
            return new RegionsDto(step, m.paging(), hex(m.cr3()), m.program(), m.tables(),
                    m.regions().stream().map(g -> new RegionDto(hex(g.va()), hex(g.end()),
                            g.kind(), g.label(), g.writable(), g.user(), g.executable(),
                            hex(g.pa()), g.contiguous(), g.pageSize(), g.pages())).toList());
        }
    }

    public record PhysMapDto(int step, String cr3, int frameSize, String frames,
                             Map<String, Integer> counts, String freeHead, int freeCount,
                             String freeError) {
        static PhysMapDto of(int step, AddressSpace.PhysMap p) {
            return new PhysMapDto(step, hex(p.cr3()), 4096, p.frames(), p.counts(),
                    hex(p.freeHead()), p.freeCount(), p.freeError());
        }
    }

    public record PhysFrameDto(int step, String pa, String kind, List<String> mappedAt,
                            Integer freeIndex) {
        static PhysFrameDto of(int step, AddressSpace.Frame f) {
            return new PhysFrameDto(step, hex(f.pa()), f.kind(),
                    f.mappedAt().stream().map(Dto::hex).toList(),
                    f.freeIndex() < 0 ? null : f.freeIndex());
        }
    }

    /** found: false if the move had nowhere to go (not in a loop, line not run); step is then from. */
    public record StepDto(int step, boolean found) {
        /** to < 0: there was nowhere to go from `from`. */
        static StepDto of(int from, int to) {
            return to < 0 ? new StepDto(from, false) : new StepDto(to, true);
        }
    }

    public record LineDto(String addr, String mnemonic, String operands, String function,
                          boolean current) {
        static LineDto of(KernelProfile.Line l, boolean current) {
            String text = l.text() == null ? "" : l.text();
            int tab = text.indexOf('\t');
            return new LineDto(hex(l.addr()), tab < 0 ? text : text.substring(0, tab),
                    tab < 0 ? "" : text.substring(tab + 1), l.function(), current);
        }
    }

    public record SourceDto(String kernel, String path, List<String> lines) {
    }
}
