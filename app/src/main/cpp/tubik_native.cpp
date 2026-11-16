#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#ifndef TUBIK_ENFORCE_INTEGRITY
#define TUBIK_ENFORCE_INTEGRITY 0
#endif

namespace {

constexpr size_t kDigestLen = 32;
constexpr size_t kPkgLen = 18;

constexpr uint8_t kMask[kDigestLen] = {
    0x5a, 0xa3, 0xec, 0x35, 0x7e, 0xc7, 0x10, 0x59,
    0xa2, 0xeb, 0x34, 0x7d, 0xc6, 0x0f, 0x58, 0xa1,
    0xea, 0x33, 0x7c, 0xc5, 0x0e, 0x57, 0xa0, 0xe9,
    0x32, 0x7b, 0xc4, 0x0d, 0x56, 0x9f, 0xe8, 0x31,
};

constexpr uint8_t kExpectedXorMask[kDigestLen] = {
    0xbd, 0x2b, 0xe6, 0x9b, 0x76, 0xd7, 0x39, 0xbb,
    0x7d, 0xd8, 0x5b, 0xfb, 0x92, 0x2e, 0x27, 0xd3,
    0x02, 0xb7, 0xc3, 0x79, 0xc7, 0xcd, 0x65, 0x4b,
    0x6c, 0x94, 0x3a, 0x0e, 0x6e, 0x37, 0x6c, 0x39,
};

constexpr uint8_t kMaskedPkg[kPkgLen] = {
    0x51, 0xc7, 0x79, 0x52, 0x9d, 0x21, 0xc6, 0x7e, 0x09,
    0x93, 0x64, 0xb4, 0x1f, 0x0a, 0x9e, 0x3a, 0xac, 0x59,
};

constexpr uint8_t kSlot0[] = {
    0x92, 0xa8, 0x3d, 0x8c, 0x2a, 0x50, 0x7f, 0xb4, 0x84, 0x2d,
    0x09, 0xb8, 0x75, 0x5c, 0xff, 0xf8, 0x7b, 0x08, 0x25,
};

constexpr uint8_t kSlot1[] = {
    0xf2, 0x80, 0x05, 0x84, 0x22, 0x72, 0x51, 0x9b, 0x9d, 0x70, 0x2b, 0x9a, 0x1c,
    0x5e, 0x05, 0x08, 0x90, 0x0a, 0x31, 0x78, 0x43, 0x1d, 0x6f, 0x4b, 0xe2, 0x44,
    0x28, 0xd5, 0xec, 0x6a, 0x7e, 0x1f, 0xb5, 0x82, 0x40, 0xdb, 0x27, 0x21, 0x0d,
    0xdd, 0x9e, 0x68, 0x2e, 0xeb, 0x0c, 0x55, 0x4f, 0x0c, 0x97, 0x0d, 0x20, 0x22,
    0x59, 0x22, 0x70, 0x0d, 0xf5, 0x5f, 0x60, 0xc7, 0xf8, 0x64, 0x68, 0x13,
};

constexpr uint8_t kSlot2[] = {
    0x34, 0x63, 0x06, 0xb6, 0x15, 0x06, 0x1a, 0xd5, 0xe0, 0x20, 0x58, 0xc7, 0x01,
};

constexpr uint8_t kSlot3[] = {
    0x34, 0x46, 0xca, 0x77, 0xf6, 0x0c, 0x3d, 0xf2, 0xcd,
    0x1d, 0x4f, 0x8d, 0x77, 0x16, 0x3e, 0x33, 0xa8,
};

struct Sha256Ctx {
    uint32_t state[8];
    uint64_t bitlen;
    uint8_t data[64];
    size_t datalen;
};

inline uint32_t rotr(uint32_t x, uint32_t n) {
    return (x >> n) | (x << (32U - n));
}

void sha256Transform(Sha256Ctx* ctx, const uint8_t block[64]) {
    static constexpr uint32_t kRound[64] = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    };

    uint32_t m[64];
    for (size_t i = 0, j = 0; i < 16; ++i, j += 4) {
        m[i] = (static_cast<uint32_t>(block[j]) << 24U) |
               (static_cast<uint32_t>(block[j + 1]) << 16U) |
               (static_cast<uint32_t>(block[j + 2]) << 8U) |
               static_cast<uint32_t>(block[j + 3]);
    }
    for (size_t i = 16; i < 64; ++i) {
        uint32_t s0 = rotr(m[i - 15], 7) ^ rotr(m[i - 15], 18) ^ (m[i - 15] >> 3U);
        uint32_t s1 = rotr(m[i - 2], 17) ^ rotr(m[i - 2], 19) ^ (m[i - 2] >> 10U);
        m[i] = m[i - 16] + s0 + m[i - 7] + s1;
    }

