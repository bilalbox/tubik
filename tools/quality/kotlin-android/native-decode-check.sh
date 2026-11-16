#!/usr/bin/env bash
# Compiles app/src/main/cpp/tubik_native.cpp with the Android NDK's clang for the host, runs the
# slot decoder, and checks every decoded string. Guards against compiler miscompiles of the decode
# loop (NDK r28 clang 19 vectorizer bug that broke stream fallback in release builds).
# Needs the NDK version pinned by `ndkVersion` in app/build.gradle.kts and a Linux/macOS x86_64 host.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"
REPO_ROOT="$(quality_repo_root)"

ndk_version="$(sed -n 's/^[[:space:]]*ndkVersion = "\(.*\)"/\1/p' "${REPO_ROOT}/app/build.gradle.kts" | head -1)"
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "${sdk_dir}" && -f "${REPO_ROOT}/local.properties" ]]; then
	sdk_dir="$(sed -n 's/^sdk\.dir=//p' "${REPO_ROOT}/local.properties" | head -1)"
fi
ndk="${ANDROID_NDK_HOME:-${sdk_dir}/ndk/${ndk_version}}"

case "$(uname -s)" in
	Linux) tag=linux-x86_64 ;;
	Darwin) tag=darwin-x86_64 ;;
	*) echo "native-decode: unsupported host $(uname -s)" >&2; exit 1 ;;
esac
if [[ ! -d "${ndk}" ]] && quality_is_ci; then
	# CI quality builds may not have pulled the NDK yet; licenses are accepted by setup-android.
	sdkmanager="$(command -v sdkmanager || true)"
	if [[ -n "${sdkmanager}" ]]; then
		echo "native-decode: installing NDK ${ndk_version}"
		"${sdkmanager}" --install "ndk;${ndk_version}" >/dev/null
	fi
fi
clang="${ndk}/toolchains/llvm/prebuilt/${tag}/bin/clang++"
jni_header="${ndk}/toolchains/llvm/prebuilt/${tag}/sysroot/usr/include/jni.h"
if [[ ! -x "${clang}" || ! -f "${jni_header}" ]]; then
	echo "native-decode: NDK ${ndk_version} not found at ${ndk} (set ANDROID_NDK_HOME)" >&2
	exit 1
fi

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
mkdir "${work}/inc"
cp "${jni_header}" "${work}/inc/jni.h"

common=(
	-std=c++17 -fvisibility=hidden -fno-rtti -fno-exceptions
	-DTUBIK_ENFORCE_INTEGRITY=1
	"-DTUBIK_NATIVE_SRC=\"${REPO_ROOT}/app/src/main/cpp/tubik_native.cpp\""
	-I"${work}/inc"
)
status=0
# The source must decode correctly on its own (no reliance on CMake flags) at every optimization
# level, and again with the exact flags CMakeLists.txt applies.
for opts in "-O2" "-O3" "-Os" "-O2 -fno-vectorize -fno-slp-vectorize"; do
	# shellcheck disable=SC2086 # intentional word splitting of the flag list
	if ! "${clang}" "${common[@]}" ${opts} "${SCRIPT_DIR}/native-decode-check.cpp" -o "${work}/check"; then
		echo "native-decode: compile failed (${opts})" >&2
		status=1
		continue
	fi
	if "${work}/check"; then
		echo "native-decode: ok (${opts})"
	else
		echo "native-decode: FAILED (${opts})" >&2
		status=1
	fi
done
exit "${status}"
