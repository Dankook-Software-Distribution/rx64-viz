package rxviz.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;

import rxviz.config.KernelRegistry;
import rxviz.config.RxvizProperties;
import rxviz.index.Recording;

/** Running guests. One student, one machine: a handful at most. */
@Service
public class SessionService {

    private final KernelRegistry kernels;
    private final RxvizProperties props;
    private final BootService boots;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger next = new AtomicInteger(1);
    private volatile Session current;
    /* indexing is CPU and memory heavy: one at a time */
    private final ExecutorService indexer = Executors.newSingleThreadExecutor();

    public SessionService(KernelRegistry kernels, RxvizProperties props, BootService boots) {
        this.kernels = kernels;
        this.props = props;
        this.boots = boots;
    }

    public Session create(String kernelId) throws Exception {
        String id = "s" + next.getAndIncrement();
        Session s = new Session(id, kernels.get(kernelId), props, indexer);
        sessions.put(id, s);
        current = s;
        s.start();
        return s;
    }

    /**
     * The one shell the UI uses: stop every running guest and boot kernelId.
     * Their recordings go with them.
     */
    public synchronized Session replace(String kernelId) throws Exception {
        kernels.get(kernelId); /* unknown kernel: fail before stopping anything */
        for (String id : java.util.List.copyOf(sessions.keySet()))
            delete(id);
        return create(kernelId);
    }

    /** The newest running guest, or null. */
    public Session current() {
        return current;
    }

    public Session get(String id) {
        Session s = sessions.get(id);
        if (s == null)
            throw new NotFoundException("no session " + id);
        return s;
    }

    public void delete(String id) {
        Session s = sessions.remove(id);
        if (s != null)
            s.stop();
        if (current == s)
            current = null;
    }

    /** Recording ids are "<session>-<seq>", or "boot-<kernel>". */
    public Recording recording(String recId) {
        if (recId.startsWith("boot-")) {
            BootService.Boot b = boots.boot(recId.substring(5));
            if (b.recording == null)
                throw new NotFoundException("boot recording " + recId + " is " + b.status);
            return b.recording;
        }
        int dash = recId.lastIndexOf('-');
        if (dash < 0)
            throw new NotFoundException("no recording " + recId);
        Session.Rec r = get(recId.substring(0, dash)).recording(recId);
        if (r == null)
            throw new NotFoundException("no recording " + recId);
        if (r.recording == null)
            throw new NotFoundException("recording " + recId + " is " + r.status);
        return r.recording;
    }

    @PreDestroy
    void shutdown() {
        for (Session s : sessions.values())
            s.stop();
        indexer.shutdownNow();
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String msg) {
            super(msg);
        }
    }
}