    uint32_t a = ctx->state[0];
    uint32_t b = ctx->state[1];
    uint32_t c = ctx->state[2];
    uint32_t d = ctx->state[3];
    uint32_t e = ctx->state[4];
    uint32_t f = ctx->state[5];
    uint32_t g = ctx->state[6];
    uint32_t h = ctx->state[7];

    for (size_t i = 0; i < 64; ++i) {
        uint32_t s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t temp1 = h + s1 + ch + kRound[i] + m[i];
        uint32_t s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t temp2 = s0 + maj;

        h = g;
        g = f;
        f = e;
        e = d + temp1;
        d = c;
        c = b;
        b = a;
        a = temp1 + temp2;
    }

    ctx->state[0] += a;
    ctx->state[1] += b;
    ctx->state[2] += c;
    ctx->state[3] += d;
    ctx->state[4] += e;
    ctx->state[5] += f;
    ctx->state[6] += g;
    ctx->state[7] += h;
}

void sha256Compute(const uint8_t* data, size_t len, uint8_t out[kDigestLen]) {
    Sha256Ctx ctx{};
    ctx.state[0] = 0x6a09e667;
    ctx.state[1] = 0xbb67ae85;
    ctx.state[2] = 0x3c6ef372;
    ctx.state[3] = 0xa54ff53a;
    ctx.state[4] = 0x510e527f;
    ctx.state[5] = 0x9b05688c;
    ctx.state[6] = 0x1f83d9ab;
    ctx.state[7] = 0x5be0cd19;

    for (size_t i = 0; i < len; ++i) {
        ctx.data[ctx.datalen++] = data[i];
        if (ctx.datalen == 64) {
            sha256Transform(&ctx, ctx.data);
            ctx.bitlen += 512;
            ctx.datalen = 0;
        }
    }

    size_t i = ctx.datalen;
    if (ctx.datalen < 56) {
        ctx.data[i++] = 0x80;
        while (i < 56) {
            ctx.data[i++] = 0x00;
        }
    } else {
        ctx.data[i++] = 0x80;
        while (i < 64) {
            ctx.data[i++] = 0x00;
        }
        sha256Transform(&ctx, ctx.data);
        memset(ctx.data, 0, 56);
    }

    ctx.bitlen += static_cast<uint64_t>(ctx.datalen) * 8ULL;
    ctx.data[63] = static_cast<uint8_t>(ctx.bitlen);
    ctx.data[62] = static_cast<uint8_t>(ctx.bitlen >> 8U);
    ctx.data[61] = static_cast<uint8_t>(ctx.bitlen >> 16U);
    ctx.data[60] = static_cast<uint8_t>(ctx.bitlen >> 24U);
    ctx.data[59] = static_cast<uint8_t>(ctx.bitlen >> 32U);
    ctx.data[58] = static_cast<uint8_t>(ctx.bitlen >> 40U);
    ctx.data[57] = static_cast<uint8_t>(ctx.bitlen >> 48U);
    ctx.data[56] = static_cast<uint8_t>(ctx.bitlen >> 56U);
    sha256Transform(&ctx, ctx.data);

    for (size_t j = 0; j < 8; ++j) {
        out[j * 4] = static_cast<uint8_t>((ctx.state[j] >> 24U) & 0xffU);
        out[j * 4 + 1] = static_cast<uint8_t>((ctx.state[j] >> 16U) & 0xffU);
        out[j * 4 + 2] = static_cast<uint8_t>((ctx.state[j] >> 8U) & 0xffU);
        out[j * 4 + 3] = static_cast<uint8_t>(ctx.state[j] & 0xffU);
    }
}

bool clearExceptionIfAny(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return true;
    }
    return false;
}

bool matchesExpectedPkg(const char* value, size_t len, bool allowSubprocessSuffix) {
    if (value == nullptr) return false;
    if (len < kPkgLen) return false;
    if (!allowSubprocessSuffix && len != kPkgLen) return false;
    if (allowSubprocessSuffix && len > kPkgLen && value[kPkgLen] != ':') return false;

    uint8_t diff = 0;
    for (size_t i = 0; i < kPkgLen; ++i) {
        uint8_t expected = kMaskedPkg[i] ^ kMask[(i * 5 + 3) % kDigestLen];
        diff |= static_cast<uint8_t>(value[i]) ^ expected;
    }
    return diff == 0;
}

