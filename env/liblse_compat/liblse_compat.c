#define _GNU_SOURCE
#include <signal.h>
#include <ucontext.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <unistd.h>
#include <sys/auxv.h>
#include <dlfcn.h>

#ifndef HWCAP_ATOMICS
#define HWCAP_ATOMICS (1 << 8)
#endif

/* Linux kernel sigaction structure for AArch64 rt_sigaction syscall (134) */
struct kernel_sigaction {
    void (*k_handler)(int, siginfo_t *, void *);
    unsigned long sa_flags;
    void (*sa_restorer)(void);
    unsigned long sa_mask;
};

#ifndef FPSIMD_MAGIC
#define FPSIMD_MAGIC 0x46508001
#endif

struct _aarch64_ctx_local {
    uint32_t magic;
    uint32_t size;
};

struct fpsimd_ctx_local {
    struct _aarch64_ctx_local head;
    uint32_t fpsr;
    uint32_t fpcr;
    __uint128_t vregs[32];
};

static struct fpsimd_ctx_local *get_fpsimd_ctx(ucontext_t *uc) {
    uint8_t *p = (uint8_t *)uc->uc_mcontext.__reserved;
    while (p < (uint8_t *)uc->uc_mcontext.__reserved + sizeof(uc->uc_mcontext.__reserved) - sizeof(struct _aarch64_ctx_local)) {
        struct _aarch64_ctx_local *ctx = (struct _aarch64_ctx_local *)p;
        if (ctx->magic == FPSIMD_MAGIC) {
            return (struct fpsimd_ctx_local *)p;
        }
        if (ctx->size == 0) break;
        p += ctx->size;
    }
    return NULL;
}

static struct sigaction old_sigill_act;
static bool lse_handler_installed = false;

static void log_raw(const char *s) {
    const char *p = s;
    while (*p) p++;
    write(2, s, (size_t)(p - s));
}

static void log_hex(uint64_t val) {
    char buf[19];
    buf[0] = '0';
    buf[1] = 'x';
    for (int i = 15; i >= 0; i--) {
        int nib = (int)((val >> (i * 4)) & 0xF);
        buf[2 + (15 - i)] = (char)((nib < 10) ? ('0' + nib) : ('a' + nib - 10));
    }
    buf[18] = '\0';
    write(2, buf, 18);
}

static int raw_rt_sigaction(int sig, const struct kernel_sigaction *act, struct kernel_sigaction *oact) {
    register long x0 __asm__("x0") = sig;
    register long x1 __asm__("x1") = (long)act;
    register long x2 __asm__("x2") = (long)oact;
    register long x3 __asm__("x3") = 8; /* sizeof(kernel_sigset_t) on arm64 */
    register long x8 __asm__("x8") = 134; /* __NR_rt_sigaction */
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x8) : "memory");
    return (int)x0;
}

static inline uint64_t get_reg(const ucontext_t *uc, unsigned reg, bool is_sp) {
    if (reg == 31) {
        return is_sp ? uc->uc_mcontext.sp : 0ULL;
    }
    return uc->uc_mcontext.regs[reg];
}

static inline void set_reg(ucontext_t *uc, unsigned reg, uint64_t val, unsigned sz) {
    if (reg == 31) {
        return; // Writes to WZR/XZR are discarded
    }
    if (sz == 0) {
        uc->uc_mcontext.regs[reg] = (uint64_t)(uint8_t)val;
    } else if (sz == 1) {
        uc->uc_mcontext.regs[reg] = (uint64_t)(uint16_t)val;
    } else if (sz == 2) {
        uc->uc_mcontext.regs[reg] = (uint64_t)(uint32_t)val;
    } else {
        uc->uc_mcontext.regs[reg] = val;
    }
}

#define EMULATE_OP(ptr_type, s_type, ptr, s_val, opc, old_out) do { \
    ptr_type *p = (ptr_type *)(ptr); \
    ptr_type s = (ptr_type)(s_val); \
    ptr_type old; \
    switch (opc) { \
        case 0: /* LDADD / STADD */ \
            old = __atomic_fetch_add(p, s, __ATOMIC_SEQ_CST); \
            break; \
        case 1: /* LDCLR / STCLR */ \
            old = __atomic_fetch_and(p, ~s, __ATOMIC_SEQ_CST); \
            break; \
        case 2: /* LDEOR / STEOR */ \
            old = __atomic_fetch_xor(p, s, __ATOMIC_SEQ_CST); \
            break; \
        case 3: /* LDSET / STSET */ \
            old = __atomic_fetch_or(p, s, __ATOMIC_SEQ_CST); \
            break; \
        case 4: { /* LDSMAX / STSMAX (signed) */ \
            ptr_type cur = __atomic_load_n(p, __ATOMIC_SEQ_CST); \
            while (1) { \
                ptr_type des = ((s_type)cur > (s_type)s) ? cur : s; \
                if (__atomic_compare_exchange_n(p, &cur, des, true, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)) { \
                    old = cur; \
                    break; \
                } \
            } \
            break; \
        } \
        case 5: { /* LDSMIN / STSMIN (signed) */ \
            ptr_type cur = __atomic_load_n(p, __ATOMIC_SEQ_CST); \
            while (1) { \
                ptr_type des = ((s_type)cur < (s_type)s) ? cur : s; \
                if (__atomic_compare_exchange_n(p, &cur, des, true, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)) { \
                    old = cur; \
                    break; \
                } \
            } \
            break; \
        } \
        case 6: { /* LDUMAX / STUMAX (unsigned) */ \
            ptr_type cur = __atomic_load_n(p, __ATOMIC_SEQ_CST); \
            while (1) { \
                ptr_type des = (cur > s) ? cur : s; \
                if (__atomic_compare_exchange_n(p, &cur, des, true, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)) { \
                    old = cur; \
                    break; \
                } \
            } \
            break; \
        } \
        case 7: { /* LDUMIN / STUMIN (unsigned) */ \
            ptr_type cur = __atomic_load_n(p, __ATOMIC_SEQ_CST); \
            while (1) { \
                ptr_type des = (cur < s) ? cur : s; \
                if (__atomic_compare_exchange_n(p, &cur, des, true, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)) { \
                    old = cur; \
                    break; \
                } \
            } \
            break; \
        } \
        case 8: /* SWP */ \
            old = __atomic_exchange_n(p, s, __ATOMIC_SEQ_CST); \
            break; \
        default: \
            return false; \
    } \
    *(old_out) = (uint64_t)old; \
} while (0)

