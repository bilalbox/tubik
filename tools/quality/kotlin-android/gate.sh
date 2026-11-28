#!/usr/bin/env bash
# quality-kit kotlin-android gate.
# Usage: ./tools/quality/kotlin-android/gate.sh [diff|fast|full] [--base <ref>] [--verbose]
#   diff — changed .kt/.kts ktfmt + secrets --diff + file-size (+ qualityFast when Kotlin/Gradle/manifest/res changed)
#   fast — ktfmt + secrets + file-size + qualityFast (detekt, compile main + unit tests)
#   full — ktfmt + secrets + file-size + qualityFull (fast + lint, unit/screenshot tests,
#          Kover, buildHealth, native decode check). Default for CI / pre-push.
# Every step runs even after a failure so one invocation reports everything.
# Local multi-Host Targets use the composed ./tools/quality/gate.sh instead.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"
# Target layout: tools/quality/gate-lib.sh (shared layer). Kit path differs.
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/../gate-lib.sh"

mode=""
quality_gate_parse_args "$@"
case "${mode}" in
	diff) task=qualityFast ;;
	fast) task=qualityFast ;;
	full) task=qualityFull ;;
	*)
		echo "usage: $0 [diff|fast|full] [--base <ref>] [--verbose]" >&2
		exit 2
		;;
esac
quality_gate_begin

REPO_ROOT="$(quality_repo_root)"

if [[ "${mode}" == "diff" ]]; then
	changed_files=()
	if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
		mapfile -t changed_files < <(quality_changed_files)
		if ((${#changed_files[@]} == 0)); then
			echo "# gate:diff ok (clean)"
			exit 0
		fi
	fi

	echo "# gate:diff"
	kt_files=()
	gradle_relevant=0
	for f in "${changed_files[@]+"${changed_files[@]}"}"; do
		case "${f}" in
			*.kt|*.kts)
				kt_files+=("${f}")
				gradle_relevant=1
				;;
			*.gradle|gradle.properties|*/gradle.properties|libs.versions.toml|*/libs.versions.toml|AndroidManifest.xml|*/AndroidManifest.xml|*/res/*|res/*)
				gradle_relevant=1
				;;
		esac
	done

	if ((${#kt_files[@]} > 0)); then
		step_parallel format "${SCRIPT_DIR}/run-format.sh" --files "${kt_files[@]}"
	fi
	step_parallel secrets "${SCRIPT_DIR}/../run-secret-scan.sh" --diff
	step_parallel file-size "${SCRIPT_DIR}/../run-file-size-ratchet.sh"
	if ((gradle_relevant)); then
		step_parallel gradle quality_gradle -q --continue "${task}"
	fi
	flush_parallel
	quality_gate_finish
fi

marker="$(mktemp)"
trap 'rm -f "${marker}"; quality_gate_cleanup' EXIT

echo "# gate:${mode}"
step_parallel format "${SCRIPT_DIR}/run-format.sh"
step_parallel secrets "${SCRIPT_DIR}/../run-secret-scan.sh"
step_parallel file-size "${SCRIPT_DIR}/../run-file-size-ratchet.sh"
if [[ "${mode}" == "full" ]]; then
	step_parallel vulns "${SCRIPT_DIR}/../run-vuln-scan.sh"
fi
step_parallel gradle quality_gradle -q --continue "${task}"
flush_parallel
if [[ "${mode}" == "full" ]]; then
	# Kover XML from qualityFull; ratchet replaces the old minLineCoverage floor.
	step coverage "${SCRIPT_DIR}/../run-coverage-ratchet.sh" --pack kotlin-android
	# Guards libtubik_native's slot decoding against NDK compiler miscompiles.
	step native-decode "${SCRIPT_DIR}/native-decode-check.sh"
fi

if quality_gate_succeeded; then
	echo "# gate:${mode} ok"
	exit 0
fi

quality_gate_print_failure
mapfile -t diffs < <(
	find "${REPO_ROOT}" \( -name .git -o -name .gradle -o -name node_modules \) -prune -o \
		-path '*/build/outputs/roborazzi/*_compare.png' -newer "${marker}" -print 2>/dev/null | sort
)
changed=()
unrecorded=()
for compare in "${diffs[@]}"; do
	module="${compare%%/build/outputs/roborazzi/*}"
	name="${compare##*/}"
	if [[ -f "${module}/src/test/screenshots/${name%_compare.png}.png" ]]; then
		changed+=("${compare#"${REPO_ROOT}/"}")
	else
		unrecorded+=("${module#"${REPO_ROOT}/"}/src/test/screenshots/${name%_compare.png}.png")
	fi
done
if ((${#changed[@]} > 0)); then
	echo "Screenshot diffs (Reference | Diff | New) — open these images:"
	printf '  %s\n' "${changed[@]}"
	echo "If the change is intended: ./tools/quality/kotlin-android/screenshots.sh record, then commit the goldens."
fi
if ((${#unrecorded[@]} > 0)); then
	echo "Previews without a golden — run ./tools/quality/kotlin-android/screenshots.sh record, inspect, and commit:"
	printf '  %s\n' "${unrecorded[@]}"
fi
exit 1
