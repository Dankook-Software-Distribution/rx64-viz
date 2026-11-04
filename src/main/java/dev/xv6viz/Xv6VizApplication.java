package dev.xv6viz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;

import dev.xv6viz.vm.VmManager;

@SpringBootApplication
@EnableConfigurationProperties(Xv6Props.class)
public class Xv6VizApplication {

    private final VmManager vms;

    public Xv6VizApplication(VmManager vms) {
        this.vms = vms;
    }

    public static void main(String[] args) {
        SpringApplication.run(Xv6VizApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void boot() {
        vms.startAll();
    }
}