#define EMULATE_CAS(ptr_type, ptr, compare_val, new_val, old_out) do { \
    ptr_type *p = (ptr_type *)(ptr); \
    ptr_type expected = (ptr_type)(compare_val); \
    ptr_type desired = (ptr_type)(new_val); \
    __atomic_compare_exchange_n(p, &expected, desired, false, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST); \
    *(old_out) = (uint64_t)expected; \
} while (0)

static bool emulate_lse(ucontext_t *uc, uint32_t insn) {
    unsigned sz = (insn >> 30) & 3;

    // Pattern 0: Load-Acquire RCpc (LDAPR, LDAPRB, LDAPRH) [ARMv8.3-A / FEAT_LRCPC]
    // Mask: 0x3FFFFC00 == 0x38BFC000 (e.g. ldaprb w8, [x8] -> 0x38bfc108)
    if ((insn & 0x3FFFFC00) == 0x38BFC000) {
        unsigned rn = (insn >> 5) & 0x1F;
        unsigned rt = insn & 0x1F;
        uintptr_t addr = (uintptr_t)get_reg(uc, rn, true);
        uint64_t val = 0;

        switch (sz) {
            case 0: val = *(const volatile uint8_t *)addr; break;
            case 1: val = *(const volatile uint16_t *)addr; break;
            case 2: val = *(const volatile uint32_t *)addr; break;
            case 3: val = *(const volatile uint64_t *)addr; break;
        }

        __atomic_thread_fence(__ATOMIC_ACQUIRE);
        if (rt != 31) {
            set_reg(uc, rt, val, sz);
        }
        return true;
    }

    // Pattern 1: Atomic memory operations (LDADD, LDCLR, LDEOR, LDSET, SWP, LDSMAX, LDSMIN, LDUMAX, LDUMIN)
    // Bits 29..24 = 111000 (0x38), Bit 21 = 1, Bits 11..10 = 00
    if (((insn >> 24) & 0x3F) == 0x38 && ((insn >> 21) & 1) == 1 && ((insn >> 10) & 3) == 0) {
        unsigned rs = (insn >> 16) & 0x1F;
        unsigned rn = (insn >> 5) & 0x1F;
        unsigned rt = insn & 0x1F;
        unsigned op_bits = (insn >> 12) & 0xF;

        uintptr_t addr = (uintptr_t)get_reg(uc, rn, true);
        uint64_t s_val = get_reg(uc, rs, false);
        uint64_t old_val = 0;

        switch (sz) {
            case 0:
                EMULATE_OP(uint8_t, int8_t, addr, s_val, op_bits, &old_val);
                break;
            case 1:
                EMULATE_OP(uint16_t, int16_t, addr, s_val, op_bits, &old_val);
                break;
            case 2:
                EMULATE_OP(uint32_t, int32_t, addr, s_val, op_bits, &old_val);
                break;
            case 3:
                EMULATE_OP(uint64_t, int64_t, addr, s_val, op_bits, &old_val);
                break;
            default:
                return false;
        }

        if (rt != 31) {
            set_reg(uc, rt, old_val, sz);
        }
        return true;
    }

    // Pattern 2: Compare and Swap (CAS, CASA, CASL, CASAL)
    // Bits 29..24 = 001000 (0x08), Bit 23 = 1, Bit 21 = 1, Bits 14..10 = 11111 (0x1F)
    if (((insn >> 24) & 0x3F) == 0x08 && ((insn >> 23) & 1) == 1 && ((insn >> 21) & 1) == 1 && ((insn >> 10) & 0x1F) == 0x1F) {
        unsigned rs = (insn >> 16) & 0x1F; // compare value & destination for old value
        unsigned rn = (insn >> 5) & 0x1F;  // memory address
        unsigned rt = insn & 0x1F;         // new value to store

        uintptr_t addr = (uintptr_t)get_reg(uc, rn, true);
        uint64_t compare_val = get_reg(uc, rs, false);
        uint64_t new_val = get_reg(uc, rt, false);
        uint64_t old_val = 0;

        switch (sz) {
            case 0:
                EMULATE_CAS(uint8_t, addr, compare_val, new_val, &old_val);
                break;
            case 1:
                EMULATE_CAS(uint16_t, addr, compare_val, new_val, &old_val);
                break;
            case 2:
                EMULATE_CAS(uint32_t, addr, compare_val, new_val, &old_val);
                break;
            case 3:
                EMULATE_CAS(uint64_t, addr, compare_val, new_val, &old_val);
                break;
            default:
                return false;
        }

        if (rs != 31) {
            set_reg(uc, rs, old_val, sz);
        }
        return true;
    }

    // Pattern 3: Compare and Swap Pair (CASP, CASPA, CASPL, CASPAL)
    // Bits 29..24 = 001000 (0x08), Bit 23 = 0, Bit 21 = 1, Bits 14..10 = 11111 (0x1F)
    if (((insn >> 24) & 0x3F) == 0x08 && ((insn >> 23) & 1) == 0 && ((insn >> 21) & 1) == 1 && ((insn >> 10) & 0x1F) == 0x1F) {
        unsigned rs = ((insn >> 16) & 0x1F) & ~1U; // even register
        unsigned rn = (insn >> 5) & 0x1F;
        unsigned rt = (insn & 0x1F) & ~1U;         // even register

        uintptr_t addr = (uintptr_t)get_reg(uc, rn, true);
        unsigned bit30 = (insn >> 30) & 1;
        bool is_acquire = ((insn >> 15) & 1) != 0;
        bool is_release = ((insn >> 22) & 1) != 0;

        if (bit30 == 0) {
            uint32_t cmp_lo = (uint32_t)get_reg(uc, rs, false);
            uint32_t cmp_hi = (uint32_t)get_reg(uc, rs + 1, false);
            uint32_t new_lo = (uint32_t)get_reg(uc, rt, false);
            uint32_t new_hi = (uint32_t)get_reg(uc, rt + 1, false);
            uint32_t old_lo = 0, old_hi = 0;
            uint32_t res = 0;

            if (is_acquire && is_release) {
                __asm__ volatile (
                    "1:\n"
                    "ldaxp %w0, %w1, [%4]\n"
                    "cmp %w0, %w5\n"
                    "ccmp %w1, %w6, #0, eq\n"
                    "b.ne 2f\n"
                    "stlxp %w2, %w7, %w8, [%4]\n"
                    "cbnz %w2, 1b\n"
                    "b 3f\n"
                    "2:\n"
                    "clrex\n"
                    "3:\n"
                    : "=&r"(old_lo), "=&r"(old_hi), "=&r"(res), "+m"(*(uint64_t *)addr)
                    : "r"(addr), "r"(cmp_lo), "r"(cmp_hi), "r"(new_lo), "r"(new_hi)
                    : "cc", "memory"
                );
            } else if (is_acquire) {
                __asm__ volatile (
                    "1:\n"
                    "ldaxp %w0, %w1, [%4]\n"
                    "cmp %w0, %w5\n"
                    "ccmp %w1, %w6, #0, eq\n"
                    "b.ne 2f\n"
                    "stxp %w2, %w7, %w8, [%4]\n"
                    "cbnz %w2, 1b\n"
                    "b 3f\n"
                    "2:\n"
                    "clrex\n"
                    "3:\n"
                    : "=&r"(old_lo), "=&r"(old_hi), "=&r"(res), "+m"(*(uint64_t *)addr)
                    : "r"(addr), "r"(cmp_lo), "r"(cmp_hi), "r"(new_lo), "r"(new_hi)
                    : "cc", "memory"
                );
            } else if (is_release) {
                __asm__ volatile (
                    "1:\n"
                    "ldxp %w0, %w1, [%4]\n"
                    "cmp %w0, %w5\n"
                    "ccmp %w1, %w6, #0, eq\n"
                    "b.ne 2f\n"
                    "stlxp %w2, %w7, %w8, [%4]\n"
                    "cbnz %w2, 1b\n"
                    "b 3f\n"
                    "2:\n"
                    "clrex\n"
                    "3:\n"
                    : "=&r"(old_lo), "=&r"(old_hi), "=&r"(res), "+m"(*(uint64_t *)addr)
                    : "r"(addr), "r"(cmp_lo), "r"(cmp_hi), "r"(new_lo), "r"(new_hi)
                    : "cc", "memory"
                );
            } else {
                __asm__ volatile (
                    "1:\n"
                    "ldxp %w0, %w1, [%4]\n"
                    "cmp %w0, %w5\n"
                    "ccmp %w1, %w6, #0, eq\n"
                    "b.ne 2f\n"
                    "stxp %w2, %w7, %w8, [%4]\n"
                    "cbnz %w2, 1b\n"
                    "b 3f\n"
                    "2:\n"
                    "clrex\n"
                    "3:\n"
                    : "=&r"(old_lo), "=&r"(old_hi), "=&r"(res), "+m"(*(uint64_t *)addr)
                    : "r"(addr), "r"(cmp_lo), "r"(cmp_hi), "r"(new_lo), "r"(new_hi)
                    : "cc", "memory"
                );
            }
            set_reg(uc, rs, (uint64_t)old_lo, 2);
            set_reg(uc, rs + 1, (uint64_t)old_hi, 2);
            return true;
        } else {
            uint64_t cmp_lo = get_reg(uc, rs, false);
            uint64_t cmp_hi = get_reg(uc, rs + 1, false);
            uint64_t new_lo = get_reg(uc, rt, false);
            uint64_t new_hi = get_reg(uc, rt + 1, false);
            uint64_t old_lo = 0, old_hi = 0;
            uint32_t res = 0;

            if (is_acquire && is_release) {
                __asm__ volatile (
                    "1:\n"
                    "ldaxp %0, %1, [%4]\n"
                    "cmp %0, %5\n"
                    "ccmp %1, %6, #0, eq\n"
                    "b.ne 2f\n"
                    "stlxp %w2, %7, %8, [%4]\n"
                    "cbnz %w2, 1b\n"
                    "b 3f\n"
                    "2:\n"
                    "clrex\n"
                    "3:\n"
                    : "=&r"(old_lo), "=&r"(old_hi), "=&r"(res), "+m"(*(unsigned __int128 *)addr)
                    : "r"(addr), "r"(cmp_lo), "r"(cmp_hi), "r"(new_lo), "r"(new_hi)
                    : "cc", "memory"
                );
            } else if (is_acquire) {
                __asm__ volatile (
                    "1:\n"
                    "ldaxp %0, %1, [%4]\n"
                    "cmp %0, %5\n"
                    "ccmp %1, %6, #0, eq\n"
                    "b.ne 2f\n"
                    "stxp %w2, %7, %8, [%4]\n"
                    "cbnz %w2, 1b\n"
                    "b 3f\n"
                    "2:\n"
                    "clrex\n"
                    "3:\n"
                    : "=&r"(old_lo), "=&r"(old_hi), "=&r"(res), "+m"(*(unsigned __int128 *)addr)
                    : "r"(addr), "r"(cmp_lo), "r"(cmp_hi), "r"(new_lo), "r"(new_hi)
                    : "cc", "memory"
                );
            } else if (is_release) {
                __asm__ volatile (
                    "1:\n"
                    "ldxp %0, %1, [%4]\n"
                    "cmp %0, %5\n"
                    "ccmp %1, %6, #0, eq\n"
                    "b.ne 2f\n"
                    "stlxp %w2, %7, %8, [%4]\n"
                    "cbnz %w2, 1b\n"
                    "b 3f\n"
                    "2:\n"
                    "clrex\n"
                    "3:\n"
                    : "=&r"(old_lo), "=&r"(old_hi), "=&r"(res), "+m"(*(unsigned __int128 *)addr)
                    : "r"(addr), "r"(cmp_lo), "r"(cmp_hi), "r"(new_lo), "r"(new_hi)
                    : "cc", "memory"
                );
            } else {
                __asm__ volatile (
                    "1:\n"
                    "ldxp %0, %1, [%4]\n"
                    "cmp %0, %5\n"
                    "ccmp %1, %6, #0, eq\n"
                    "b.ne 2f\n"
                    "stxp %w2, %7, %8, [%4]\n"
                    "cbnz %w2, 1b\n"
                    "b 3f\n"
                    "2:\n"
                    "clrex\n"
                    "3:\n"
                    : "=&r"(old_lo), "=&r"(old_hi), "=&r"(res), "+m"(*(unsigned __int128 *)addr)
                    : "r"(addr), "r"(cmp_lo), "r"(cmp_hi), "r"(new_lo), "r"(new_hi)
                    : "cc", "memory"
                );
            }
            set_reg(uc, rs, old_lo, 3);
            set_reg(uc, rs + 1, old_hi, 3);
            return true;
        }
    }

    // Pattern 4: RCpc2 unscaled loads and stores (LDAPUR, LDAPURB, LDAPURH, LDAPURSB, LDAPURSH, LDAPURSW, STLUR, STLURB, STLURH) [ARMv8.4-A / FEAT_LRCPC2]
    // Bits 29..24 = 011001 (0x19), Bit 21 = 0, Bits 11..10 = 00
    if (((insn >> 24) & 0x3F) == 0x19 && ((insn >> 21) & 1) == 0 && ((insn >> 10) & 3) == 0) {
        unsigned opc = (insn >> 22) & 3;
        int32_t simm9 = (int32_t)((insn >> 12) & 0x1FF);
        if (simm9 & 0x100) {
            simm9 |= ~0x1FF; // sign-extend 9-bit immediate
        }
        unsigned rn = (insn >> 5) & 0x1F;
        unsigned rt = insn & 0x1F;
        uintptr_t addr = (uintptr_t)(get_reg(uc, rn, true) + (int64_t)simm9);

        if (opc == 0) {
            // STLUR (Store-Release)
            uint64_t s_val = get_reg(uc, rt, false);
            __atomic_thread_fence(__ATOMIC_RELEASE);
            switch (sz) {
                case 0: *(volatile uint8_t *)addr = (uint8_t)s_val; break;
                case 1: *(volatile uint16_t *)addr = (uint16_t)s_val; break;
                case 2: *(volatile uint32_t *)addr = (uint32_t)s_val; break;
                case 3: *(volatile uint64_t *)addr = (uint64_t)s_val; break;
            }
            return true;
        } else if (opc == 1) {
            // LDAPUR (Load-Acquire unsigned)
            uint64_t val = 0;
            switch (sz) {
                case 0: val = *(const volatile uint8_t *)addr; break;
                case 1: val = *(const volatile uint16_t *)addr; break;
                case 2: val = *(const volatile uint32_t *)addr; break;
                case 3: val = *(const volatile uint64_t *)addr; break;
            }
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            if (rt != 31) {
                set_reg(uc, rt, val, sz);
            }
            return true;
        } else if (opc == 2) {
            // LDAPURSB / LDAPURSH / LDAPURSW (signed 64-bit into Xt)
            uint64_t val = 0;
            switch (sz) {
                case 0: val = (uint64_t)(int64_t)(*(const volatile int8_t *)addr); break;
                case 1: val = (uint64_t)(int64_t)(*(const volatile int16_t *)addr); break;
                case 2: val = (uint64_t)(int64_t)(*(const volatile int32_t *)addr); break;
                default: return false;
            }
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            if (rt != 31) {
                set_reg(uc, rt, val, 3);
            }
            return true;
        } else if (opc == 3) {
            // LDAPURSB / LDAPURSH (signed 32-bit into Wt)
            uint64_t val = 0;
            switch (sz) {
                case 0: val = (uint64_t)(uint32_t)(int32_t)(*(const volatile int8_t *)addr); break;
                case 1: val = (uint64_t)(uint32_t)(int32_t)(*(const volatile int16_t *)addr); break;
                default: return false;
            }
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            if (rt != 31) {
                set_reg(uc, rt, val, 2);
            }
            return true;
        }
    }

    return false;
}

