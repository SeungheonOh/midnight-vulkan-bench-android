//! Deterministic on-device SRS/keygen, Vulkan/CPU proving, verification,
//! erasure reporting, and low-level GPU smoke diagnostics.

use std::{
    sync::{
        Mutex, MutexGuard,
        atomic::{AtomicBool, Ordering::SeqCst},
    },
    time::Instant,
};

use ff::{Field, PrimeField};
use group::{Curve, Group, prime::PrimeCurveAffine};
use midnight_curves::{Bls12, CurveAffine, Fq, G1Affine, G1Projective};
use midnight_proofs::{
    gpu::{self as gpu, adapter},
    poly::{
        Coeff, LagrangeCoeff, LagrangeDeltaCoeff, LagrangeDoubleDeltaCoeff, kzg::params::ParamsKZG,
    },
};
use midnight_vulkan as vulkan;
use rand::SeedableRng;
use rand_chacha::ChaCha20Rng;
use serde_json::{Value, json};

use crate::{
    circuits::Circuit,
    hooks,
    keys::{self, Proving},
    memory::{self, PeakSampler},
};

pub type Progress<'a> = &'a mut dyn FnMut(&str);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Backend {
    Cpu,
    Vulkan,
}

impl Backend {
    pub fn parse(s: &str) -> Option<Backend> {
        match s {
            "cpu" => Some(Backend::Cpu),
            "vulkan" | "gpu" => Some(Backend::Vulkan),
            _ => None,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Backend::Cpu => "cpu",
            Backend::Vulkan => "vulkan",
        }
    }
}

static CANCEL: AtomicBool = AtomicBool::new(false);

pub fn cancel() {
    CANCEL.store(true, SeqCst);
}

fn cancelled() -> bool {
    CANCEL.load(SeqCst)
}

pub struct Stop {
    stage: &'static str,
    error: Option<String>,
}

impl Stop {
    fn error(stage: &'static str, e: impl std::fmt::Display) -> Stop {
        Stop {
            stage,
            error: Some(e.to_string()),
        }
    }

    pub fn to_json(&self) -> Value {
        match &self.error {
            Some(e) => json!({ "ok": false, "stage": self.stage, "error": e }),
            None => json!({ "ok": false, "stage": self.stage, "cancelled": true }),
        }
    }
}

fn checkpoint(stage: &'static str) -> Result<(), Stop> {
    if cancelled() {
        Err(Stop { stage, error: None })
    } else {
        Ok(())
    }
}

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1e3
}

// ---------------------------------------------------------------------------
// Cached SRS (reused across circuits of the same `k`) + active proving key

struct CachedSrs {
    // Handles drop before `params` they reference.
    handles: Option<[vulkan::SrsHandle; 4]>,
    k: u32,
    params: ParamsKZG<Bls12>,
}

struct ActiveCircuit {
    // Key handle drops before `keys`.
    _key_handle: Option<vulkan::KeyHandle>,
    circuit: Circuit,
    k: u32,
    keys: Box<dyn Proving>,
    report: Value,
}

struct EngineState {
    // Active circuit drops before SRS.
    active: Option<ActiveCircuit>,
    srs: Option<CachedSrs>,
}

static STATE: Mutex<EngineState> = Mutex::new(EngineState {
    active: None,
    srs: None,
});

fn state() -> MutexGuard<'static, EngineState> {
    STATE.lock().unwrap_or_else(|e| e.into_inner())
}

pub fn vulkan_usable() -> Result<(), String> {
    if !hooks::install() {
        return Err("another set of accelerator hooks was installed first".into());
    }
    if !vulkan::vulkan_built() {
        return Err("midnight-vulkan was built without embedded SPIR-V".into());
    }
    if vulkan::present().is_none() {
        return Err(format!(
            "no usable Vulkan device: {}",
            vulkan::ctx::context_error().unwrap_or("unknown")
        ));
    }
    if !vulkan::device() {
        return Err(format!(
            "the Vulkan context could not be made: {}",
            vulkan::ctx::context_error().unwrap_or("unknown")
        ));
    }
    Ok(())
}

