#include <adrenotools/driver.h>
#include <android/api-level.h>
#include <dlfcn.h>
#include <jni.h>

#include <atomic>
#include <mutex>
#include <string>

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

namespace {

struct TurnipLoader {
    std::mutex lock;
    void *handle = nullptr;
    std::atomic<PFN_vkGetInstanceProcAddr> getInstanceProcAddr{nullptr};
};

TurnipLoader& processLoader() {
    // The Vulkan instance and its function pointers can outlive the JNI call.
    // Keep the app-local driver open until process exit.
    static TurnipLoader loader;
    return loader;
}

std::string nativeDirectory(JNIEnv *env, jstring value) {
    if (!value) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) {
        return {};
    }
    std::string directory(chars);
    env->ReleaseStringUTFChars(value, chars);
    if (!directory.empty() && directory.back() != '/') {
        directory += '/';
    }
    return directory;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_network_midnight_vulkanbench_TurnipLoader_open(
    JNIEnv *env, jobject, jstring nativeDir, jstring driverDir) {
    if (android_get_device_api_level() < 30) {
        return JNI_FALSE;
    }
    auto& loader = processLoader();
    std::lock_guard guard(loader.lock);
    if (loader.handle) {
        return JNI_TRUE;
    }

    const auto hookDirectory = nativeDirectory(env, nativeDir);
    if (hookDirectory.empty()) {
        return JNI_FALSE;
    }
    const auto driverDirectory = nativeDirectory(env, driverDir);
    if (driverDirectory.empty()) {
        return JNI_FALSE;
    }

    auto *handle = adrenotools_open_libvulkan(
        RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr, hookDirectory.c_str(),
        driverDirectory.c_str(), "libvulkan_freedreno.so", nullptr, nullptr);
    if (!handle) {
        return JNI_FALSE;
    }
    const auto get = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
        dlsym(handle, "vkGetInstanceProcAddr"));
    if (!get) {
        dlclose(handle);
        return JNI_FALSE;
    }
    loader.handle = handle;
    loader.getInstanceProcAddr.store(get, std::memory_order_release);
    return JNI_TRUE;
}

extern "C" __attribute__((visibility("default"))) VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
vkGetInstanceProcAddr(VkInstance instance, const char *name) {
    const auto get = processLoader().getInstanceProcAddr.load(std::memory_order_acquire);
    return get ? get(instance, name) : nullptr;
}
