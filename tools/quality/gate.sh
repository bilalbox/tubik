#!/usr/bin/env bash
# quality-kit composed gate — runs each selected Host's gate.
# Usage: ./tools/quality/gate.sh [fast|full]
# Keep this entrypoint thin: each Host gate runs all steps then summarizes.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=gate-lib.sh
source "${SCRIPT_DIR}/gate-lib.sh"

mode="${1:-full}"
quality_gate_require_mode

ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${ROOT}"

"./tools/quality/kotlin-android/gate.sh" "${mode}"