bool verifyProcCmdline() {
    FILE* f = fopen("/proc/self/cmdline", "rb");
    if (f == nullptr) return false;
    char buf[128] = {};
    size_t readLen = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    if (readLen == 0) return false;
    size_t strLen = strnlen(buf, readLen);
    return matchesExpectedPkg(buf, strLen, true);
}

jobject resolveApplicationContext(JNIEnv* env, jobject passedContext) {
    if (passedContext != nullptr) {
        return env->NewLocalRef(passedContext);
    }
    jclass activityThreadCls = env->FindClass("android/app/ActivityThread");
    if (clearExceptionIfAny(env) || activityThreadCls == nullptr) {
        return nullptr;
    }
    jmethodID currentAppMid = env->GetStaticMethodID(
        activityThreadCls,
        "currentApplication",
        "()Landroid/app/Application;"
    );
    if (clearExceptionIfAny(env) || currentAppMid == nullptr) {
        env->DeleteLocalRef(activityThreadCls);
        return nullptr;
    }
    jobject app = env->CallStaticObjectMethod(activityThreadCls, currentAppMid);
    env->DeleteLocalRef(activityThreadCls);
    if (clearExceptionIfAny(env)) {
        return nullptr;
    }
    return app;
}

bool readSigningCertSha256(JNIEnv* env, jobject contextObj, uint8_t outDigest[kDigestLen], bool* pkgOk) {
    *pkgOk = false;
    jobject context = resolveApplicationContext(env, contextObj);
    if (context == nullptr) {
        return false;
    }

    jclass contextCls = env->GetObjectClass(context);
    if (clearExceptionIfAny(env) || contextCls == nullptr) {
        env->DeleteLocalRef(context);
        return false;
    }

    jmethodID getPkgMid = env->GetMethodID(contextCls, "getPackageName", "()Ljava/lang/String;");
    jmethodID getPmMid = env->GetMethodID(
        contextCls,
        "getPackageManager",
        "()Landroid/content/pm/PackageManager;"
    );
    env->DeleteLocalRef(contextCls);
    if (clearExceptionIfAny(env) || getPkgMid == nullptr || getPmMid == nullptr) {
        env->DeleteLocalRef(context);
        return false;
    }

    auto pkgJStr = static_cast<jstring>(env->CallObjectMethod(context, getPkgMid));
    if (clearExceptionIfAny(env) || pkgJStr == nullptr) {
        env->DeleteLocalRef(context);
        return false;
    }

    const char* pkgUtf = env->GetStringUTFChars(pkgJStr, nullptr);
    bool validPkg = false;
    if (pkgUtf != nullptr) {
        validPkg = matchesExpectedPkg(pkgUtf, strlen(pkgUtf), false);
        env->ReleaseStringUTFChars(pkgJStr, pkgUtf);
    }
    *pkgOk = validPkg && verifyProcCmdline();

    jobject pm = env->CallObjectMethod(context, getPmMid);
    env->DeleteLocalRef(context);
    if (clearExceptionIfAny(env) || pm == nullptr) {
        env->DeleteLocalRef(pkgJStr);
        return false;
    }

    jint sdkInt = 0;
    jclass versionCls = env->FindClass("android/os/Build$VERSION");
    if (!clearExceptionIfAny(env) && versionCls != nullptr) {
        jfieldID sdkFid = env->GetStaticFieldID(versionCls, "SDK_INT", "I");
        if (!clearExceptionIfAny(env) && sdkFid != nullptr) {
            sdkInt = env->GetStaticIntField(versionCls, sdkFid);
        }
        env->DeleteLocalRef(versionCls);
    }
    clearExceptionIfAny(env);

    constexpr jint kFlagSigningCertificates = 0x08000000;
    constexpr jint kFlagLegacySignatures = 0x00000040;
    jint flags = (sdkInt >= 28) ? kFlagSigningCertificates : kFlagLegacySignatures;

    jclass pmCls = env->GetObjectClass(pm);
    jmethodID getPkgInfoMid = (pmCls != nullptr)
        ? env->GetMethodID(
              pmCls,
              "getPackageInfo",
              "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;"
          )
        : nullptr;
    if (pmCls != nullptr) env->DeleteLocalRef(pmCls);
    if (clearExceptionIfAny(env) || getPkgInfoMid == nullptr) {
        env->DeleteLocalRef(pm);
        env->DeleteLocalRef(pkgJStr);
        return false;
    }

    jobject pkgInfo = env->CallObjectMethod(pm, getPkgInfoMid, pkgJStr, flags);
    env->DeleteLocalRef(pm);
    env->DeleteLocalRef(pkgJStr);
    if (clearExceptionIfAny(env) || pkgInfo == nullptr) {
        return false;
    }

    jclass pkgInfoCls = env->GetObjectClass(pkgInfo);
    jobjectArray signers = nullptr;
    if (pkgInfoCls != nullptr && sdkInt >= 28) {
        jfieldID signingInfoFid = env->GetFieldID(
            pkgInfoCls,
            "signingInfo",
            "Landroid/content/pm/SigningInfo;"
        );
        if (!clearExceptionIfAny(env) && signingInfoFid != nullptr) {
            jobject signingInfo = env->GetObjectField(pkgInfo, signingInfoFid);
            if (!clearExceptionIfAny(env) && signingInfo != nullptr) {
                jclass signingInfoCls = env->GetObjectClass(signingInfo);
                jmethodID getSignersMid = (signingInfoCls != nullptr)
                    ? env->GetMethodID(
                          signingInfoCls,
                          "getApkContentsSigners",
                          "()[Landroid/content/pm/Signature;"
                      )
                    : nullptr;
                if (signingInfoCls != nullptr) env->DeleteLocalRef(signingInfoCls);
                if (!clearExceptionIfAny(env) && getSignersMid != nullptr) {
                    signers = static_cast<jobjectArray>(
                        env->CallObjectMethod(signingInfo, getSignersMid)
                    );
                }
                env->DeleteLocalRef(signingInfo);
            }
        }
    } else if (pkgInfoCls != nullptr) {
        jfieldID sigsFid = env->GetFieldID(
            pkgInfoCls,
            "signatures",
            "[Landroid/content/pm/Signature;"
        );
        if (!clearExceptionIfAny(env) && sigsFid != nullptr) {
            signers = static_cast<jobjectArray>(env->GetObjectField(pkgInfo, sigsFid));
        }
    }
    if (pkgInfoCls != nullptr) env->DeleteLocalRef(pkgInfoCls);
    env->DeleteLocalRef(pkgInfo);
    if (clearExceptionIfAny(env) || signers == nullptr) {
        return false;
    }

    jsize count = env->GetArrayLength(signers);
    if (count != 1) {
        env->DeleteLocalRef(signers);
        return false;
    }

    jobject sig = env->GetObjectArrayElement(signers, 0);
    env->DeleteLocalRef(signers);
    if (clearExceptionIfAny(env) || sig == nullptr) {
        return false;
    }

    jclass sigCls = env->GetObjectClass(sig);
    jmethodID toBytesMid = (sigCls != nullptr) ? env->GetMethodID(sigCls, "toByteArray", "()[B") : nullptr;
    if (sigCls != nullptr) env->DeleteLocalRef(sigCls);
    if (clearExceptionIfAny(env) || toBytesMid == nullptr) {
        env->DeleteLocalRef(sig);
        return false;
    }

    auto certBytes = static_cast<jbyteArray>(env->CallObjectMethod(sig, toBytesMid));
    env->DeleteLocalRef(sig);
    if (clearExceptionIfAny(env) || certBytes == nullptr) {
        return false;
    }

    jsize certLen = env->GetArrayLength(certBytes);
    if (certLen <= 0) {
        env->DeleteLocalRef(certBytes);
        return false;
    }

    jbyte* rawCert = env->GetByteArrayElements(certBytes, nullptr);
    if (rawCert == nullptr) {
        env->DeleteLocalRef(certBytes);
        return false;
    }

    sha256Compute(reinterpret_cast<const uint8_t*>(rawCert), static_cast<size_t>(certLen), outDigest);
    env->ReleaseByteArrayElements(certBytes, rawCert, JNI_ABORT);
    env->DeleteLocalRef(certBytes);
    return true;
}