static bool emulate_dotprod(ucontext_t *uc, uint32_t insn) {
    bool is_vec = ((insn & 0x9fe0fc00) == 0x0e809400);
    bool is_elem = ((insn & 0x9fc0f400) == 0x0f80e000);
    if (!is_vec && !is_elem) {
        return false;
    }

    struct fpsimd_ctx_local *fpsimd = get_fpsimd_ctx(uc);
    if (!fpsimd) {
        return false;
    }

    unsigned q = (insn >> 30) & 1;
    unsigned u = (insn >> 29) & 1;
    unsigned rm = (insn >> 16) & 0x1F;
    unsigned rn = (insn >> 5) & 0x1F;
    unsigned rd = insn & 0x1F;

    uint8_t vd_copy[16];
    uint8_t vn_copy[16];
    uint8_t vm_copy[16];
    memcpy(vd_copy, (const void *)&fpsimd->vregs[rd], 16);
    memcpy(vn_copy, (const void *)&fpsimd->vregs[rn], 16);
    memcpy(vm_copy, (const void *)&fpsimd->vregs[rm], 16);

    uint32_t *vd32 = (uint32_t *)vd_copy;
    int num_lanes = q ? 4 : 2;

    if (is_vec) {
        for (int i = 0; i < num_lanes; i++) {
            if (u) {
                uint32_t sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += (uint32_t)vn_copy[4 * i + k] * (uint32_t)vm_copy[4 * i + k];
                }
                vd32[i] += sum;
            } else {
                int32_t sum = 0;
                const int8_t *vn_s = (const int8_t *)vn_copy;
                const int8_t *vm_s = (const int8_t *)vm_copy;
                for (int k = 0; k < 4; k++) {
                    sum += (int32_t)vn_s[4 * i + k] * (int32_t)vm_s[4 * i + k];
                }
                ((int32_t *)vd32)[i] += sum;
            }
        }
    } else {
        unsigned h = (insn >> 11) & 1;
        unsigned l = (insn >> 21) & 1;
        unsigned index = (h << 1) | l;
        int m_offset = (int)(index * 4);

        for (int i = 0; i < num_lanes; i++) {
            if (u) {
                uint32_t sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += (uint32_t)vn_copy[4 * i + k] * (uint32_t)vm_copy[m_offset + k];
                }
                vd32[i] += sum;
            } else {
                int32_t sum = 0;
                const int8_t *vn_s = (const int8_t *)vn_copy;
                const int8_t *vm_s = (const int8_t *)vm_copy;
                for (int k = 0; k < 4; k++) {
                    sum += (int32_t)vn_s[4 * i + k] * (int32_t)vm_s[m_offset + k];
                }
                ((int32_t *)vd32)[i] += sum;
            }
        }
    }

    if (!q) {
        memset(vd_copy + 8, 0, 8);
    }
    memcpy((void *)&fpsimd->vregs[rd], vd_copy, 16);

    return true;
}

