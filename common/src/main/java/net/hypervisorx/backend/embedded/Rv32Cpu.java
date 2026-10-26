package net.hypervisorx.backend.embedded;

/**
 * RV32IMA + Zicsr machine-mode core, enough to boot a no-MMU Linux kernel.
 * <p>
 * Java port of the step function from Charles Lohr's mini-rv32ima
 * (https://github.com/cnlohr/mini-rv32ima, BSD/MIT/CC0). Java has no unsigned 32-bit int, so all
 * the unsigned math goes through {@link Integer#compareUnsigned}, {@link Integer#divideUnsigned}
 * and friends. RAM is a little-endian byte array mapped at {@link #RAM_BASE}.
 */
public final class Rv32Cpu {
    public static final int RAM_BASE = 0x80000000;

    /** What {@link #step} returns. */
    public static final int STEP_OK = 0;
    public static final int STEP_WFI = 1;
    public static final int STEP_FAULT = 3;
    public static final int STEP_RESTART = 0x7777;
    public static final int STEP_POWEROFF = 0x5555;

    /** MMIO device bus, covers 0x10000000..0x12000000. */
    public interface Bus {
        /** @return non-zero to bail out of the current step with that code (syscon reboot/poweroff). */
        int store(int address, int value);

        int load(int address);
    }

    public final int[] regs = new int[32];
    public int pc;
    public int mstatus;
    public int cyclel;
    public int cycleh;
    public int timerl;
    public int timerh;
    public int timermatchl;
    public int timermatchh;
    public int mscratch;
    public int mtvec;
    public int mie;
    public int mip;
    public int mepc;
    public int mtval;
    public int mcause;
    /** Bits 0..1 privilege, bit 2 WFI, bits 3+ load-reserved address. */
    public int extraflags;

    private final byte[] ram;
    private final int ramSize;
    private final Bus bus;

    public Rv32Cpu(byte[] ram, Bus bus) {
        this.ram = ram;
        this.ramSize = ram.length;
        this.bus = bus;
    }

    public void reset(int dtbAddress) {
        java.util.Arrays.fill(regs, 0);
        pc = RAM_BASE;
        mstatus = cyclel = cycleh = timerl = timerh = timermatchl = timermatchh = 0;
        mscratch = mtvec = mie = mip = mepc = mtval = mcause = 0;
        extraflags = 3; // machine mode
        regs[10] = 0; // hart id
        regs[11] = dtbAddress;
    }

    // ---- little-endian RAM access, offsets are relative to RAM_BASE ----

    private int load4(int ofs) {
        return (ram[ofs] & 0xff) | (ram[ofs + 1] & 0xff) << 8 | (ram[ofs + 2] & 0xff) << 16 | (ram[ofs + 3] & 0xff) << 24;
    }

    private int load2(int ofs) {
        return (ram[ofs] & 0xff) | (ram[ofs + 1] & 0xff) << 8;
    }

    private void store4(int ofs, int v) {
        ram[ofs] = (byte) v;
        ram[ofs + 1] = (byte) (v >>> 8);
        ram[ofs + 2] = (byte) (v >>> 16);
        ram[ofs + 3] = (byte) (v >>> 24);
    }

    private void store2(int ofs, int v) {
        ram[ofs] = (byte) v;
        ram[ofs + 1] = (byte) (v >>> 8);
    }

    private static boolean isMmio(int addr) {
        return Integer.compareUnsigned(addr, 0x10000000) >= 0 && Integer.compareUnsigned(addr, 0x12000000) < 0;
    }

    private static boolean ltu(int a, int b) {
        return Integer.compareUnsigned(a, b) < 0;
    }

    private static boolean geu(int a, int b) {
        return Integer.compareUnsigned(a, b) >= 0;
    }

