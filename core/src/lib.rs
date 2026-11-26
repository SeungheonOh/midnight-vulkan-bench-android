#![recursion_limit = "256"]
//! Midnight Vulkan Prover Benchmark Core for Android.
//!
//! Exposes a JSON command API over JNI (`jni.rs`) that probes Vulkan GPU
//! capabilities, runs GPU kernel smoke tests, and benchmarks CPU vs Vulkan
//! proving across multiple circuits and sizes.

use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    sync::Once,
};

use midnight_vulkan as vulkan;
use serde_json::{Value, json};

pub mod circuits;
pub mod engine;
mod hooks;
#[cfg(target_os = "android")]
mod jni;
pub mod keys;
pub mod memory;
pub mod rc1_chain;

pub use engine::Backend;

/// Initializes runtime settings BEFORE the first Vulkan context or rayon call:
/// - sets `MIDNIGHT_VULKAN_PIPELINE_CACHE` into the app's cache dir
/// - optionally forces `MIDNIGHT_VULKAN_ARITH=32` or `64`
/// - optionally selects a Vulkan loader owned by the app
/// - configures the global rayon pool with an 8 MiB stack size (Android's
///   default thread stack is 1 MiB, which is too small for deep PLONK recursion)
pub fn init_runtime(
    pipeline_cache_path: Option<&str>,
    arith_override: Option<&str>,
    vulkan_loader_path: Option<&str>,
) {
    static INIT: Once = Once::new();
    INIT.call_once(|| {
        if let Some(p) = pipeline_cache_path.filter(|s| !s.is_empty()) {
            // SAFETY: called once at app startup before spawning worker threads.
            unsafe { std::env::set_var("MIDNIGHT_VULKAN_PIPELINE_CACHE", p) };
        }
        if let Some(a) = arith_override.filter(|s| *s == "32" || *s == "64") {
            // SAFETY: called once at app startup before spawning worker threads.
            unsafe { std::env::set_var("MIDNIGHT_VULKAN_ARITH", a) };
        }
        if let Some(path) = vulkan_loader_path.filter(|s| !s.is_empty()) {
            // SAFETY: called once at app startup before spawning worker threads.
            unsafe { std::env::set_var(vulkan::device::LOADER_ENV, path) };
        }
        let _ = rayon::ThreadPoolBuilder::new()
            .stack_size(8 * 1024 * 1024)
            .build_global();
    });
}

fn vendor_name(vendor_id: u32) -> &'static str {
    match vendor_id {
        0x5143 => "Qualcomm (Adreno)",
        0x13B5 => "ARM (Mali / Immortalis)",
        0x144D => "Samsung (Xclipse)",
        0x1010 => "Imagination (PowerVR)",
        0x1002 => "AMD",
        0x10DE => "NVIDIA",
        0x8086 => "Intel",
        0x106B => "Apple",
        0x1AE0 => "Google",
        _ => "Unknown",
    }
}

fn cpu_cluster_max_khz() -> Vec<u64> {
    let mut freqs = Vec::new();
    for cpu in 0..16 {
        let p = format!("/sys/devices/system/cpu/cpu{cpu}/cpufreq/cpuinfo_max_freq");
        if let Ok(s) = std::fs::read_to_string(&p)
            && let Ok(v) = s.trim().parse::<u64>()
        {
            freqs.push(v);
        }
    }
    freqs
}