static inline int16_t do_sqrdmlah_h(int16_t src1, int16_t src2, int16_t src3, bool sub) {
    int32_t prod = (int32_t)src1 * (int32_t)src2;
    if (sub) {
        prod = -prod;
    }
    int32_t ret = prod + ((int32_t)src3 << 15) + (1 << 14);
    ret >>= 15;
    if (ret > 32767) return 32767;
    if (ret < -32768) return -32768;
    return (int16_t)ret;
}

static inline int32_t do_sqrdmlah_s(int32_t src1, int32_t src2, int32_t src3, bool sub) {
    __int128 prod = (__int128)src1 * (__int128)src2;
    if (sub) {
        prod = -prod;
    }
    __int128 ret = prod + ((__int128)src3 << 31) + ((__int128)1 << 30);
    ret >>= 31;
    if (ret > 2147483647LL) return 2147483647;
    if (ret < -2147483648LL) return -2147483648LL;
    return (int32_t)ret;
}

static bool emulate_rdm(ucontext_t *uc, uint32_t insn) {
    bool is_vec = ((insn & 0xbf20f400) == 0x2e008400);
    bool is_elem = ((insn & 0xbf00d400) == 0x2f00d000);
    if (!is_vec && !is_elem) {
        return false;
    }

    unsigned sz = (insn >> 22) & 3;
    if (sz != 1 && sz != 2) {
        return false;
    }

    struct fpsimd_ctx_local *fpsimd = get_fpsimd_ctx(uc);
    if (!fpsimd) {
        return false;
    }

    unsigned q = (insn >> 30) & 1;
    unsigned rn = (insn >> 5) & 0x1F;
    unsigned rd = insn & 0x1F;
    bool sub = is_vec ? (((insn >> 11) & 1) != 0) : (((insn >> 13) & 1) != 0);

    uint8_t vd_copy[16];
    uint8_t vn_copy[16];
    memcpy(vd_copy, (const void *)&fpsimd->vregs[rd], 16);
    memcpy(vn_copy, (const void *)&fpsimd->vregs[rn], 16);

    if (sz == 1) {
        int num_lanes = q ? 8 : 4;
        int16_t *vd16 = (int16_t *)vd_copy;
        const int16_t *vn16 = (const int16_t *)vn_copy;

        if (is_vec) {
            unsigned rm = (insn >> 16) & 0x1F;
            uint8_t vm_copy[16];
            memcpy(vm_copy, (const void *)&fpsimd->vregs[rm], 16);
            const int16_t *vm16 = (const int16_t *)vm_copy;
            for (int i = 0; i < num_lanes; i++) {
                vd16[i] = do_sqrdmlah_h(vn16[i], vm16[i], vd16[i], sub);
            }
        } else {
            unsigned rm = (insn >> 16) & 0xF;
            unsigned index = (((insn >> 11) & 1) << 2) | (((insn >> 21) & 1) << 1) | ((insn >> 20) & 1);
            const int16_t *vm16 = (const int16_t *)&fpsimd->vregs[rm];
            int16_t m_elem = vm16[index];
            for (int i = 0; i < num_lanes; i++) {
                vd16[i] = do_sqrdmlah_h(vn16[i], m_elem, vd16[i], sub);
            }
        }
    } else {
        int num_lanes = q ? 4 : 2;
        int32_t *vd32 = (int32_t *)vd_copy;
        const int32_t *vn32 = (const int32_t *)vn_copy;

        if (is_vec) {
            unsigned rm = (insn >> 16) & 0x1F;
            uint8_t vm_copy[16];
            memcpy(vm_copy, (const void *)&fpsimd->vregs[rm], 16);
            const int32_t *vm32 = (const int32_t *)vm_copy;
            for (int i = 0; i < num_lanes; i++) {
                vd32[i] = do_sqrdmlah_s(vn32[i], vm32[i], vd32[i], sub);
            }
        } else {
            unsigned rm = (insn >> 16) & 0x1F;
            unsigned index = (((insn >> 11) & 1) << 1) | ((insn >> 21) & 1);
            const int32_t *vm32 = (const int32_t *)&fpsimd->vregs[rm];
            int32_t m_elem = vm32[index];
            for (int i = 0; i < num_lanes; i++) {
                vd32[i] = do_sqrdmlah_s(vn32[i], m_elem, vd32[i], sub);
            }
        }
    }

    if (!q) {
        memset(vd_copy + 8, 0, 8);
    }
    memcpy((void *)&fpsimd->vregs[rd], vd_copy, 16);
    return true;
}

