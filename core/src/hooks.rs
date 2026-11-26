//! Process-wide GPU hooks with a runtime CPU-only toggle for A/B benchmarking.

use std::{
    ffi::c_void,
    sync::{
        Once,
        atomic::{AtomicBool, Ordering::SeqCst},
    },
};

use midnight_proofs::gpu::{
    self as gpu,
    adapter::{G1AffineLimbs, G1JacobianLimbs, Scalar},
};
use midnight_vulkan as vulkan;

static CPU_ONLY: AtomicBool = AtomicBool::new(true);

fn msm_g1(scalars: &[Scalar], bases: &[G1AffineLimbs]) -> Option<G1JacobianLimbs> {
    if CPU_ONLY.load(SeqCst) {
        return None;
    }
    vulkan::msm_g1(scalars, bases)
}

fn fft(a: &mut [Scalar], omega: Scalar, log_n: u32) -> bool {
    if CPU_ONLY.load(SeqCst) {
        return false;
    }
    vulkan::fft(a, omega, log_n)
}

fn ifft(a: &mut [Scalar], omega_inv: Scalar, log_n: u32, divisor: Scalar) -> bool {
    if CPU_ONLY.load(SeqCst) {
        return false;
    }
    vulkan::ifft(a, omega_inv, log_n, divisor)
}

/// # Safety
/// Caller passes a valid quotient job per `midnight_vulkan::quotient`.
unsafe fn quotient(job: *const c_void, out: &mut [Scalar]) -> bool {
    if CPU_ONLY.load(SeqCst) {
        return false;
    }
    // SAFETY: forwarded from midnight-proofs.
    unsafe { vulkan::quotient(job, out) }
}

pub fn install() -> bool {
    static ONCE: Once = Once::new();
    static OURS: AtomicBool = AtomicBool::new(false);
    ONCE.call_once(|| {
        let ours = gpu::install(gpu::GpuHooks {
            msm_g1: Some(msm_g1),
            fft: Some(fft),
            ifft: Some(ifft),
            quotient: Some(quotient),
        });
        OURS.store(ours, SeqCst);
    });
    OURS.load(SeqCst)
}

pub fn set_cpu_only(on: bool) {
    CPU_ONLY.store(on, SeqCst);
}
