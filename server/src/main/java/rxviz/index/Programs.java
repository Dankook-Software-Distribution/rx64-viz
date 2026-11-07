package rxviz.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import rxviz.config.KernelProfile;
import rxviz.config.KernelProfile.Program;

/**
 * Which user program runs in which address space. All programs are
 * linked at the same addresses, so a program is recognized by comparing
 * the bytes of the instructions it executes with each program's file.
 * An address space (CR3) can change program at exec.
 */
public final class Programs {

    private static final class Space {
        List<Program> candidates = List.of();
        Program current;
        final TreeMap<Integer, Program> history = new TreeMap<>();
    }

    private final KernelProfile kernel;
    private final Map<Long, Space> spaces = new HashMap<>();
    /* the address space of the last call: user steps come in long runs */
    private long lastCr3 = -1;
    private Space last;

    public Programs(KernelProfile kernel) {
        this.kernel = kernel;
    }

    /** A user-mode step; returns the program it belongs to, or null. */
    Program observe(int step, long cr3, long pc, byte[] bytes) {
        Space s = cr3 == lastCr3 ? last : spaces.computeIfAbsent(cr3, k -> new Space());
        lastCr3 = cr3;
        last = s;
        if (s.current != null && s.current.elf().matches(pc, bytes))
            return s.current;
        List<Program> next = filter(s.candidates, pc, bytes);
        if (next.isEmpty())
            next = filter(kernel.programs(), pc, bytes);
        s.candidates = next;
        Program cur = next.size() == 1 ? next.getFirst() : null;
        if (cur != s.current) {
            s.current = cur;
            s.history.put(step, cur);
        }
        return cur;
    }

    private static List<Program> filter(List<Program> from, long pc, byte[] bytes) {
        List<Program> out = new ArrayList<>();
        for (Program p : from)
            if (p.elf().matches(pc, bytes))
                out.add(p);
        return out;
    }

    /** The program known to run in address space cr3 at step. */
    public Program at(long cr3, int step) {
        Space s = spaces.get(cr3);
        if (s == null)
            return null;
        Map.Entry<Integer, Program> e = s.history.floorEntry(step);
        if (e == null)
            e = s.history.ceilingEntry(step); /* identified a few steps later */
        return e == null ? null : e.getValue();
    }
}