pub fn device_info() -> Value {
    let info = vulkan::present();
    let usable = vulkan::device();
    let variant = vulkan::variant().map(|v| match v {
        vulkan::Variant::Int64 => "int64",
        vulkan::Variant::Int32 => "int32",
    });
    let t = vulkan::thresholds();
    let mem = vulkan::mem_info();
    json!({
        "ok": true,
        "vulkan_built": vulkan::vulkan_built(),
        "present": info.is_some(),
        "usable": usable,
        "variant": variant,
        "arith_env": std::env::var("MIDNIGHT_VULKAN_ARITH").ok(),
        "pipeline_cache_path": std::env::var("MIDNIGHT_VULKAN_PIPELINE_CACHE").ok(),
        "context_error": vulkan::ctx::context_error(),
        "name": info.map(|i| i.name.clone()),
        "vendor_id": info.map(|i| i.vendor_id),
        "vendor_name": info.map(|i| vendor_name(i.vendor_id)),
        "device_id": info.map(|i| i.device_id),
        "device_type": info.map(|i| format!("{:?}", i.device_type)),
        "api_version": info.map(|i| format!("{}.{}.{}", i.api_version.0, i.api_version.1, i.api_version.2)),
        "driver": info.map(|i| i.driver.trim().to_string()),
        "unified_memory": info.map(|i| i.unified_memory),
        "device_local_heap_bytes": info.map(|i| i.device_local_heap),
        "max_buffer_length_bytes": info.map(|i| i.max_buffer_len()),
        "max_memory_allocation_size_bytes": info.map(|i| i.max_memory_allocation_size),
        "max_buffer_size_bytes": info.map(|i| i.max_buffer_size),
        "max_compute_work_group_invocations": info.map(|i| i.max_compute_work_group_invocations),
        "max_compute_work_group_size": info.map(|i| i.max_compute_work_group_size),
        "max_compute_shared_memory_bytes": info.map(|i| i.max_compute_shared_memory_size),
        "subgroup_size": info.map(|i| i.subgroup_size),
        "min_subgroup_size": info.map(|i| i.min_subgroup_size),
        "max_subgroup_size": info.map(|i| i.max_subgroup_size),
        "subgroup_size_control": info.map(|i| i.subgroup_size_control),
        "compute_full_subgroups": info.map(|i| i.compute_full_subgroups),
        "shader_int64": info.map(|i| i.shader_int64),
        "shader_int16": info.map(|i| i.shader_int16),
        "buffer_device_address": info.map(|i| i.buffer_device_address),
        "timestamp_valid_bits": info.map(|i| i.timestamp_valid_bits),
        "timestamp_period_ns": info.map(|i| i.timestamp_period),
        "memory_budget_ext": info.map(|i| i.memory_budget_ext),
        "gpu_free_bytes": mem.map(|m| m.0),
        "gpu_budget_bytes": mem.map(|m| m.1),
        "thresholds": {
            "fft": t.fft,
            "ifft": t.ifft,
            "msm_table": t.msm_table,
            "msm_one_shot": t.msm_one_shot,
            "quotient_ext": t.quotient,
            "explicit": t.explicit,
        },
        "cpu_threads": rayon::current_num_threads(),
        "cpu_cores": std::thread::available_parallelism().map(|n| n.get()).ok(),
        "cpu_cluster_max_khz": cpu_cluster_max_khz(),
        "memory": memory::snapshot(),
        "prover_rev": option_env!("MIDNIGHT_BENCH_PROVER_REV")
            .unwrap_or("4e31451de322882d75776ff68fe29c03a9d3e8bc"),
    })
}

pub fn circuits_json() -> Value {
    json!({
        "ok": true,
        "circuits": circuits::ALL.map(|c| c.describe()),
    })
}