static bool emulate_fcma(ucontext_t *uc, uint32_t insn) {
    bool is_fcadd = ((insn & 0xbf20ec00) == 0x2e00e400);
    bool is_fcmla_vec = ((insn & 0xbf20e400) == 0x2e00c400);
    bool is_fcmla_elem = ((insn & 0xbf009400) == 0x2f001000);
    if (!is_fcadd && !is_fcmla_vec && !is_fcmla_elem) {
        return false;
    }

    unsigned sz = (insn >> 22) & 3;
    if (sz == 0) return false;
    unsigned q = (insn >> 30) & 1;
    if (sz == 3 && !q) return false;

    struct fpsimd_ctx_local *fpsimd = get_fpsimd_ctx(uc);
    if (!fpsimd) {
        return false;
    }

    unsigned rn = (insn >> 5) & 0x1F;
    unsigned rd = insn & 0x1F;

    uint8_t vd_copy[16];
    uint8_t vn_copy[16];
    memcpy(vd_copy, (const void *)&fpsimd->vregs[rd], 16);
    memcpy(vn_copy, (const void *)&fpsimd->vregs[rn], 16);

    int elements = 0;
    if (sz == 1) elements = q ? 8 : 4;
    else if (sz == 2) elements = q ? 4 : 2;
    else if (sz == 3) elements = 2;

    if (is_fcadd) {
        unsigned rm = (insn >> 16) & 0x1F;
        uint8_t vm_copy[16];
        memcpy(vm_copy, (const void *)&fpsimd->vregs[rm], 16);
        unsigned rot = (insn >> 12) & 1;

        if (sz == 1) {
            _Float16 *d = (_Float16 *)vd_copy;
            const _Float16 *n = (const _Float16 *)vn_copy;
            const _Float16 *m = (const _Float16 *)vm_copy;
            for (int i = 0; i < elements; i += 2) {
                _Float16 e0 = n[i];
                _Float16 e1 = rot ? m[i + 1] : -m[i + 1];
                _Float16 e2 = n[i + 1];
                _Float16 e3 = rot ? -m[i] : m[i];
                d[i] = e0 + e1;
                d[i + 1] = e2 + e3;
            }
        } else if (sz == 2) {
            float *d = (float *)vd_copy;
            const float *n = (const float *)vn_copy;
            const float *m = (const float *)vm_copy;
            for (int i = 0; i < elements; i += 2) {
                float e0 = n[i];
                float e1 = rot ? m[i + 1] : -m[i + 1];
                float e2 = n[i + 1];
                float e3 = rot ? -m[i] : m[i];
                d[i] = e0 + e1;
                d[i + 1] = e2 + e3;
            }
        } else if (sz == 3) {
            double *d = (double *)vd_copy;
            const double *n = (const double *)vn_copy;
            const double *m = (const double *)vm_copy;
            for (int i = 0; i < elements; i += 2) {
                double e0 = n[i];
                double e1 = rot ? m[i + 1] : -m[i + 1];
                double e2 = n[i + 1];
                double e3 = rot ? -m[i] : m[i];
                d[i] = e0 + e1;
                d[i + 1] = e2 + e3;
            }
        }
    } else if (is_fcmla_vec) {
        unsigned rm = (insn >> 16) & 0x1F;
        uint8_t vm_copy[16];
        memcpy(vm_copy, (const void *)&fpsimd->vregs[rm], 16);
        unsigned rot = (insn >> 11) & 3;
        int flip = rot & 1;
        int neg_imag = (rot >> 1) & 1;
        int neg_real = flip ^ neg_imag;

        if (sz == 1) {
            _Float16 *d = (_Float16 *)vd_copy;
            const _Float16 *n = (const _Float16 *)vn_copy;
            const _Float16 *m = (const _Float16 *)vm_copy;
            for (int i = 0; i < elements; i += 2) {
                _Float16 e2 = n[i + flip];
                _Float16 e1 = neg_real ? -m[i + flip] : m[i + flip];
                _Float16 e4 = e2;
                _Float16 e3 = neg_imag ? -m[i + 1 - flip] : m[i + 1 - flip];
                d[i]     += e2 * e1;
                d[i + 1] += e4 * e3;
            }
        } else if (sz == 2) {
            float *d = (float *)vd_copy;
            const float *n = (const float *)vn_copy;
            const float *m = (const float *)vm_copy;
            for (int i = 0; i < elements; i += 2) {
                float e2 = n[i + flip];
                float e1 = neg_real ? -m[i + flip] : m[i + flip];
                float e4 = e2;
                float e3 = neg_imag ? -m[i + 1 - flip] : m[i + 1 - flip];
                d[i]     += e2 * e1;
                d[i + 1] += e4 * e3;
            }
        } else if (sz == 3) {
            double *d = (double *)vd_copy;
            const double *n = (const double *)vn_copy;
            const double *m = (const double *)vm_copy;
            for (int i = 0; i < elements; i += 2) {
                double e2 = n[i + flip];
                double e1 = neg_real ? -m[i + flip] : m[i + flip];
                double e4 = e2;
                double e3 = neg_imag ? -m[i + 1 - flip] : m[i + 1 - flip];
                d[i]     += e2 * e1;
                d[i + 1] += e4 * e3;
            }
        }
    } else { // is_fcmla_elem
        unsigned rot = (insn >> 13) & 3;
        int flip = rot & 1;
        int neg_imag = (rot >> 1) & 1;
        int neg_real = flip ^ neg_imag;

        if (sz == 1) {
            unsigned rm = (insn >> 16) & 0x1F;
            unsigned index = (((insn >> 11) & 1) << 1) | ((insn >> 21) & 1);
            const _Float16 *m = (const _Float16 *)&fpsimd->vregs[rm];
            _Float16 mr = m[2 * index + 0];
            _Float16 mi = m[2 * index + 1];
            _Float16 e1 = (flip ? mi : mr);
            if (neg_real) e1 = -e1;
            _Float16 e3 = (flip ? mr : mi);
            if (neg_imag) e3 = -e3;

            _Float16 *d = (_Float16 *)vd_copy;
            const _Float16 *n = (const _Float16 *)vn_copy;
            for (int i = 0; i < elements; i += 2) {
                _Float16 e2 = n[i + flip];
                d[i]     += e2 * e1;
                d[i + 1] += e2 * e3;
            }
        } else if (sz == 2) {
            unsigned rm = (insn >> 16) & 0x1F;
            unsigned index = (insn >> 11) & 1;
            const float *m = (const float *)&fpsimd->vregs[rm];
            float mr = m[2 * index + 0];
            float mi = m[2 * index + 1];
            float e1 = (flip ? mi : mr);
            if (neg_real) e1 = -e1;
            float e3 = (flip ? mr : mi);
            if (neg_imag) e3 = -e3;

            float *d = (float *)vd_copy;
            const float *n = (const float *)vn_copy;
            for (int i = 0; i < elements; i += 2) {
                float e2 = n[i + flip];
                d[i]     += e2 * e1;
                d[i + 1] += e2 * e3;
            }
        }
    }

    if (!q) {
        memset(vd_copy + 8, 0, 8);
    }
    memcpy((void *)&fpsimd->vregs[rd], vd_copy, 16);
    return true;
}

