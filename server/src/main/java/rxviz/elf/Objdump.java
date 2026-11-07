package rxviz.elf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Runs objdump on a file and hands each output line to a consumer. */
final class Objdump {

    private Objdump() {
    }

    static void run(Path file, Consumer<String> each, String... args) throws IOException {
        List<String> argv = new ArrayList<>(List.of("objdump"));
        argv.addAll(List.of(args));
        argv.add(file.toString());
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null)
                each.accept(line);
        }
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