pub fn call_json(request_json: &str, progress: &mut dyn FnMut(&str)) -> String {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let req: Value = match serde_json::from_str(request_json) {
            Ok(v) => v,
            Err(e) => return json!({ "ok": false, "error": format!("invalid json: {e}") }),
        };
        let op = req["op"].as_str().unwrap_or("");
        match op {
            "device_info" => device_info(),
            "circuits" => circuits_json(),
            "smoke" => engine::smoke_test(progress),
            "prepare" => {
                let Some(circuit) = req["circuit"].as_str().and_then(circuits::Circuit::parse) else {
                    return json!({ "ok": false, "error": format!("unknown circuit: {}", req["circuit"]) });
                };
                let Some(k) = req["k"].as_u64().map(|v| v as u32) else {
                    return json!({ "ok": false, "error": "missing k" });
                };
                engine::prepare(circuit, k, progress).unwrap_or_else(|s| s.to_json())
            }
            "prove" => {
                let Some(circuit) = req["circuit"].as_str().and_then(circuits::Circuit::parse) else {
                    return json!({ "ok": false, "error": format!("unknown circuit: {}", req["circuit"]) });
                };
                let Some(k) = req["k"].as_u64().map(|v| v as u32) else {
                    return json!({ "ok": false, "error": "missing k" });
                };
                let Some(backend) = req["backend"].as_str().and_then(Backend::parse) else {
                    return json!({ "ok": false, "error": format!("unknown backend: {}", req["backend"]) });
                };
                engine::prove(circuit, k, backend, progress).unwrap_or_else(|s| s.to_json())
            }
            "release" => {
                let mut r = engine::release();
                r["ok"] = json!(true);
                r
            }
            "memory" => {
                let mut r = memory::snapshot();
                r["ok"] = json!(true);
                r
            }
            _ => json!({ "ok": false, "error": format!("unknown op: {op:?}") }),
        }
    }))
    .unwrap_or_else(|e| {
        let msg = e
            .downcast_ref::<&str>()
            .map(|s| s.to_string())
            .or_else(|| e.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "panic".into());
        json!({ "ok": false, "error": format!("panic: {msg}") })
    });
    result.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn call(req: Value) -> Value {
        let mut stages = Vec::new();
        let raw = call_json(&req.to_string(), &mut |s| stages.push(s.to_string()));
        serde_json::from_str(&raw).expect("valid JSON response")
    }

    #[test]
    fn end_to_end_json_api_all_circuits() {
        let info = call(json!({ "op": "device_info" }));
        assert_eq!(info["ok"], true);
        let usable = info["usable"].as_bool().unwrap_or(false);

        let circuits = call(json!({ "op": "circuits" }));
        assert_eq!(circuits["ok"], true);
        assert_eq!(circuits["circuits"].as_array().unwrap().len(), 3);

        if usable {
            let smoke = call(json!({ "op": "smoke" }));
            assert_eq!(smoke["ok"], true, "smoke failed: {smoke}");
        }

        for (cid, k) in [
            ("chain", 14u32),
            ("poseidon-chain", 13u32),
            ("poseidon-preimage", 13u32),
        ] {
            let prep = call(json!({ "op": "prepare", "circuit": cid, "k": k }));
            assert_eq!(prep["ok"], true, "prepare {cid}-k{k} failed: {prep}");

            let cpu = call(json!({ "op": "prove", "circuit": cid, "k": k, "backend": "cpu" }));
            assert_eq!(cpu["ok"], true, "cpu prove {cid}-k{k} failed: {cpu}");
            assert_eq!(cpu["verified"], true, "cpu verify {cid}-k{k} failed: {cpu}");

            if usable {
                let vk =
                    call(json!({ "op": "prove", "circuit": cid, "k": k, "backend": "vulkan" }));
                assert_eq!(vk["ok"], true, "vulkan prove {cid}-k{k} failed: {vk}");
                assert_eq!(vk["verified"], true, "vulkan verify {cid}-k{k} failed: {vk}");
                assert_eq!(
                    vk["vulkan"]["erase"]["ok"], true,
                    "vulkan erase {cid}-k{k} failed: {vk}"
                );
            }
        }

        // Verify SRS reuse on same-k circuit switch
        let prep1 = call(json!({ "op": "prepare", "circuit": "poseidon-chain", "k": 13 }));
        assert_eq!(prep1["ok"], true);
        assert_eq!(prep1["srs_reused"], true, "expected cached k=13 SRS reuse");

        let rel = call(json!({ "op": "release" }));
        assert_eq!(rel["ok"], true);
    }
}