static bool emulate_mrs(ucontext_t *uc, uint32_t insn) {
    // MRS Xt, <system_register>: 1101 0101 0011 ...
    // Mask: 0xFFF00000 == 0xD5300000
    if ((insn & 0xFFF00000) == 0xD5300000) {
        unsigned rt = insn & 0x1F;
        uint32_t sys = (insn >> 5) & 0x7FFF;
        uint64_t val = 0;

        switch (sys) {
            case 0x4000: // MIDR_EL1 (Main ID Register)
                // Cortex-A53 r0p4: Implementer=0x41 ('A'), Variant=0, Arch=0xF (ARMv8), Part=0xD03 (Cortex-A53), Rev=4
                val = 0x410FD034ULL;
                break;
            case 0x4040: // ID_AA64PFR0_EL1
                // FP=1, AdvSIMD=1
                val = 0x00000011ULL;
                break;
            case 0x4060: // ID_AA64ISAR0_EL1
                val = 0ULL;
                break;
            case 0x4061: // ID_AA64ISAR1_EL1
                val = 0ULL;
                break;
            default:
                val = 0ULL;
                break;
        }

        if (rt != 31) {
            set_reg(uc, rt, val, 3);
        }
        return true;
    }
    return false;
}

static inline uint32_t crc32_step(uint32_t crc, uint8_t byte, uint32_t poly) {
    crc ^= byte;
    for (int i = 0; i < 8; i++) {
        crc = (crc >> 1) ^ (poly & -(crc & 1));
    }
    return crc;
}

