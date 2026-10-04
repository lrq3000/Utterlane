#pragma once
#include <jni.h>
#include <string>

namespace {
void error(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
// JNI NewStringUTF takes modified UTF-8, not model text's standard UTF-8.
jstring utf8(JNIEnv* env, const char* text) {
    const std::string value(text ? text : "");
    auto bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte*>(value.data()));
    auto encoding = env->NewStringUTF("UTF-8");
    auto cls = env->FindClass("java/lang/String");
    auto result = static_cast<jstring>(env->NewObject(cls, env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V"), bytes, encoding));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(encoding); env->DeleteLocalRef(cls);
    return result;
}
}
