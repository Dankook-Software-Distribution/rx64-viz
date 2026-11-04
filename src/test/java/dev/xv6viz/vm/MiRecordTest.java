package dev.xv6viz.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MiRecordTest {

    @Test
    void resultWithJson() {
        MiRecord r = MiRecord.parse("12^done,json=\"{\\\"hit\\\": null, \\\"a\\\": \\\"x\\\\ny\\\"}\"");
        assertEquals(12, r.token());
        assertEquals("done", r.cls());
        assertEquals("{\"hit\": null, \"a\": \"x\\ny\"}", r.str("json"));
    }

    @Test
    void stoppedWithTupleAndList() {
        MiRecord r = MiRecord.parse("*stopped,reason=\"breakpoint-hit\",frame={addr=\"0x1\",args=[{name=\"tf\",value=\"0x2\"}]},thread-id=\"1\",stopped-threads=\"all\"");
        assertEquals('*', r.kind());
        assertEquals("breakpoint-hit", r.str("reason"));
        Map<?, ?> frame = (Map<?, ?>) r.results().get("frame");
        assertEquals("0x1", frame.get("addr"));
        assertEquals("tf", ((Map<?, ?>) ((List<?>) frame.get("args")).get(0)).get("name"));
    }

    @Test
    void octalUtf8AndPrompt() {
        MiRecord r = MiRecord.parse("~\"\\352\\260\\200\\n\"");
        assertEquals("가\n", r.stream());
        assertNull(MiRecord.parse("(gdb) "));
    }
}