fn register_srs(params: &ParamsKZG<Bls12>) -> (Option<[vulkan::SrsHandle; 4]>, f64, Value) {
    if let Err(e) = vulkan_usable() {
        return (None, 0.0, json!(e));
    }
    let t = Instant::now();
    let handles = [
        params.bases::<Coeff>(),
        params.bases::<LagrangeCoeff>(),
        params.bases::<LagrangeDeltaCoeff>(),
        params.bases::<LagrangeDoubleDeltaCoeff>(),
    ]
    .map(|b| vulkan::register_srs(adapter::affine(b)));
    (Some(handles), ms(t), Value::Null)
}

fn register_key(keys: &dyn Proving) -> (Option<vulkan::KeyHandle>, f64, Value) {
    if let Err(e) = vulkan_usable() {
        return (None, 0.0, json!(e));
    }
    let t = Instant::now();
    let (handle, note) = match keys.pk().gpu_resident_polys() {
        Some(polys) => (
            Some(vulkan::register_key(
                &polys
                    .iter()
                    .map(|p| adapter::scalars(p))
                    .collect::<Vec<_>>(),
            )),
            Value::Null,
        ),
        None => (
            None,
            json!("quotient job does not take this key; quotient runs on CPU"),
        ),
    };
    (handle, ms(t), note)
}

fn release_locked(st: &mut EngineState) -> Value {
    let circuit_dropped = st.active.take().is_some();
    let srs_dropped = st.srs.take().is_some();
    let key_bytes = vulkan::evict_key_copies();
    let pool_bytes = vulkan::release_pool();
    memory::return_free_pages();
    json!({
        "circuit_released": circuit_dropped,
        "srs_released": srs_dropped,
        "key_copy_bytes_released": key_bytes,
        "pool_bytes_released": pool_bytes,
        "vulkan_buffer_bytes_now": vulkan::device_bytes().0,
        "vulkan_srs_tables": vulkan::srs_handles(),
        "vulkan_keys_registered": vulkan::keys_registered(),
        "memory": memory::snapshot(),
    })
}

pub fn prepare(circuit: Circuit, k: u32, progress: Progress) -> Result<Value, Stop> {
    CANCEL.store(false, SeqCst);
    let mut st = state();

    if let Some(a) = st.active.as_ref()
        && a.circuit == circuit
        && a.k == k
    {
        let mut r = a.report.clone();
        r["reused"] = json!(true);
        progress("done");
        return Ok(r);
    }

    let (k_min, k_max) = circuit.k_range();
    if !(k_min..=k_max).contains(&k) {
        return Err(Stop::error(
            "srs",
            format!("{} supports k from {k_min} to {k_max}", circuit.id()),
        ));
    }

    // Drop previous circuit key first to free RAM before building next one.
    st.active = None;
    let _ = vulkan::evict_key_copies();

    let srs_reused = st.srs.as_ref().is_some_and(|s| s.k == k);
    let mut srs_ms = 0.0;
    let mut srs_register_ms = 0.0;
    let mut srs_note = Value::Null;

    let sampler = PeakSampler::start();

    if !srs_reused {
        // Free old SRS + Vulkan scratch pool before allocating a different `k`.
        st.srs = None;
        let _ = vulkan::release_pool();
        memory::return_free_pages();

        checkpoint("srs")?;
        progress("srs");
        let t = Instant::now();
        let rng = ChaCha20Rng::seed_from_u64(0x4d49_444e_4947_4854u64 ^ u64::from(k));
        let params = ParamsKZG::<Bls12>::unsafe_setup(k, rng);
        srs_ms = ms(t);

        checkpoint("register-srs")?;
        progress("register-srs");
        let (handles, reg_ms, note) = register_srs(&params);
        srs_register_ms = reg_ms;
        srs_note = note;
        st.srs = Some(CachedSrs { handles, k, params });
    }

    checkpoint("keygen")?;
    progress("keygen");
    let t = Instant::now();
    let (keys, keygen) = {
        let srs = st.srs.as_ref().expect("SRS initialized");
        keys::keygen(circuit, k, &srs.params).map_err(|e| Stop::error("keygen", e))?
    };
    let keygen_ms = ms(t);

    checkpoint("register-key")?;
    progress("register-key");
    let (key_handle, key_register_ms, key_note) = register_key(keys.as_ref());
    let registered = st.srs.as_ref().is_some_and(|s| s.handles.is_some());
    let peak = sampler.finish();

    let report = json!({
        "ok": true,
        "circuit": circuit.id(),
        "k": k,
        "srs_reused": srs_reused,
        "srs_ms": srs_ms,
        "srs_register_ms": srs_register_ms,
        "keygen_ms": keygen_ms,
        "keygen": keygen,
        "key_register_ms": key_register_ms,
        "total_prepare_ms": srs_ms + srs_register_ms + keygen_ms + key_register_ms,
        "vulkan_registered": registered,
        "srs_note": srs_note,
        "key_note": key_note,
        "peak_footprint_bytes": peak,
        "memory": memory::snapshot(),
    });

    st.active = Some(ActiveCircuit {
        _key_handle: key_handle,
        circuit,
        k,
        keys,
        report: report.clone(),
    });
    progress("done");
    Ok(report)
}

