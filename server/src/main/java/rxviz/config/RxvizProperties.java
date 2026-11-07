package rxviz.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings from application.yml (prefix "rxviz"). */
@ConfigurationProperties("rxviz")
public record RxvizProperties(
        String plugin,
        String dataDir,
        int icountShift,
        List<Kernel> kernels,
        List<String> syscalls,
        long physMemBytes) {

    /** One kernel tree under /work/kernels and the facts the indexer needs about it. */
    public record Kernel(
            String id,
            String dir,
            String kstackBase,
            String kstackStride,
            int syscallVector,
            Map<Integer, String> vectors,
            List<Alias> aliases,
            List<String> userTrapStackPages,
            String bootEntry) {
    }

    /** Code linked at symbol but executed at [va, va + size), mapped at run time. */
    public record Alias(String va, String symbol, long size) {
    }

    /** Parses "0x..." or "-0x..." as a 64-bit value. */
    public static long hex(String s) {
        boolean neg = s.startsWith("-");
        String t = neg ? s.substring(1) : s;
        if (t.startsWith("0x") || t.startsWith("0X"))
            t = t.substring(2);
        long v = Long.parseUnsignedLong(t, 16);
        return neg ? -v : v;
    }
}
