package rxviz.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The kernels this server can run, loaded once at startup. */
@Component
public class KernelRegistry {

    private static final Logger log = LoggerFactory.getLogger(KernelRegistry.class);

    private final Map<String, KernelProfile> kernels = new LinkedHashMap<>();

    public KernelRegistry(RxvizProperties props) {
        for (RxvizProperties.Kernel k : props.kernels()) {
            try {
                KernelProfile p = new KernelProfile(k, props.syscalls(),
                        props.stepSkip() == null ? List.of() : props.stepSkip());
                kernels.put(p.id(), p);
                log.info("kernel {}: {} user programs", p.id(), p.programs().size());
                /* the kernel's DWARF variables (two objdump runs): read now, off the request path */
                Thread.ofVirtual().start(() -> p.locals(p.kernelProgram()));
            } catch (IOException e) {
                throw new UncheckedIOException("loading kernel " + k.id(), e);
            }
        }
    }

    public Map<String, KernelProfile> all() {
        return kernels;
    }

    public KernelProfile get(String id) {
        KernelProfile p = kernels.get(id);
        if (p == null)
            throw new IllegalArgumentException("unknown kernel " + id);
        return p;
    }
}
