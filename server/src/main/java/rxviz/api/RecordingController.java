package rxviz.api;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import rxviz.config.RxvizProperties;
import rxviz.index.MemoryImage;
import rxviz.index.Recording;
import rxviz.index.Stepper;
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
                                  @RequestParam(defaultValue = "-1") int node) {
        return Dto.children(sessions.recording(rid).tree(), node);
    }

    /** Nodes by id (members of a grouped entry). */
    @GetMapping("/nodes")
    public List<Dto.NodeDto> nodes(@PathVariable String rid, @RequestParam List<Integer> ids) {
        Tree t = sessions.recording(rid).tree();
        if (ids.size() > 1000)
            throw new IllegalArgumentException("at most 1000 ids");
        List<Dto.NodeDto> out = new ArrayList<>();
        for (int id : ids) {
            if (id < 0 || id >= t.size())
                throw new IllegalArgumentException("no node " + id);
            out.add(Dto.node(t, id));
        }
        return out;
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
                            @RequestParam String op) {
        Recording r = sessions.recording(rid);
        Tree t = r.tree();
        Stepper st = new Stepper(r);
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
        return new Dto.StepDto(to);
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
        long cr3 = virt ? r.cr3(step) : 0;
        return r.memory(step, img -> {
            byte[] buf = new byte[len];
            List<Dto.PageDto> pages = new ArrayList<>();
            int off = 0;
            while (off < len) {
                long va = a + off;
                int n = (int) Math.min(len - off, 4096 - (va & 0xfff));
                long pa = virt ? img.translate(cr3, va) : va;
                if (virt && (off == 0 || (va & 0xfff) == 0))
                    pages.add(new Dto.PageDto(Dto.hex(va & ~0xfffL),
                            pa < 0 ? null : Dto.hex(pa & ~0xfffL)));
                if (pa >= 0 && img.contains(pa, n))
                    img.copy(pa, buf, off, n);
                off += n;
            }
            return new Dto.MemoryDto(step, virt ? "virt" : "phys", Dto.hex(a), len,
                    HexFormat.of().formatHex(buf), pages);
        });
    }

    @GetMapping("/pagewalk")
    public Dto.WalkDto pagewalk(@PathVariable String rid, @RequestParam int step,
                                @RequestParam String va) {
        Recording r = sessions.recording(rid);
        long cr3 = r.cr3(step);
        long v = RxvizProperties.hex(va);
        MemoryImage.Walk w = r.memory(step, img -> img.walk(cr3, v));
        return Dto.WalkDto.of(step, w);
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