fn vulkan_report(erase: Option<Result<vulkan::WipeStats, vulkan::WipeStats>>) -> Value {
    let s = vulkan::stats();
    let h = vulkan::host_times();
    let class = |c: vulkan::ClassStats| {
        json!({ "calls": c.served, "device_ms": c.device_nanos as f64 / 1e6 })
    };
    let declines: Vec<Value> = vulkan::declines()
        .into_iter()
        .map(|(c, r, n)| json!({ "class": c, "reason": r, "count": n }))
        .collect();
    let hook_declines: Vec<Value> = gpu::hook_declines()
        .into_iter()
        .map(|(c, r, n)| json!({ "class": c, "reason": r, "count": n }))
        .collect();
    let erase_val = erase.map(|r| {
        let ok = r.is_ok();
        let w = r.unwrap_or_else(|w| w);
        json!({
            "ok": ok,
            "regions": w.regions,
            "bytes": w.bytes,
            "checked": w.checked,
            "checked_regions": w.checked_regions,
            "nonzero_regions": w.nonzero_regions,
            "check_failures": w.check_failures,
            "pool_regions": w.pool_regions,
            "erasure_failures": w.erasure_failures,
        })
    });
    let pipelines: Vec<Value> = vulkan::pipeline_times()
        .into_iter()
        .map(|(name, wg, d)| {
            json!({
                "kernel": name,
                "workgroup": wg,
                "creation_ms": d.as_secs_f64() * 1e3,
            })
        })
        .collect();
    let (bytes_now, _) = vulkan::device_bytes();
    let total_gpu_ms =
        (s.msm.device_nanos + s.fft.device_nanos + s.ifft.device_nanos + s.quotient.device_nanos)
            as f64
            / 1e6;
    json!({
        "calls": {
            "msm": class(s.msm),
            "fft": class(s.fft),
            "ifft": class(s.ifft),
            "quotient": class(s.quotient),
        },
        "total_calls": s.msm.served + s.fft.served + s.ifft.served + s.quotient.served,
        "total_gpu_ms": total_gpu_ms,
        "host_times": {
            "copy_ms": h.copy_nanos as f64 / 1e6,
            "zero_ms": h.zero_nanos as f64 / 1e6,
            "zero_background_ms": h.zero_background_nanos as f64 / 1e6,
            "run_ms": h.run_nanos as f64 / 1e6,
            "run_gpu_ms": h.run_gpu_nanos as f64 / 1e6,
            "runs": h.runs,
        },
        "declines": declines,
        "hook_declines": hook_declines,
        "request_peak_device_bytes": erase_val.is_some().then(vulkan::request_peak_bytes),
        "device_bytes_now": bytes_now,
        "pool_bytes": vulkan::pool_bytes(),
        "erase": erase_val,
        "pipeline_count": pipelines.len(),
        "pipeline_total_creation_ms": pipelines.iter().filter_map(|p| p["creation_ms"].as_f64()).sum::<f64>(),
    })
}

