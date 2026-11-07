package rxviz.api;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import rxviz.config.RxvizProperties;
import rxviz.index.MemoryImage;
import rxviz.index.Recording;
import rxviz.index.Stepper;
import rxviz.index.Timeline;
import rxviz.index.Tree;
import rxviz.session.SessionService;

@RestController
@RequestMapping("/api/recordings/{rid}")
public class RecordingController {

    private static final int MAX_MEMORY = 1 << 16;
    private static final int MAX_STORES = 10_000;

    private final SessionService sessions;

    public RecordingController(SessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    public Dto.RecordingDto get(@PathVariable String rid) {
        return Dto.RecordingDto.of(sessions.recording(rid));
    }

    /** Children of node (or the runs, for node = -1). */
    @GetMapping("/tree")
    public List<Dto.NodeDto> tree(@PathVariable String rid,
                                  @RequestParam(defaultValue = "-1") int node,
                                  @RequestParam(defaultValue = "false") boolean skip) {
        Recording r = sessions.recording(rid);
        return Dto.children(r.tree(), node, skip ? r.kernel().stepSkip() : null).stream()
                .map(n -> n.withDetail(r)).toList();
    }

    /** Nodes by id (members of a grouped entry). */
    @GetMapping("/nodes")
    public List<Dto.NodeDto> nodes(@PathVariable String rid, @RequestParam List<Integer> ids,
                                   @RequestParam(defaultValue = "false") boolean skip) {
        Recording r = sessions.recording(rid);
        Tree t = r.tree();
        Set<String> hide = skip ? r.kernel().stepSkip() : null;
        if (ids.size() > 1000)
            throw new IllegalArgumentException("at most 1000 ids");
        List<Dto.NodeDto> out = new ArrayList<>();
        for (int id : ids) {
            if (id < 0 || id >= t.size())
                throw new IllegalArgumentException("no node " + id);
            out.add(Dto.node(t, id, hide).withDetail(r));
        }
        return out;
    }

    /** System calls and exceptions in the order they started, strace-style. */
    @GetMapping("/syscalls")
    public List<Dto.SyscallDto> syscalls(@PathVariable String rid) {
        return sessions.recording(rid).syscalls().list().stream().map(Dto.SyscallDto::of).toList();
    }

    /** Static disassembly around the instruction of a step. */
    @GetMapping("/disasm")
    public List<Dto.LineDto> disasm(@PathVariable String rid, @RequestParam int step,
                                    @RequestParam(defaultValue = "12") int before,
                                    @RequestParam(defaultValue = "24") int after) {
        Recording r = sessions.recording(rid);
        long pc = r.insn(step).vaddr();
        return r.listing(step, Math.min(before, 200), Math.min(after, 400)).stream()
                .map(l -> Dto.LineDto.of(l, l.addr() == pc))
                .toList();
    }

    /**
     * The instructions a source line compiled to, in the function running
     * at step: that step's line, or `line` of the same file.
     */
    @GetMapping("/code")
    public List<Dto.LineDto> code(@PathVariable String rid, @RequestParam int step,
                                  @RequestParam(defaultValue = "0") int line) {
        Recording r = sessions.recording(rid);
        long pc = r.insn(step).vaddr();
        return r.lineInstructions(step, line).stream()
                .map(l -> Dto.LineDto.of(l, l.addr() == pc))
                .toList();
    }

    @GetMapping("/state")
    public Dto.StateDto state(@PathVariable String rid, @RequestParam int step) {
        return Dto.StateDto.of(sessions.recording(rid), step);
    }

    /**
     * Where a step command leads from step `from`: next, prev (one
     * instruction), over, out, line-into, line-over, line-back (see
     * Stepper), next-trap, prev-trap.
     */
    @GetMapping("/step")
    public Dto.StepDto step(@PathVariable String rid, @RequestParam int from,
                            @RequestParam String op,
                            @RequestParam(defaultValue = "false") boolean skip) {
        Recording r = sessions.recording(rid);
        Tree t = r.tree();
        Stepper st = new Stepper(r, skip);
        int last = r.steps() - 1;
        int s = Math.max(0, Math.min(from, last));
        int to = switch (op) {
            case "next" -> Math.min(s + 1, last);
            case "prev" -> Math.max(s - 1, 0);
            case "over" -> st.over(s);
            case "out" -> st.out(s);
            case "line-into" -> st.lineInto(s);
            case "line-over" -> st.lineOver(s);
            case "line-back" -> st.lineBack(s);
            case "loop-out" -> st.loopOut(s);
            case "loop-back" -> st.loopBack(s);
            case "next-trap" -> {
                int n = t.nextIntr(s);
                yield n < 0 ? s : t.start(n);
            }
            case "prev-trap" -> {
                int n = t.prevIntr(s);
                yield n < 0 ? s : t.start(n);
            }
            default -> throw new IllegalArgumentException("unknown op " + op);
        };
        return Dto.StepDto.of(s, to);
    }

    /** The next (back: previous) time the source line file:line starts to run, in any thread. */
    @GetMapping("/run-to")
    public Dto.StepDto runTo(@PathVariable String rid, @RequestParam int from,
                             @RequestParam String file, @RequestParam int line,
                             @RequestParam(defaultValue = "false") boolean back) {
        Recording r = sessions.recording(rid);
        int s = Math.max(0, Math.min(from, r.steps() - 1));
        /* a line with no code: its next line with code, like a breakpoint */
        int l = r.kernel().codeLineAtOrAfter(file, line);
        int to = l < 0 ? -1 : new Stepper(r).runTo(s, file, l, back);
        return Dto.StepDto.of(s, to);
    }

    /** Memory at step; space "virt" translates through CR3 at that step. */
    @GetMapping("/memory")
    public Dto.MemoryDto memory(@PathVariable String rid, @RequestParam int step,
                                @RequestParam String addr, @RequestParam int len,
                                @RequestParam(defaultValue = "virt") String space) {
        Recording r = sessions.recording(rid);
        if (len <= 0 || len > MAX_MEMORY)
            throw new IllegalArgumentException("len must be in (0, " + MAX_MEMORY + "]");
        long a = RxvizProperties.hex(addr);
        boolean virt = !space.equals("phys");
        Function<MemoryImage.Virtual, Dto.MemoryDto> read = vm -> {
            byte[] buf = new byte[len];
            List<Dto.PageDto> pages = new ArrayList<>();
            int off = 0;
            while (off < len) {
                long va = a + off;
                int n = (int) Math.min(len - off, 4096 - (va & 0xfff));
                long pa = vm.pa(va);
                if (virt && (off == 0 || (va & 0xfff) == 0))
                    pages.add(new Dto.PageDto(Dto.hex(va & ~0xfffL),
                            pa < 0 ? null : Dto.hex(pa & ~0xfffL)));
                if (pa >= 0 && vm.image().contains(pa, n))
                    vm.image().copy(pa, buf, off, n);
                off += n;
            }
            return new Dto.MemoryDto(step, virt ? "virt" : "phys", Dto.hex(a), len,
                    HexFormat.of().formatHex(buf), pages);
        };
        return virt ? r.virtual(step, read) : r.physical(step, read);
    }

    /** The stack in use at step: its 8-byte slots from rsp to the top, and the frames on it. */
    @GetMapping("/stack")
    public Dto.StackDto stack(@PathVariable String rid, @RequestParam int step) {
        return Dto.StackDto.of(sessions.recording(rid).stack(step));
    }

    /** The address space of CR3 at step: mapped virtual ranges, named. */
    @GetMapping("/regions")
    public Dto.RegionsDto regions(@PathVariable String rid, @RequestParam int step) {
        return Dto.RegionsDto.of(step, sessions.recording(rid).addressSpace(step));
    }

    /** How each 4 KB physical frame is used at step (one character per frame). */
    @GetMapping("/physmap")
    public Dto.PhysMapDto physmap(@PathVariable String rid, @RequestParam int step) {
        return Dto.PhysMapDto.of(step, sessions.recording(rid).physMap(step));
    }

    /** One physical frame at step: its kind, where CR3 maps it, its place on the free list. */
    @GetMapping("/frame")
    public Dto.PhysFrameDto frame(@PathVariable String rid, @RequestParam int step,
                               @RequestParam String pa) {
        return Dto.PhysFrameDto.of(step, sessions.recording(rid).frame(step, RxvizProperties.hex(pa)));
    }

    /** [from, to) in buckets: per thread how much it ran (and in user mode), and the traps. */
    @GetMapping("/timeline")
    public Timeline.View timeline(@PathVariable String rid, @RequestParam int from,
                                  @RequestParam int to, @RequestParam int buckets) {
        return sessions.recording(rid).timeline(from, to, buckets);
    }

    @GetMapping("/pagewalk")
    public Dto.WalkDto pagewalk(@PathVariable String rid, @RequestParam int step,
                                @RequestParam String va) {
        Recording r = sessions.recording(rid);
        long cr3 = r.cr3(step);
        long v = RxvizProperties.hex(va);
        if (!r.paging(step)) /* addresses are physical */
            return Dto.WalkDto.of(step, false, new MemoryImage.Walk(v, cr3, List.of(), v, 4096));
        MemoryImage.Walk w = r.memory(step, img -> img.walk(cr3, v));
        return Dto.WalkDto.of(step, true, w);
    }

    /** Stores in [from, to): the first ones, or with last=N the N newest, newest first. */
    @GetMapping("/stores")
    public List<Dto.StoreDto> stores(@PathVariable String rid, @RequestParam int from,
                                     @RequestParam int to,
                                     @RequestParam(required = false) Integer last) {
        Recording r = sessions.recording(rid);
        int limit = last == null ? MAX_STORES : Math.min(last, MAX_STORES);
        return r.stores(from, to, limit, last != null).stream()
                .map(w -> new Dto.StoreDto(w.step(), Dto.hex(w.paddr()), w.size(),
                        Dto.hex(w.value()), Dto.hex(w.old())))
                .toList();
    }
}
