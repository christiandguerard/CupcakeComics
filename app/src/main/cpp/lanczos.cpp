#include <jni.h>
#include <cmath>
#include <algorithm>
#include <vector>

extern "C" {

JNIEXPORT jfloatArray JNICALL
Java_com_cupcakecomics_reader_gl_NativeImageBridge_nativeLanczosKernel(
        JNIEnv *env, jobject /* thiz */, jint samples) {
    if (samples < 2) samples = 2;
    if (samples > 512) samples = 512;
    jfloatArray out = env->NewFloatArray(samples);
    if (!out) return nullptr;
    std::vector<jfloat> kernel(static_cast<size_t>(samples));
    for (int i = 0; i < samples; ++i) {
        float x = (static_cast<float>(i) / static_cast<float>(samples - 1)) * 6.f - 3.f;
        float ax = std::fabs(x);
        float v;
        if (ax >= 3.f) {
            v = 0.f;
        } else if (ax < 1e-6f) {
            v = 1.f;
        } else {
            float pix = static_cast<float>(M_PI) * ax;
            float pix3 = pix / 3.f;
            v = (std::sin(pix) / pix) * (std::sin(pix3) / pix3);
        }
        kernel[static_cast<size_t>(i)] = v;
    }
    env->SetFloatArrayRegion(out, 0, samples, kernel.data());
    return out;
}

} // extern "C"