pub fn prove(
    circuit: Circuit,
    k: u32,
    backend: Backend,
    progress: Progress,
) -> Result<Value, Stop> {
    CANCEL.store(false, SeqCst);
    let st = state();
    let Some(active) = st
        .active
        .as_ref()
        .filter(|a| a.circuit == circuit && a.k == k)
    else {
        return Err(Stop::error(
            "prove",
            format!("{} at k = {k} is not prepared", circuit.id()),
        ));
    };
    let Some(srs) = st.srs.as_ref().filter(|s| s.k == k) else {
        return Err(Stop::error("prove", format!("SRS for k = {k} is missing")));
    };
    if backend == Backend::Vulkan {
        if let Err(e) = vulkan_usable() {
            return Err(Stop::error("prove", format!("Vulkan is not usable: {e}")));
        }
        if srs.handles.is_none() {
            return Err(Stop::error(
                "prove",
                "SRS is not registered with Vulkan",
            ));
        }
    }

    checkpoint("prove")?;
    progress("prove");
    hooks::set_cpu_only(backend == Backend::Cpu);
    vulkan::reset_stats();
    gpu::reset_hook_declines();
    let before = memory::footprint().map_or(0, |f| f.0);
    let sampler = PeakSampler::start();
    if backend == Backend::Vulkan {
        vulkan::begin_request();
    }
    let t = Instant::now();
    let proof = active.keys.prove(&srs.params);
    let prove_ms = ms(t);
    let erase = (backend == Backend::Vulkan).then(|| vulkan::end_request_report(true));
    hooks::set_cpu_only(true);
    let peak = sampler.finish();
    let vk = vulkan_report(erase);
    let proof = proof.map_err(|e| Stop::error("prove", e))?;

    checkpoint("verify")?;
    progress("verify");
    let t = Instant::now();
    let verified = active.keys.verify(&srs.params, &proof);
    let verify_ms = ms(t);
    progress("done");

    Ok(json!({
        "ok": true,
        "circuit": circuit.id(),
        "k": k,
        "backend": backend.name(),
        "prove_ms": prove_ms,
        "verify_ms": verify_ms,
        "verified": verified.is_ok(),
        "verify_error": verified.err(),
        "proof_bytes": proof.len(),
        "threads": rayon::current_num_threads(),
        "vulkan": vk,
        "memory": {
            "before_prove_footprint_bytes": before,
            "prove_peak_footprint_bytes": peak,
            "after": memory::snapshot(),
        },
    }))
}

pub fn release() -> Value {
    let mut st = state();
    release_locked(&mut st)
}

