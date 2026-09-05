#include "core/native_api.h"
#include "jni/jni_bridge.h"
#include "jni/jni_hooks.h"

namespace lspd::native::jni {
LSPD_DEF_NATIVE_METHOD(void, NativeAPI, recordNativeEntrypoint, jstring jstr) {
    lsplant::JUTFString str(env, jstr);
    lspd::native::RegisterNativeLib(str);
}

static JNINativeMethod gMethods[] = {
    LSPD_NATIVE_METHOD(NativeAPI, recordNativeEntrypoint, "(Ljava/lang/String;)V")};

void RegisterNativeApiBridge(JNIEnv *env) { REGISTER_LSPD_NATIVE_METHODS(NativeAPI); }

}  // namespace lspd::native::jni