pthread_mutex_t gDigestMutex = PTHREAD_MUTEX_INITIALIZER;
bool gDigestInitialized = false;
uint8_t gActiveDigest[kDigestLen] = {};

void ensureDigestInitialized(JNIEnv* env, jobject contextObj) {
    pthread_mutex_lock(&gDigestMutex);
    if (!gDigestInitialized) {
        uint8_t computed[kDigestLen] = {};
        bool pkgOk = false;
        bool certOk = readSigningCertSha256(env, contextObj, computed, &pkgOk);

#if TUBIK_ENFORCE_INTEGRITY == 1
        if (certOk && pkgOk) {
            memcpy(gActiveDigest, computed, kDigestLen);
        } else {
            for (size_t i = 0; i < kDigestLen; ++i) {
                gActiveDigest[i] = static_cast<uint8_t>(computed[i] ^ 0xA5U ^ static_cast<uint8_t>(i));
            }
        }
#else
        (void)certOk;
        (void)pkgOk;
        for (size_t i = 0; i < kDigestLen; ++i) {
            gActiveDigest[i] = kMask[i] ^ kExpectedXorMask[i];
        }
#endif
        gDigestInitialized = true;
    }
    pthread_mutex_unlock(&gDigestMutex);
}

jstring decodeSlot(JNIEnv* env, const uint8_t* slotData, size_t slotLen, jint slotIdx) {
    char out[96];
    if (slotLen >= sizeof(out)) {
        return env->NewStringUTF("");
    }
    // Vectorizing this loop miscompiles under NDK r28 clang (see CMakeLists.txt).
#if defined(__clang__)
#pragma clang loop vectorize(disable) interleave(disable)
#endif
    for (size_t i = 0; i < slotLen; ++i) {
        uint8_t dec = slotData[i] ^ gActiveDigest[i % kDigestLen] ^
                      kMask[(i * 7 + static_cast<size_t>(slotIdx) * 11) % kDigestLen];
        // Map control/non-ASCII bytes to printable ASCII so callers fail silently in protocol parsing
        // rather than throwing header/string encoding exceptions.
        if (dec < 0x20U || dec > 0x7eU) {
            dec = static_cast<uint8_t>('a' + (dec % 26U));
        }
        out[i] = static_cast<char>(dec);
    }
    out[slotLen] = '\0';
    return env->NewStringUTF(out);
}

