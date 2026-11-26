//! Process RSS/HWM and system memory snapshots from `/proc` on Android/Linux.

use std::{
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicU64, Ordering::Relaxed},
    },
    thread,
    time::Duration,
};

use serde_json::{Value, json};

#[cfg(any(target_os = "linux", target_os = "android"))]
mod platform {
    use std::fs;

    fn parse_proc_kb(contents: &str, key: &str) -> Option<u64> {
        for line in contents.lines() {
            if let Some(rest) = line.strip_prefix(key) {
                let kb = rest.split_whitespace().next()?.parse::<u64>().ok()?;
                return Some(kb.saturating_mul(1024));
            }
        }
        None
    }

    pub fn footprint() -> Option<(u64, u64)> {
        let s = fs::read_to_string("/proc/self/status").ok()?;
        let rss = parse_proc_kb(&s, "VmRSS:")?;
        let hwm = parse_proc_kb(&s, "VmHWM:").unwrap_or(rss);
        Some((rss, hwm))
    }

    pub fn available() -> Option<u64> {
        let s = fs::read_to_string("/proc/meminfo").ok()?;
        parse_proc_kb(&s, "MemAvailable:")
    }

    pub fn total_ram() -> Option<u64> {
        let s = fs::read_to_string("/proc/meminfo").ok()?;
        parse_proc_kb(&s, "MemTotal:")
    }

    pub fn return_free_pages() {
        #[cfg(target_os = "android")]
        {
            // Android Bionic M_PURGE = -101 (scudo purge).
            unsafe extern "C" {
                fn mallopt(param: libc::c_int, value: libc::c_int) -> libc::c_int;
            }
            let _ = unsafe { mallopt(-101, 0) };
        }
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
mod platform {
    pub fn footprint() -> Option<(u64, u64)> {
        None
    }
    pub fn available() -> Option<u64> {
        None
    }
    pub fn total_ram() -> Option<u64> {
        None
    }
    pub fn return_free_pages() {}
}

pub fn footprint() -> Option<(u64, u64)> {
    platform::footprint()
}

pub fn available() -> Option<u64> {
    platform::available()
}

pub fn total_ram() -> Option<u64> {
    platform::total_ram()
}

pub fn return_free_pages() {
    platform::return_free_pages();
}

pub fn snapshot() -> Value {
    let (now, peak) = footprint().unwrap_or((0, 0));
    json!({
        "footprint_bytes": now,
        "lifetime_peak_footprint_bytes": peak,
        "available_bytes": available(),
        "total_ram_bytes": total_ram(),
    })
}

pub struct PeakSampler {
    stop: Arc<AtomicBool>,
    peak: Arc<AtomicU64>,
    thread: Option<thread::JoinHandle<()>>,
}

impl PeakSampler {
    pub fn start() -> Self {
        let stop = Arc::new(AtomicBool::new(false));
        let peak = Arc::new(AtomicU64::new(footprint().map_or(0, |f| f.0)));
        let (s, p) = (stop.clone(), peak.clone());
        let thread = thread::Builder::new()
            .name("bench-footprint".into())
            .spawn(move || {
                while !s.load(Relaxed) {
                    if let Some((now, _)) = footprint() {
                        p.fetch_max(now, Relaxed);
                    }
                    thread::sleep(Duration::from_millis(10));
                }
            })
            .ok();
        PeakSampler { stop, peak, thread }
    }

    pub fn finish(mut self) -> u64 {
        self.stop.store(true, Relaxed);
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
        if let Some((now, _)) = footprint() {
            self.peak.fetch_max(now, Relaxed);
        }
        self.peak.load(Relaxed)
    }
}

impl Drop for PeakSampler {
    fn drop(&mut self) {
        self.stop.store(true, Relaxed);
    }
}
