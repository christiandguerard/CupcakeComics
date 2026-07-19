#include <jni.h>
#include <cmath>
#include <algorithm>

static inline int clampi(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static inline int lerpi(int a, int b, float t) {
    return clampi(static_cast<int>(a + (b - a) * t), 0, 255);
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_cupcakecomics_reader_gl_NativeImageBridge_nativeColorCorrect(
        JNIEnv *env, jobject /* thiz */,
        jintArray pixels, jint width, jint height,
        jfloat whiteBalance, jfloat hardness, jfloat vibrance,
        jfloat gammaR, jfloat gammaG, jfloat gammaB) {
    if (!pixels || width <= 0 || height <= 0) return;
    const jsize len = env->GetArrayLength(pixels);
    if (len < width * height) return;

    jint *data = env->GetIntArrayElements(pixels, nullptr);
    if (!data) return;

    const float wb = std::max(0.f, std::min(1.f, whiteBalance));
    const float hard = std::max(0.f, std::min(1.f, hardness));
    const float vib = std::max(-1.f, std::min(1.f, vibrance));
    const float gr = std::max(0.2f, std::min(3.f, gammaR));
    const float gg = std::max(0.2f, std::min(3.f, gammaG));
    const float gb = std::max(0.2f, std::min(3.f, gammaB));

    const int count = width * height;
    for (int i = 0; i < count; ++i) {
        int c = data[i];
        int a = (c >> 24) & 0xff;
        int r = (c >> 16) & 0xff;
        int g = (c >> 8) & 0xff;
        int b = c & 0xff;

        if (wb > 0.f) {
            float lum = 0.299f * r + 0.587f * g + 0.114f * b;
            int maxc = std::max(r, std::max(g, b));
            int minc = std::min(r, std::min(g, b));
            if (lum > 180.f - hard * 80.f) {
                int target = clampi(static_cast<int>(255.f * (0.5f + 0.5f * wb)), 0, 255);
                r = lerpi(r, target, wb);
                g = lerpi(g, target, wb);
                b = lerpi(b, target, wb * std::max(0.5f, 1.f - hard * 0.3f));
            }
            if (hard > 0.4f && (maxc - minc) < 40 && lum >= 40.f && lum <= 200.f) {
                int mid = (r + g + b) / 3;
                float t = (hard - 0.4f) / 0.6f;
                r = lerpi(r, mid, t * 0.5f);
                g = lerpi(g, mid, t * 0.5f);
                b = lerpi(b, mid, t * 0.5f);
            }
        }

        if (vib != 0.f) {
            float maxc = static_cast<float>(std::max(r, std::max(g, b)));
            float minc = static_cast<float>(std::min(r, std::min(g, b)));
            float sat = maxc > 0.f ? (maxc - minc) / maxc : 0.f;
            float amount = vib > 0.f ? vib * (1.f - sat) : vib * sat;
            float gray = 0.299f * r + 0.587f * g + 0.114f * b;
            r = clampi(static_cast<int>(gray + (r - gray) * (1.f + amount)), 0, 255);
            g = clampi(static_cast<int>(gray + (g - gray) * (1.f + amount)), 0, 255);
            b = clampi(static_cast<int>(gray + (b - gray) * (1.f + amount)), 0, 255);
        }

        r = clampi(static_cast<int>(255.0 * std::pow(r / 255.0, 1.0 / gr)), 0, 255);
        g = clampi(static_cast<int>(255.0 * std::pow(g / 255.0, 1.0 / gg)), 0, 255);
        b = clampi(static_cast<int>(255.0 * std::pow(b / 255.0, 1.0 / gb)), 0, 255);

        data[i] = (a << 24) | (r << 16) | (g << 8) | b;
    }

    env->ReleaseIntArrayElements(pixels, data, 0);
}

} // extern "C"