    /**
     * Bumps the timer by {@code elapsedUs}, then runs up to {@code count} instructions.
     *
     * @return one of the STEP_* constants
     */
    public int step(int elapsedUs, int count) {
        int newTimer = timerl + elapsedUs;
        if (ltu(newTimer, timerl)) timerh++;
        timerl = newTimer;

        boolean timerFired = (Integer.compareUnsigned(timerh, timermatchh) > 0
                || (timerh == timermatchh && Integer.compareUnsigned(timerl, timermatchl) > 0))
                && (timermatchh != 0 || timermatchl != 0);
        if (timerFired) {
            extraflags &= ~4;
            mip |= 1 << 7;
        } else {
            mip &= ~(1 << 7);
        }

        if ((extraflags & 4) != 0) return STEP_WFI;

        int trap = 0;
        int rval = 0;
        int pc = this.pc;
        int cycle = this.cyclel;

        if ((mip & (1 << 7)) != 0 && (mie & (1 << 7)) != 0 && (mstatus & 0x8) != 0) {
            trap = 0x80000007;
            pc -= 4;
        } else {
            for (int icount = 0; icount < count; icount++) {
                int ir;
                rval = 0;
                cycle++;
                int ofsPc = pc - RAM_BASE;

                if (geu(ofsPc, ramSize)) {
                    trap = 1 + 1;
                    break;
                } else if ((ofsPc & 3) != 0) {
                    trap = 1 + 0;
                    break;
                }

                ir = load4(ofsPc);
                int rdid = (ir >>> 7) & 0x1f;

                switch (ir & 0x7f) {
                    case 0x37 -> rval = ir & 0xfffff000; // LUI
                    case 0x17 -> rval = pc + (ir & 0xfffff000); // AUIPC
                    case 0x6F -> { // JAL
                        int reladdy = ((ir & 0x80000000) >>> 11) | ((ir & 0x7fe00000) >>> 20) | ((ir & 0x00100000) >>> 9) | (ir & 0x000ff000);
                        if ((reladdy & 0x00100000) != 0) reladdy |= 0xffe00000;
                        rval = pc + 4;
                        pc = pc + reladdy - 4;
                    }
                    case 0x67 -> { // JALR
                        int imm = ir >>> 20;
                        int immSe = imm | (((imm & 0x800) != 0) ? 0xfffff000 : 0);
                        rval = pc + 4;
                        pc = ((regs[(ir >>> 15) & 0x1f] + immSe) & ~1) - 4;
                    }
                    case 0x63 -> { // BRANCH
                        int immm4 = ((ir & 0xf00) >>> 7) | ((ir & 0x7e000000) >>> 20) | ((ir & 0x80) << 4) | ((ir >>> 31) << 12);
                        if ((immm4 & 0x1000) != 0) immm4 |= 0xffffe000;
                        int rs1 = regs[(ir >>> 15) & 0x1f];
                        int rs2 = regs[(ir >>> 20) & 0x1f];
                        immm4 = pc + immm4 - 4;
                        rdid = 0;
                        switch ((ir >>> 12) & 0x7) {
                            case 0 -> { if (rs1 == rs2) pc = immm4; }
                            case 1 -> { if (rs1 != rs2) pc = immm4; }
                            case 4 -> { if (rs1 < rs2) pc = immm4; }
                            case 5 -> { if (rs1 >= rs2) pc = immm4; }
                            case 6 -> { if (ltu(rs1, rs2)) pc = immm4; }
                            case 7 -> { if (geu(rs1, rs2)) pc = immm4; }
                            default -> trap = 2 + 1;
                        }
                    }
                    case 0x03 -> { // LOAD
                        int rs1 = regs[(ir >>> 15) & 0x1f];
                        int imm = ir >>> 20;
                        int immSe = imm | (((imm & 0x800) != 0) ? 0xfffff000 : 0);
                        int rsval = rs1 + immSe;
                        rsval -= RAM_BASE;
                        if (geu(rsval, ramSize - 3)) {
                            rsval += RAM_BASE;
                            if (isMmio(rsval)) {
                                rval = bus.load(rsval);
                            } else {
                                trap = 5 + 1;
                                rval = rsval;
                            }
                        } else {
                            switch ((ir >>> 12) & 0x7) {
                                case 0 -> rval = ram[rsval];
                                case 1 -> rval = (short) load2(rsval);
                                case 2 -> rval = load4(rsval);
                                case 4 -> rval = ram[rsval] & 0xff;
                                case 5 -> rval = load2(rsval);
                                default -> trap = 2 + 1;
                            }
                        }
                    }
                    case 0x23 -> { // STORE
                        int rs1 = regs[(ir >>> 15) & 0x1f];
                        int rs2 = regs[(ir >>> 20) & 0x1f];
                        int addy = ((ir >>> 7) & 0x1f) | ((ir & 0xfe000000) >>> 20);
                        if ((addy & 0x800) != 0) addy |= 0xfffff000;
                        addy += rs1 - RAM_BASE;
                        rdid = 0;
                        if (geu(addy, ramSize - 3)) {
                            addy += RAM_BASE;
                            if (isMmio(addy)) {
                                int ctl = bus.store(addy, rs2);
                                if (ctl != 0) {
                                    // syscon: PC already advanced by the bus, bail out of the step loop with the code
                                    this.pc = pc + 4;
                                    this.cyclel = cycle;
                                    return ctl;
                                }
                            } else {
                                trap = 7 + 1;
                                rval = addy;
                            }
                        } else {
                            switch ((ir >>> 12) & 0x7) {
                                case 0 -> ram[addy] = (byte) rs2;
                                case 1 -> store2(addy, rs2);
                                case 2 -> store4(addy, rs2);
                                default -> trap = 2 + 1;
                            }
                        }
                    }
                    case 0x13, 0x33 -> { // OP-IMM, OP
                        int imm = ir >>> 20;
                        imm = imm | (((imm & 0x800) != 0) ? 0xfffff000 : 0);
                        int rs1 = regs[(ir >>> 15) & 0x1f];
                        boolean isReg = (ir & 0x20) != 0;
                        int rs2 = isReg ? regs[imm & 0x1f] : imm;

                        if (isReg && (ir & 0x02000000) != 0) {
                            switch ((ir >>> 12) & 7) { // RV32M
                                case 0 -> rval = rs1 * rs2;
                                case 1 -> rval = (int) (((long) rs1 * (long) rs2) >> 32);
                                case 2 -> rval = (int) (((long) rs1 * Integer.toUnsignedLong(rs2)) >> 32);
                                case 3 -> rval = (int) ((Integer.toUnsignedLong(rs1) * Integer.toUnsignedLong(rs2)) >>> 32);
                                case 4 -> {
                                    if (rs2 == 0) rval = -1;
                                    else rval = (rs1 == Integer.MIN_VALUE && rs2 == -1) ? rs1 : rs1 / rs2;
                                }
                                case 5 -> rval = (rs2 == 0) ? 0xffffffff : Integer.divideUnsigned(rs1, rs2);
                                case 6 -> {
                                    if (rs2 == 0) rval = rs1;
                                    else rval = (rs1 == Integer.MIN_VALUE && rs2 == -1) ? 0 : rs1 % rs2;
                                }
                                case 7 -> rval = (rs2 == 0) ? rs1 : Integer.remainderUnsigned(rs1, rs2);
                                default -> { }
                            }
                        } else {
                            switch ((ir >>> 12) & 7) {
                                case 0 -> rval = (isReg && (ir & 0x40000000) != 0) ? (rs1 - rs2) : (rs1 + rs2);
                                case 1 -> rval = rs1 << (rs2 & 0x1F);
                                case 2 -> rval = (rs1 < rs2) ? 1 : 0;
                                case 3 -> rval = ltu(rs1, rs2) ? 1 : 0;
                                case 4 -> rval = rs1 ^ rs2;
                                case 5 -> rval = ((ir & 0x40000000) != 0) ? (rs1 >> (rs2 & 0x1F)) : (rs1 >>> (rs2 & 0x1F));
                                case 6 -> rval = rs1 | rs2;
                                case 7 -> rval = rs1 & rs2;
                                default -> { }
                            }
                        }
                    }
                    case 0x0f -> rdid = 0; // FENCE / FENCE.I: nothing to do with one in-order hart
                    case 0x73 -> { // SYSTEM: Zicsr, ECALL, EBREAK, MRET, WFI
                        int csrno = ir >>> 20;
                        int microop = (ir >>> 12) & 0x7;
                        if ((microop & 3) != 0) {
                            int rs1imm = (ir >>> 15) & 0x1f;
                            int rs1 = regs[rs1imm];
                            int writeval = rs1;

                            switch (csrno) {
                                case 0x340 -> rval = mscratch;
                                case 0x305 -> rval = mtvec;
                                case 0x304 -> rval = mie;
                                case 0xC00 -> rval = cycle;
                                case 0x344 -> rval = mip;
                                case 0x341 -> rval = mepc;
                                case 0x300 -> rval = mstatus;
                                case 0x342 -> rval = mcause;
                                case 0x343 -> rval = mtval;
                                case 0xf11 -> rval = 0xff0ff0ff; // mvendorid
                                case 0x301 -> rval = 0x40401101; // misa: RV32 IMA + X
                                default -> rval = 0;
                            }

                            switch (microop) {
                                case 1 -> writeval = rs1;
                                case 2 -> writeval = rval | rs1;
                                case 3 -> writeval = rval & ~rs1;
                                case 5 -> writeval = rs1imm;
                                case 6 -> writeval = rval | rs1imm;
                                case 7 -> writeval = rval & ~rs1imm;
                                default -> { }
                            }

                            switch (csrno) {
                                case 0x340 -> mscratch = writeval;
                                case 0x305 -> mtvec = writeval;
                                case 0x304 -> mie = writeval;
                                case 0x344 -> mip = writeval;
                                case 0x341 -> mepc = writeval;
                                case 0x300 -> mstatus = writeval;
                                case 0x342 -> mcause = writeval;
                                case 0x343 -> mtval = writeval;
                                default -> { }
                            }
                        } else if (microop == 0) {
                            rdid = 0;
                            if ((csrno & 0xff) == 0x02) { // MRET
                                int startMstatus = mstatus;
                                int startExtraflags = extraflags;
                                mstatus = ((startMstatus & 0x80) >>> 4) | ((startExtraflags & 3) << 11) | 0x80;
                                extraflags = (startExtraflags & ~3) | ((startMstatus >>> 11) & 3);
                                pc = mepc - 4;
                            } else {
                                switch (csrno) {
                                    case 0 -> trap = ((extraflags & 3) != 0) ? (11 + 1) : (8 + 1); // ECALL
                                    case 1 -> trap = 3 + 1; // EBREAK
                                    case 0x105 -> { // WFI
                                        mstatus |= 8;
                                        extraflags |= 4;
                                        if (Integer.compareUnsigned(cyclel, cycle) > 0) cycleh++;
                                        cyclel = cycle;
                                        this.pc = pc + 4;
                                        return STEP_WFI;
                                    }
                                    default -> trap = 2 + 1;
                                }
                            }
                        } else {
                            trap = 2 + 1;
                        }
                    }
                    case 0x2f -> { // RV32A
                        int rs1 = regs[(ir >>> 15) & 0x1f];
                        int rs2 = regs[(ir >>> 20) & 0x1f];
                        int irmid = (ir >>> 27) & 0x1f;
                        rs1 -= RAM_BASE;
                        if (geu(rs1, ramSize - 3)) {
                            trap = 7 + 1;
                            rval = rs1 + RAM_BASE;
                        } else {
                            rval = load4(rs1);
                            boolean dowrite = true;
                            switch (irmid) {
                                case 2 -> { // LR.W
                                    dowrite = false;
                                    extraflags = (extraflags & 0x07) | (rs1 << 3);
                                }
                                case 3 -> { // SC.W
                                    rval = ((extraflags >>> 3) != (rs1 & 0x1fffffff)) ? 1 : 0;
                                    dowrite = rval == 0;
                                }
                                case 1 -> { } // AMOSWAP
                                case 0 -> rs2 += rval;
                                case 4 -> rs2 ^= rval;
                                case 12 -> rs2 &= rval;
                                case 8 -> rs2 |= rval;
                                case 16 -> rs2 = Math.min(rs2, rval);
                                case 20 -> rs2 = Math.max(rs2, rval);
                                case 24 -> rs2 = ltu(rs2, rval) ? rs2 : rval;
                                case 28 -> rs2 = Integer.compareUnsigned(rs2, rval) > 0 ? rs2 : rval;
                                default -> {
                                    trap = 2 + 1;
                                    dowrite = false;
                                }
                            }
                            if (dowrite) store4(rs1, rs2);
                        }
                    }
                    default -> trap = 2 + 1;
                }

                if (trap != 0) {
                    break;
                }
                if (rdid != 0) {
                    regs[rdid] = rval;
                }
                pc += 4;
            }
        }

        if (trap != 0) {
            if ((trap & 0x80000000) != 0) {
                mcause = trap;
                mtval = 0;
                pc += 4;
            } else {
                mcause = trap - 1;
                mtval = (trap > 5 && trap <= 8) ? rval : pc;
            }
            mepc = pc;
            mstatus = ((mstatus & 0x08) << 4) | ((extraflags & 3) << 11);
            pc = mtvec - 4;
            extraflags |= 3;
            pc += 4;
        }

        if (Integer.compareUnsigned(cyclel, cycle) > 0) cycleh++;
        cyclel = cycle;
        this.pc = pc;
        return STEP_OK;
    }
}
