#!/usr/bin/env bash
# quality-kit composed gate — runs each selected Host's gate.
# Usage: ./tools/quality/gate.sh [diff|fast|full] [--base <ref>] [--verbose]
# Keep this entrypoint thin: each Host gate runs all steps then summarizes.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=gate-lib.sh
source "${SCRIPT_DIR}/gate-lib.sh"

mode=""
quality_gate_parse_args "$@"
quality_gate_require_mode "[diff|fast|full] [--base <ref>] [--verbose]"

ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${ROOT}"

if [[ "${mode}" == "diff" ]]; then
	changed_files=()
	if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
		mapfile -t changed_files < <(quality_changed_files)
		if ((${#changed_files[@]} == 0)); then
			echo "# gate:diff ok (clean)"
			exit 0
		fi
	fi
fi

"./tools/quality/kotlin-android/gate.sh" "${mode}"