static bool emulate_crc32(ucontext_t *uc, uint32_t insn) {
    // ARMv8 CRC32 / CRC32C: bits 29..24 = 011010 (0x1A), bit 23 = 1, bits 15..13 = 010 (0x2)
    // Mask: 0x7FE0E000 == 0x1AC04000
    if ((insn & 0x7FE0E000) == 0x1AC04000) {
        unsigned sz = (insn >> 21) & 3;
        bool is_c = ((insn >> 12) & 1) != 0;
        unsigned rm = (insn >> 16) & 0x1F;
        unsigned rn = (insn >> 5) & 0x1F;
        unsigned rd = insn & 0x1F;

        uint32_t poly = is_c ? 0x82F63B78U : 0xEDB88320U;
        uint32_t crc = (uint32_t)get_reg(uc, rn, false);
        uint64_t data = get_reg(uc, rm, false);

        int bytes = 1 << sz;
        for (int i = 0; i < bytes; i++) {
            crc = crc32_step(crc, (uint8_t)(data >> (i * 8)), poly);
        }

        if (rd != 31) {
            set_reg(uc, rd, (uint64_t)crc, 2);
        }
        return true;
    }
    return false;
}

static const char *const sigill_paths[] = {
    "/data/data/com.agy/files/logs/sigill.log",
    "/data/user/0/com.agy/files/logs/sigill.log",
    "/data/data/com.agy/files/sigill.log",
    "/data/user/0/com.agy/files/sigill.log",
    "/data/data/com.agy.dev/files/logs/sigill.log",
    "/data/user/0/com.agy.dev/files/logs/sigill.log"
};

static void direct_sigill_log(uintptr_t pc, uint32_t insn, uintptr_t fault_addr) {

    char buf[256];
    int pos = 0;
    const char *hdr = "[lse_compat] FATAL: unhandled SIGILL at PC=0x";
    while (hdr[pos]) { buf[pos] = hdr[pos]; pos++; }
    for (int i = 15; i >= 0; i--) {
        int nib = (int)((pc >> (i * 4)) & 0xF);
        buf[pos++] = (char)((nib < 10) ? ('0' + nib) : ('a' + nib - 10));
    }
    const char *mid1 = " INSN=0x";
    int mpos1 = 0;
    while (mid1[mpos1]) { buf[pos++] = mid1[mpos1++]; }
    for (int i = 7; i >= 0; i--) {
        int nib = (int)((insn >> (i * 4)) & 0xF);
        buf[pos++] = (char)((nib < 10) ? ('0' + nib) : ('a' + nib - 10));
    }
    const char *mid2 = " FAULT_ADDR=0x";
    int mpos2 = 0;
    while (mid2[mpos2]) { buf[pos++] = mid2[mpos2++]; }
    for (int i = 15; i >= 0; i--) {
        int nib = (int)((fault_addr >> (i * 4)) & 0xF);
        buf[pos++] = (char)((nib < 10) ? ('0' + nib) : ('a' + nib - 10));
    }
    buf[pos++] = '\n';

    // Direct output to both stderr (2) and stdout (1) via raw Linux syscalls
    register long s0 __asm__("x0") = 2; // stderr
    register long s1 __asm__("x1") = (long)buf;
    register long s2 __asm__("x2") = pos;
    register long s8 __asm__("x8") = 64; // __NR_write
    __asm__ volatile("svc #0" : "+r"(s0) : "r"(s1), "r"(s2), "r"(s8) : "memory");

    register long o0 __asm__("x0") = 1; // stdout
    register long o1 __asm__("x1") = (long)buf;
    register long o2 __asm__("x2") = pos;
    register long o8 __asm__("x8") = 64; // __NR_write
    __asm__ volatile("svc #0" : "+r"(o0) : "r"(o1), "r"(o2), "r"(o8) : "memory");

    for (int p = 0; p < 6; p++) {
        register long x0 __asm__("x0") = -100; // AT_FDCWD
        register long x1 __asm__("x1") = (long)sigill_paths[p];
        register long x2 __asm__("x2") = 0x441; // O_WRONLY | O_CREAT | O_APPEND
        register long x3 __asm__("x3") = 0644;
        register long x8 __asm__("x8") = 56; // __NR_openat
        __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x8) : "memory");
        int fd = (int)x0;
        if (fd >= 0) {
            register long w0 __asm__("x0") = fd;
            register long w1 __asm__("x1") = (long)buf;
            register long w2 __asm__("x2") = pos;
            register long w8 __asm__("x8") = 64; // __NR_write
            __asm__ volatile("svc #0" : "+r"(w0) : "r"(w1), "r"(w2), "r"(w8) : "memory");

            register long c0 __asm__("x0") = fd;
            register long c8 __asm__("x8") = 57; // __NR_close
            __asm__ volatile("svc #0" : "+r"(c0) : "r"(c8) : "memory");
            break;
        }
    }
}

static void sigill_handler(int sig, siginfo_t *info, void *ucontext) {
    ucontext_t *uc = (ucontext_t *)ucontext;
    uintptr_t pc = uc->uc_mcontext.pc;
    uint32_t insn = *(uint32_t *)pc;

    if (emulate_lse(uc, insn) || emulate_mrs(uc, insn) || emulate_crc32(uc, insn) ||
        emulate_dotprod(uc, insn) || emulate_rdm(uc, insn) || emulate_fcma(uc, insn)) {
        uc->uc_mcontext.pc += 4;
        return;
    }

    uintptr_t fault_addr = info ? (uintptr_t)info->si_addr : 0;
    direct_sigill_log((uintptr_t)pc, insn, fault_addr);

    // Pass-through to original handler if not an LSE instruction
    if (old_sigill_act.sa_flags & SA_SIGINFO) {
        if (old_sigill_act.sa_sigaction) {
            old_sigill_act.sa_sigaction(sig, info, ucontext);
            return;
        }
    } else if (old_sigill_act.sa_handler) {
        if (old_sigill_act.sa_handler == SIG_DFL || old_sigill_act.sa_handler == SIG_IGN) {
            signal(SIGILL, old_sigill_act.sa_handler);
            raise(SIGILL);
            return;
        }
        old_sigill_act.sa_handler(sig);
        return;
    }

    // Restore kernel default SIGILL handler and re-raise
    struct kernel_sigaction dfl_act;
    memset(&dfl_act, 0, sizeof(dfl_act));
    dfl_act.k_handler = (void *)SIG_DFL;
    dfl_act.sa_flags = 0;
    dfl_act.sa_restorer = NULL;
    dfl_act.sa_mask = 0;
    raw_rt_sigaction(SIGILL, &dfl_act, NULL);
    raise(SIGILL);
}

static int raw_rt_sigprocmask(int how, const void *set, void *oldset, size_t sigsetsize) {
    register long x0 __asm__("x0") = how;
    register long x1 __asm__("x1") = (long)set;
    register long x2 __asm__("x2") = (long)oldset;
    register long x3 __asm__("x3") = sigsetsize;
    register long x8 __asm__("x8") = 135; /* __NR_rt_sigprocmask */
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x8) : "memory");
    return (int)x0;
}

