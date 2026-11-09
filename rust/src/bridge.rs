//! What Kotlin calls (`app.ciranda.android.Native`), and how events get back to it. Commands and
//! events are JSON, one string each way, so the interface and the engine share no types beyond
//! their field names.

use jni::JNIEnv;
use jni::JavaVM;
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use std::path::PathBuf;
use std::sync::{Arc, OnceLock};

static VM: OnceLock<JavaVM> = OnceLock::new();
static NATIVE: OnceLock<GlobalRef> = OnceLock::new();
/// The application's context, kept for as long as the process lives (the audio engine and WebRTC
/// hold it too).
static CONTEXT: OnceLock<GlobalRef> = OnceLock::new();

fn emit(json: String) {
    let (Some(vm), Some(class)) = (VM.get(), NATIVE.get()) else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_permanently() else {
        return;
    };
    let Ok(s) = env.new_string(json) else { return };
    let class: &JClass = class.as_obj().into();
    if env.call_static_method(class, "onEvent", "(Ljava/lang/String;)V", &[JValue::Object(&s)]).is_err() {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
    let _ = env.delete_local_ref(s);
}

fn init_logging() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("ciranda")
            .with_max_level(if cfg!(debug_assertions) { log::LevelFilter::Debug } else { log::LevelFilter::Info })
            .with_filter(
                android_logger::FilterBuilder::new()
                    .parse("info,ciranda=debug,libwebrtc=warn,tract_core=warn,tract_onnx=warn,tract_hir=warn")
                    .build(),
            ),
    );
    std::panic::set_hook(Box::new(|info| {
        log::error!("panic: {info}");
    }));
}

/// The platform's certificate checks, through rustls-platform-verifier's Kotlin component; it
/// takes its own version of the JNI bindings.
fn init_tls(vm: &JavaVM, context: &GlobalRef) {
    let raw_vm = vm.get_java_vm_pointer();
    let raw_context = context.as_obj().as_raw();
    // SAFETY: the process's JavaVM, valid for as long as the process.
    let vm22 = unsafe { jni22::JavaVM::from_raw(raw_vm as *mut _) };
    let done = vm22.attach_current_thread(|env| -> Result<(), jni22::errors::Error> {
        // SAFETY: a global reference this module keeps alive for the process's life.
        let context = unsafe { jni22::objects::JObject::from_raw(env, raw_context as _) };
        rustls_platform_verifier::android::init_with_env(env, context)
    });
    if let Err(e) = done {
        log::error!("certificate checks could not start: {e}");
    }
}

/// `Native.start(context, dataDir, cacheDir)`: once, before any command.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_ciranda_android_Native_start(
    mut env: JNIEnv,
    class: JClass,
    context: JObject,
    data_dir: JString,
    cache_dir: JString,
) {
    if VM.get().is_some() {
        return;
    }
    init_logging();
    let data_dir: String = env.get_string(&data_dir).map(Into::into).unwrap_or_default();
    let cache_dir: String = env.get_string(&cache_dir).map(Into::into).unwrap_or_default();
    // SAFETY: set before the engine's threads start; nothing in this process reads the
    // environment concurrently yet.
    unsafe { std::env::set_var("CIRANDA_DATA_DIR", &data_dir) };
    let Ok(vm) = env.get_java_vm() else { return };
    let Ok(native) = env.new_global_ref(&class) else {
        return;
    };
    let Ok(context) = env.new_global_ref(&context) else {
        return;
    };
    // SAFETY: the VM and a context that live as long as the process.
    unsafe { ndk_context::initialize_android_context(vm.get_java_vm_pointer().cast(), context.as_obj().as_raw().cast()) };
    libwebrtc::android::initialize_android_context(&vm, context.as_obj());
    init_tls(&vm, &context);
    let _ = VM.set(vm);
    let _ = NATIVE.set(native);
    let _ = CONTEXT.set(context);
    log::info!("Ciranda {} starting; data in {data_dir}", env!("CARGO_PKG_VERSION"));
    crate::engine::session::set_pictures_dir(PathBuf::from(&cache_dir).join("pictures"));
    crate::engine::start(PathBuf::from(cache_dir), Arc::new(emit));
}

/// `Native.command(json)`: anything the interface asks for.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_ciranda_android_Native_command(mut env: JNIEnv, _class: JClass, json: JString) {
    let Ok(text) = env.get_string(&json).map(String::from) else {
        return;
    };
    let Ok(cmd) = serde_json::from_str::<serde_json::Value>(&text) else {
        log::warn!("a command that is not JSON: {text}");
        return;
    };
    if let Some(h) = crate::engine::handle() {
        h.update(move |e| e.command(cmd));
    }
}