void JNICALL nativeInit(JNIEnv* env, jclass /* clazz */, jobject contextObj) {
    ensureDigestInitialized(env, contextObj);
}

jstring JNICALL nativeResolveSlot(JNIEnv* env, jclass /* clazz */, jobject contextObj, jint slotIdx) {
    ensureDigestInitialized(env, contextObj);
    switch (slotIdx) {
        case 0:
            return decodeSlot(env, kSlot0, sizeof(kSlot0), 0);
        case 1:
            return decodeSlot(env, kSlot1, sizeof(kSlot1), 1);
        case 2:
            return decodeSlot(env, kSlot2, sizeof(kSlot2), 2);
        case 3:
            return decodeSlot(env, kSlot3, sizeof(kSlot3), 3);
        default:
            return env->NewStringUTF("");
    }
}

jint JNICALL nativeResolveUmpMask(JNIEnv* env, jclass /* clazz */, jobject contextObj) {
    ensureDigestInitialized(env, contextObj);
    uint32_t acc = 0;
    for (size_t i = 0; i < kDigestLen; ++i) {
        uint8_t diff = static_cast<uint8_t>(gActiveDigest[i] ^ kMask[i] ^ kExpectedXorMask[i]);
        acc |= static_cast<uint32_t>(diff) << ((i & 3U) * 8U);
    }
    return static_cast<jint>(acc);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }

    jclass bridgeCls = env->FindClass("com/github/libretube/security/NativeTransportBridge");
    if (bridgeCls == nullptr) {
        return JNI_ERR;
    }

    static const JNINativeMethod kMethods[] = {
        {"nativeInit", "(Landroid/content/Context;)V", reinterpret_cast<void*>(nativeInit)},
        {"nativeResolveSlot", "(Landroid/content/Context;I)Ljava/lang/String;",
         reinterpret_cast<void*>(nativeResolveSlot)},
        {"nativeResolveUmpMask", "(Landroid/content/Context;)I",
         reinterpret_cast<void*>(nativeResolveUmpMask)},
    };

    if (env->RegisterNatives(bridgeCls, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) < 0) {
        env->DeleteLocalRef(bridgeCls);
        return JNI_ERR;
    }

    env->DeleteLocalRef(bridgeCls);
    return JNI_VERSION_1_6;
}