int sigprocmask(int how, const sigset_t *set, sigset_t *oldset) {
    if (set != NULL && how != 1 /* SIG_UNBLOCK */) {
        sigset_t copy = *set;
        sigdelset(&copy, SIGILL);
        return raw_rt_sigprocmask(how, &copy, oldset, sizeof(sigset_t));
    }
    return raw_rt_sigprocmask(how, set, oldset, sizeof(sigset_t));
}

int pthread_sigmask(int how, const sigset_t *set, sigset_t *oldset) {
    return sigprocmask(how, set, oldset);
}

int sigaction(int signum, const struct sigaction *act, struct sigaction *oldact) {
    if (signum == SIGILL) {
        if (oldact) {
            *oldact = old_sigill_act;
        }
        if (act) {
            old_sigill_act = *act;
        }
        return 0; // Keep kernel handler point to sigill_handler
    }

    struct sigaction copy;
    if (act != NULL) {
        copy = *act;
        sigdelset(&copy.sa_mask, SIGILL);
        act = &copy;
    }

    typedef int (*sigaction_fn)(int, const struct sigaction *, struct sigaction *);
    static sigaction_fn real_sigaction = NULL;
    if (!real_sigaction) {
        real_sigaction = (sigaction_fn)dlsym(RTLD_NEXT, "sigaction");
    }
    if (real_sigaction) {
        return real_sigaction(signum, act, oldact);
    }
    return 0;
}

#ifndef LSE_COMPAT_BUILD
#define LSE_COMPAT_BUILD "unknown"
#endif

__attribute__((constructor(101))) static void init_lse_compat(void) {
    unsigned long hwcap = getauxval(AT_HWCAP);
    log_raw("[lse_compat] INIT build=" LSE_COMPAT_BUILD " hwcap=");
    log_hex((uint64_t)hwcap);
    log_raw("\n");
    log_raw("[lse_compat] installing kernel SIGILL handler\n");
    lse_handler_installed = true;

    char init_buf[128];
    int ipos = 0;
    const char *ihdr = "[lse_compat] INIT build=" LSE_COMPAT_BUILD " hwcap=0x";
    while (ihdr[ipos]) { init_buf[ipos] = ihdr[ipos]; ipos++; }
    for (int i = 15; i >= 0; i--) {
        int nib = (int)((hwcap >> (i * 4)) & 0xF);
        init_buf[ipos++] = (char)((nib < 10) ? ('0' + nib) : ('a' + nib - 10));
    }
    init_buf[ipos++] = '\n';

    for (int p = 0; p < 6; p++) {
        register long x0 __asm__("x0") = -100; // AT_FDCWD
        register long x1 __asm__("x1") = (long)sigill_paths[p];
        register long x2 __asm__("x2") = 0x241; // O_WRONLY | O_CREAT | O_TRUNC
        register long x3 __asm__("x3") = 0644;
        register long x8 __asm__("x8") = 56; // __NR_openat
        __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x8) : "memory");
        int fd = (int)x0;
        if (fd >= 0) {
            register long w0 __asm__("x0") = fd;
            register long w1 __asm__("x1") = (long)init_buf;
            register long w2 __asm__("x2") = ipos;
            register long w8 __asm__("x8") = 64; // __NR_write
            __asm__ volatile("svc #0" : "+r"(w0) : "r"(w1), "r"(w2), "r"(w8) : "memory");

            register long c0 __asm__("x0") = fd;
            register long c8 __asm__("x8") = 57; // __NR_close
            __asm__ volatile("svc #0" : "+r"(c0) : "r"(c8) : "memory");
            break;
        }
    }

    struct kernel_sigaction ksa;
    memset(&ksa, 0, sizeof(ksa));
    ksa.k_handler = sigill_handler;
    ksa.sa_flags = SA_SIGINFO | SA_NODEFER | SA_ONSTACK;
    ksa.sa_restorer = NULL;
    ksa.sa_mask = 0;

    struct kernel_sigaction old_ksa;
    memset(&old_ksa, 0, sizeof(old_ksa));

    int ret = raw_rt_sigaction(SIGILL, &ksa, &old_ksa);
    if (ret != 0) {
        log_raw("[lse_compat] ERROR: raw_rt_sigaction failed with code: ");
        log_hex((uint64_t)(int64_t)ret);
        log_raw("\n");
    } else {
        log_raw("[lse_compat] SUCCESS: SIGILL handler active in Linux kernel\n");
    }

    sigset_t unblock_set;
    sigemptyset(&unblock_set);
    sigaddset(&unblock_set, SIGILL);
    raw_rt_sigprocmask(1 /* SIG_UNBLOCK */, &unblock_set, NULL, sizeof(sigset_t));
}

/* ========================================================================= */
/* Native Android Bionic DNS Bridge for Go CGO and GLIBC                    */
/* ========================================================================= */
#include <netdb.h>
#include <sys/socket.h>

typedef int (*bionic_gai_t)(const char *, const char *, const struct addrinfo *, struct addrinfo **);
typedef void (*bionic_fai_t)(struct addrinfo *);

static bionic_gai_t s_bionic_gai = NULL;
static bionic_fai_t s_bionic_fai = NULL;
static bool s_dns_init = false;

static void init_bionic_dns(void) {
    if (s_dns_init) return;
    s_dns_init = true;
    void *h = dlopen("/system/lib64/libc.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h) {
        h = dlopen("libc.so", RTLD_NOW | RTLD_GLOBAL);
    }
    if (h) {
        s_bionic_gai = (bionic_gai_t)dlsym(h, "getaddrinfo");
        s_bionic_fai = (bionic_fai_t)dlsym(h, "freeaddrinfo");
        if (s_bionic_gai) {
            log_raw("[lse_compat] SUCCESS: Native Android Bionic DNS bridge active (/system/lib64/libc.so)\n");
        }
    }
}

__attribute__((visibility("default")))
int getaddrinfo(const char *node, const char *service, const struct addrinfo *hints, struct addrinfo **res) {
    init_bionic_dns();
    if (s_bionic_gai) {
        return s_bionic_gai(node, service, hints, res);
    }
    return EAI_SYSTEM;
}

__attribute__((visibility("default")))
void freeaddrinfo(struct addrinfo *res) {
    init_bionic_dns();
    if (s_bionic_fai) {
        s_bionic_fai(res);
    }
}
