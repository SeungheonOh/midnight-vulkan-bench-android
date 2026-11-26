//! Android JNI bridge for `midnight-bench-core`.
//!
//! Exposes `init`, `call`, `cancel`, and `setProgressListener` to
//! `network.midnight.vulkanbench.core.NativeBridge`.

use std::sync::{Arc, RwLock};

use jni::{
    JNIEnv, JavaVM,
    objects::{GlobalRef, JClass, JObject, JString, JValue},
    sys::jstring,
};

struct ProgressTarget {
    vm: JavaVM,
    listener: GlobalRef,
}

static PROGRESS: RwLock<Option<Arc<ProgressTarget>>> = RwLock::new(None);

fn emit_progress(stage: &str) {
    let target = {
        let g = PROGRESS.read().unwrap_or_else(|e| e.into_inner());
        g.clone()
    };
    let Some(target) = target else { return };
    let Ok(mut env) = target.vm.attach_current_thread() else {
        return;
    };
    let Ok(jstr) = env.new_string(stage) else {
        let _ = env.exception_clear();
        return;
    };
    let res = env.call_method(
        target.listener.as_obj(),
        "onStage",
        "(Ljava/lang/String;)V",
        &[JValue::Object(&jstr)],
    );
    if res.is_err() {
        let _ = env.exception_clear();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_network_midnight_vulkanbench_core_NativeBridge_init<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    pipeline_cache_path: JString<'local>,
    arith_override: JString<'local>,
) {
    let cache = if pipeline_cache_path.is_null() {
        None
    } else {
        env.get_string(&pipeline_cache_path).ok().map(String::from)
    };
    let arith = if arith_override.is_null() {
        None
    } else {
        env.get_string(&arith_override).ok().map(String::from)
    };
    crate::init_runtime(cache.as_deref(), arith.as_deref());
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_network_midnight_vulkanbench_core_NativeBridge_call<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    request_json: JString<'local>,
) -> jstring {
    let request: String = match env.get_string(&request_json) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let mut cb = |stage: &str| emit_progress(stage);
    let reply = crate::call_json(&request, &mut cb);
    match env.new_string(reply) {
        Ok(s) => s.into_raw(),
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_network_midnight_vulkanbench_core_NativeBridge_cancel<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    crate::engine::cancel();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_network_midnight_vulkanbench_core_NativeBridge_setProgressListener<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    listener: JObject<'local>,
) {
    let next = if listener.is_null() {
        None
    } else {
        match (env.get_java_vm(), env.new_global_ref(listener)) {
            (Ok(vm), Ok(global)) => Some(Arc::new(ProgressTarget { vm, listener: global })),
            _ => None,
        }
    };
    *PROGRESS.write().unwrap_or_else(|e| e.into_inner()) = next;
}