/// Runs low-level GPU self-tests (`fill`, `fft`, `msm`) and compares against CPU.
/// Never panics or aborts; reports per-step pass/fail/timings in JSON.
pub fn smoke_test(progress: Progress) -> Value {
    if let Err(e) = vulkan_usable() {
        return json!({ "ok": false, "error": e, "steps": [] });
    }
    let ctx = match vulkan::ctx::context() {
        Ok(c) => c,
        Err(e) => return json!({ "ok": false, "error": format!("{e:?}"), "steps": [] }),
    };
    let d = ctx.device();
    let mut steps = Vec::new();
    let mut all_ok = true;

    // 1. Buffer fill + readback
    progress("smoke-fill");
    let t = Instant::now();
    let fill_res: Result<(), String> = (|| {
        let buf = d.alloc(4096).map_err(|e| format!("alloc: {e:?}"))?;
        d.fill(&buf, 0, None, 0xdead_beef)
            .map_err(|e| format!("fill: {e:?}"))?;
        let ok = d
            .read_chunks(&buf, |part| {
                part.chunks_exact(4)
                    .all(|w| u32::from_ne_bytes(w.try_into().unwrap()) == 0xdead_beef)
            })
            .map_err(|e| format!("read: {e:?}"))?;
        if !ok {
            return Err("readback pattern mismatch".into());
        }
        Ok(())
    })();
    let fill_ok = fill_res.is_ok();
    all_ok &= fill_ok;
    steps.push(json!({
        "name": "buffer_fill_readback",
        "ok": fill_ok,
        "ms": ms(t),
        "error": fill_res.err(),
    }));

    // 2. FFT k=15 exact match vs CPU (uses production thresholds untouched)
    progress("smoke-fft");
    let t = Instant::now();
    let fft_res: Result<(), String> = (|| {
        let log_n = 15u32;
        let n = 1usize << log_n;
        let mut omega = Fq::ROOT_OF_UNITY;
        for _ in log_n..Fq::S {
            omega = omega.square();
        }
        let mut gpu_vals: Vec<Fq> = (0..n).map(|i| Fq::from(i as u64 + 1)).collect();
        let mut cpu_vals = gpu_vals.clone();
        midnight_curves::fft::best_fft(&mut cpu_vals, omega, log_n);

        vulkan::reset_stats();
        let ok = vulkan::fft(
            adapter::scalars_mut(&mut gpu_vals),
            adapter::scalar(omega),
            log_n,
        );
        if !ok {
            return Err(format!("vulkan::fft declined: {:?}", vulkan::declines()));
        }
        if gpu_vals != cpu_vals {
            return Err("FFT output mismatch vs CPU".into());
        }
        Ok(())
    })();
    let fft_ok = fft_res.is_ok();
    all_ok &= fft_ok;
    steps.push(json!({
        "name": "fft_k15_vs_cpu",
        "ok": fft_ok,
        "ms": ms(t),
        "error": fft_res.err(),
    }));

    // 3. MSM k=15 (32,768 points) exact match vs CPU
    progress("smoke-msm-32768");
    let t = Instant::now();
    let msm_res: Result<(), String> = (|| {
        let n = 1usize << 15;
        let g = G1Projective::generator();
        let mut bases_proj = Vec::with_capacity(n);
        let mut cur = g;
        for _ in 0..n {
            bases_proj.push(cur);
            cur += g;
        }
        let mut bases_aff = vec![G1Affine::identity(); n];
        <G1Projective as Curve>::batch_normalize(&bases_proj, &mut bases_aff);
        let scalars: Vec<Fq> = (1..=n).map(|i| Fq::from(i as u64 * 17 + 3)).collect();
        let cpu_res = G1Projective::multi_exp(&bases_proj, &scalars);

        vulkan::reset_stats();
        let _h = vulkan::register_srs(adapter::affine(&bases_aff));
        let got = vulkan::msm_g1(adapter::scalars(&scalars), adapter::affine(&bases_aff))
            .ok_or_else(|| format!("vulkan::msm_g1 declined: {:?}", vulkan::declines()))?;
        let got_proj = adapter::from_jacobian(got);
        if got_proj != cpu_res {
            let on_curve = bool::from(got_proj.to_affine().is_on_curve());
            return Err(format!("MSM n={n} mismatch vs CPU (on_curve={on_curve})"));
        }
        Ok(())
    })();
    let msm_ok = msm_res.is_ok();
    all_ok &= msm_ok;
    steps.push(json!({
        "name": "msm_k15_vs_cpu",
        "ok": msm_ok,
        "ms": ms(t),
        "error": msm_res.err(),
    }));

    let _ = vulkan::release_pool();
    progress("done");

    json!({
        "ok": all_ok,
        "device": d.info().name,
        "variant": format!("{:?}", ctx.variant()),
        "steps": steps,
    })
}
