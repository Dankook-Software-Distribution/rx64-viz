package rxviz.elf;

/** 64-bit addresses are unsigned; Java longs are not. */
public final class Addr {

    private Addr() {
    }

    /** base <= a < base + size, unsigned. */
    public static boolean in(long a, long base, long size) {
        return Long.compareUnsigned(a - base, size) < 0 && Long.compareUnsigned(a, base) >= 0;
    }
}
