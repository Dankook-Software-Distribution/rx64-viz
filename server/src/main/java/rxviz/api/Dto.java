package rxviz.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import rxviz.config.KernelProfile;
import rxviz.index.MemoryImage;
import rxviz.index.Recording;
import rxviz.index.Tree;
import rxviz.session.BootService;
import rxviz.session.Session;

/** JSON shapes. 64-bit values travel as "0x..." strings (JS numbers lose bits). */
public final class Dto {

    private Dto() {
    }

    public static String hex(long v) {
        return "0x" + Long.toHexString(v);
    }

    public record BootDto(String id, String status, String error, Recording.Summary summary) {
        static BootDto of(String kernelId, BootService.Boot b) {
            return new BootDto(BootService.recordingId(kernelId), b.status.name(), b.error,
                    b.recording == null ? null : b.recording.summary());
        }
    }

    public record KernelDto(String id, List<String> programs, BootDto boot) {
        static KernelDto of(KernelProfile k, BootService boots) {
            return new KernelDto(k.id(),
                    k.programs().stream().map(KernelProfile.Program::name).toList(),
                    BootDto.of(k.id(), boots.boot(k.id())));
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

    public record NodeDto(int id, String kind, String label, int start, int end,
                          int children, boolean continued, boolean suspended,
                          Integer vector, int count, List<Integer> members) {
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
        return new NodeDto(i, kindName(t.kind(i)), t.label(i), t.start(i), t.end(i),
                t.childCount(i), (t.flags(i) & Tree.CONTINUED) != 0,
                (t.flags(i) & Tree.SUSPENDED) != 0,
                t.vector(i) >= 0 ? t.vector(i) : null, 1, null);
    }

    private static final int MAX_PERIOD = 4;

    /**
     * Children of a node. A call sequence that repeats back to back
     * (memset in a loop, acquire/release over the process table...)
     * becomes one entry: label "acquire, release", count = repetitions,
     * members = every node it covers.
     */
    static List<NodeDto> children(Tree t, int parent) {
        int[] kids = t.children(parent);
        List<NodeDto> out = new ArrayList<>();
        int i = 0;
        while (i < kids.length) {
            int bestP = 1, bestR = 1;
            for (int p = 1; p <= MAX_PERIOD && i + 2 * p <= kids.length; p++) {
                if (!allCalls(t, kids, i, p))
                    break;
                int r = 1;
                while (i + (r + 1) * p <= kids.length && same(t, kids, i, i + r * p, p))
                    r++;
                if (r >= 2 && r * p > bestR * bestP) {
                    bestP = p;
                    bestR = r;
                }
            }
            int j = i + bestP * bestR;
            if (bestR == 1) {
                out.add(node(t, kids[i]));
                i++;
                continue;
            }
            List<Integer> members = new ArrayList<>();
            int nkids = 0;
            for (int k = i; k < j; k++) {
                members.add(kids[k]);
                nkids += t.childCount(kids[k]);
            }
            StringBuilder lab = new StringBuilder();
            for (int k = i; k < i + bestP; k++)
                lab.append(k > i ? ", " : "").append(t.label(kids[k]));
            out.add(new NodeDto(kids[i], "call", lab.toString(), t.start(kids[i]),
                    t.end(kids[j - 1]), nkids, false, false, null, bestR, members));
            i = j;
        }
        return out;
    }

    private static boolean allCalls(Tree t, int[] kids, int from, int n) {
        for (int k = from; k < from + n; k++)
            if (t.kind(kids[k]) != Tree.FUNC || (t.flags(kids[k]) & Tree.CONTINUED) != 0)
                return false;
        return true;
    }

    private static boolean same(Tree t, int[] kids, int a, int b, int n) {
        for (int k = 0; k < n; k++)
            if (t.kind(kids[b + k]) != Tree.FUNC
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
                path.addFirst(node(t, n));
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

    public record WalkDto(int step, String va, String cr3, List<LevelDto> levels, String pa,
                          long pageSize) {
        static WalkDto of(int step, MemoryImage.Walk w) {
            return new WalkDto(step, hex(w.va()), hex(w.cr3()),
                    w.levels().stream().map(LevelDto::of).toList(),
                    w.pa() < 0 ? null : hex(w.pa()), w.pageSize());
        }
    }

    public record StoreDto(int step, String pa, int size, String value, String old) {
    }

    public record StepDto(int step) {
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
