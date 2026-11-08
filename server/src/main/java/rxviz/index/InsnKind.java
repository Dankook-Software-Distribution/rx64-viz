package rxviz.index;

/** What an x86-64 instruction does to control flow, from its bytes. */
public enum InsnKind {
    OTHER, CALL, RET, IRET, SYSRET, JMP_INDIRECT, INT;

    /** The index of the first byte after the legacy prefixes (operand/address size, lock, rep, segments). */
    static int prefixEnd(byte[] b) {
        int i = 0;
        while (i < b.length) {
            int p = b[i] & 0xff;
            if (p == 0x66 || p == 0x67 || p == 0xf0 || p == 0xf2 || p == 0xf3
                    || p == 0x2e || p == 0x36 || p == 0x3e || p == 0x26
                    || p == 0x64 || p == 0x65)
                i++;
            else
                break;
        }
        return i;
    }

    public static InsnKind of(byte[] b) {
        boolean rexW = false;
        /* legacy prefixes, then an optional REX prefix */
        int i = prefixEnd(b);
        if (i < b.length && (b[i] & 0xf0) == 0x40) {
            rexW = (b[i] & 0x08) != 0;
            i++;
        }
        if (i >= b.length)
            return OTHER;
        int op = b[i] & 0xff;
        switch (op) {
            case 0xe8:
                return CALL;
            case 0xc3: case 0xc2:
                return RET;
            /* lret (0xca/0xcb) is not a return: kernels use push cs;
             * push addr; lretq to reload CS inside one function */
            case 0xcf:
                return IRET;
            case 0xcd: case 0xcc:
                return INT;
            case 0x0f:
                if (i + 1 < b.length && (b[i + 1] & 0xff) == 0x07)
                    return SYSRET;
                return OTHER;
            case 0xff:
                if (i + 1 < b.length) {
                    /* frames are near call / near ret (far ones are not used
                     * as calls: see lret above) */
                    int reg = (b[i + 1] >> 3) & 7;
                    if (reg == 2)
                        return CALL;
                    if (reg == 4 || reg == 5)
                        return JMP_INDIRECT;
                }
                return OTHER;
            default:
                return OTHER;
        }
    }
}
