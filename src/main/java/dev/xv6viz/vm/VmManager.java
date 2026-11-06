package dev.xv6viz.vm;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;

import dev.xv6viz.Xv6Props;

/** 설정에 있는 커널마다 Vm 하나. */
@Service
public class VmManager {

    private final Map<String, Vm> vms = new LinkedHashMap<>();

    public VmManager(Xv6Props props) {
        props.kernels().forEach((id, dir) -> vms.put(id, new Vm(id, Xv6Props.path(dir), props.cpus())));
    }

    public Vm get(String id) {
        Vm vm = vms.get(id);
        if (vm == null) throw new IllegalArgumentException("no such kernel: " + id);
        return vm;
    }

    public Collection<Vm> all() {
        return vms.values();
    }

    public void startAll() {
        vms.values().forEach(Vm::start);
    }

    @PreDestroy
    public void shutdown() {
        vms.values().forEach(Vm::shutdown);
    }
}
