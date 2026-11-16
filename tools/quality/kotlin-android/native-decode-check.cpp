// Host-side regression check for libtubik_native's slot decoding.
//
// NDK r28 clang 19 miscompiled the vectorized decode loop, silently corrupting the player path in
// release builds (the debug build and JVM tests never run the native code). This harness compiles
// the real tubik_native.cpp with the NDK's clang, decodes every slot with the pinned certificate
// digest, and fails if any string differs from what the Kotlin side expects.
// Driven by native-decode-check.sh; not part of the app build.
#include TUBIK_NATIVE_SRC

#include <stdio.h>

namespace {

char captured[128];

jstring JNICALL captureString(JNIEnv*, const char* utf) {
    strncpy(captured, utf, sizeof(captured) - 1);
    captured[sizeof(captured) - 1] = '\0';
    return nullptr;
}

struct Expected {
    const uint8_t* data;
    size_t len;
    jint slot;
    const char* text;
};

}  // namespace

int main() {
    for (size_t i = 0; i < kDigestLen; ++i) {
        gActiveDigest[i] = kMask[i] ^ kExpectedXorMask[i];
    }

    JNINativeInterface table;
    memset(&table, 0, sizeof(table));
    table.NewStringUTF = captureString;
    _JNIEnv env;
    const JNINativeInterface* functions = &table;
    env.functions = functions;

    const Expected expected[] = {
        {kSlot0, sizeof(kSlot0), 0, "/youtubei/v1/player"},
        {kSlot1, sizeof(kSlot1), 1,
         "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"},
        {kSlot2, sizeof(kSlot2), 2, "streamingData"},
        {kSlot3, sizeof(kSlot3), 3, "playabilityStatus"},
    };

    int failures = 0;
    for (const Expected& e : expected) {
        captured[0] = '\0';
        decodeSlot(&env, e.data, e.len, e.slot);
        if (strcmp(captured, e.text) != 0) {
            printf("slot %d decoded to \"%s\", expected \"%s\"\n", e.slot, captured, e.text);
            ++failures;
        }
    }
    return failures == 0 ? 0 : 1;
}
