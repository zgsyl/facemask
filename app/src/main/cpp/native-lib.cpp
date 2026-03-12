#include <jni.h>
#include <string>
#include <android/log.h>
#include "ncnn/net.h"

#define TAG "FaceMaskJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_facemask_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {

    // Log to logcat to verify execution
    LOGD("Hello from C++! NCNN header is successfully included.");
    
    std::string hello = "JNI Bridge: [Pass]";
    return env->NewStringUTF(hello.c_str());
}
