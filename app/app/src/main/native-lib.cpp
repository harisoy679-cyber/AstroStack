#include <jni.h>
#include <string>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_astrostack_MainActivity_nativeGetEngineStatus(
        JNIEnv* env,
        jobject /* this */) {

    std::string status = "AstroStack Native Engine Ready";

    return env->NewStringUTF(status.c_str());
}
