#!/usr/bin/env bash
# Betterleaks v1 secret scan — shared layer, used by every Host Pack.
# Usage: ./tools/quality/run-secret-scan.sh [--staged | --diff [--base <ref>]] [--verbose] [-- betterleaks args…]
#
# Modes:
#   --staged       pre-commit: `betterleaks git --staged`
#   --diff         fast diff gate: `betterleaks dir` on changed/untracked files only
#   (default)      local gate: `betterleaks dir .` (working tree + untracked)
#   CI=true        `betterleaks git --log-opts=origin/$GITHUB_BASE_REF..HEAD`
#                  (fallback: full `git` when GITHUB_BASE_REF is unset / unresolvable)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BETTERLEAKS_MAJOR=1
cd "${REPO_ROOT}"

is_ci() {
	case "${CI:-}" in
		"" | 0 | [Ff][Aa][Ll][Ss][Ee]) return 1 ;;
		*) return 0 ;;
	esac
}

is_staged=0
is_diff=0
is_verbose=0
diff_base="${QUALITY_GATE_BASE:-}"
extra_args=()
while [[ $# -gt 0 ]]; do
	case "$1" in
		--staged)
			is_staged=1
			shift
			;;
		--diff)
			is_diff=1
			shift
			;;
		--base)
			if [[ $# -lt 2 ]]; then
				echo "error: --base requires a git ref" >&2
				exit 2
			fi
			diff_base="$2"
			shift 2
			;;
		--base=*)
			diff_base="${1#--base=}"
			shift
			;;
		--verbose)
			is_verbose=1
			shift
			;;
		--)
			shift
			;;
		*)
			extra_args+=("$1")
			shift
			;;
	esac
done

case "${QUALITY_GATE_VERBOSE:-}" in
	"" | 0 | [Ff][Aa][Ll][Ss][Ee]) ;;
	*) is_verbose=1 ;;
esac

version_major() {
	local out="$1"
	if [[ "${out}" =~ ^v?([0-9]+)\. ]]; then
		printf '%s\n' "${BASH_REMATCH[1]}"
	fi
}

try_bin() {
	local cmd="$1"
	local out
	if ! out="$("${cmd}" version 2>&1)"; then
		return 1
	fi
	printf '%s\n' "${out}"
}

resolve_bin() {
	local out major
	if out="$(try_bin betterleaks-v1)"; then
		major="$(version_major "${out}")"
		if [[ "${major}" == "${BETTERLEAKS_MAJOR}" ]]; then
			printf 'betterleaks-v1\n'
			return 0
		fi
	fi
	if out="$(try_bin betterleaks)"; then
		major="$(version_major "${out}")"
		if [[ "${major}" == "${BETTERLEAKS_MAJOR}" ]]; then
			printf 'betterleaks\n'
			return 0
		fi
		echo "error: found betterleaks ${out}, but quality-kit pins 1.x until v2 is stable." >&2
		echo "Install v1.9.0+ — see quality-kit languages/shared/MODULE.md" >&2
		exit 2
	fi
	echo "error: betterleaks 1.x not found on PATH." >&2
	echo "  macOS: brew install --cask betterleaks/tap/betterleaks@1" >&2
	echo "  Linux: ./tools/quality/install-betterleaks.sh (pinned, checksum-verified)" >&2
	exit 2
}

collect_diff_files() {
	local base="${1:-HEAD}"
	if [[ -f "${SCRIPT_DIR}/gate-lib.sh" ]]; then
		# shellcheck source=gate-lib.sh
		source "${SCRIPT_DIR}/gate-lib.sh"
		quality_changed_files --base "${base}"
		return $?
	fi
	if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
		return 1
	fi
	{
		if git rev-parse --verify "${base}" >/dev/null 2>&1; then
			git diff --name-only --diff-filter=ACMR "${base}"
		else
			git diff --cached --name-only --diff-filter=ACMR
			git diff --name-only --diff-filter=ACMR
		fi
		git ls-files --others --exclude-standard
	} | sort -u | while IFS= read -r f; do
		if [[ -n "${f}" && -f "${f}" ]]; then
			printf '%s\n' "${f}"
		fi
	done
}

args=()
diff_files=()

if ((is_staged)); then
	args+=(git --staged)
elif ((is_diff)); then
	if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
		mapfile -t diff_files < <(collect_diff_files "${diff_base:-HEAD}")
		if ((${#diff_files[@]} == 0)); then
			exit 0
		fi
		args+=(dir)
	else
		args+=(dir .)
	fi
elif is_ci; then
	# PR workflows set GITHUB_BASE_REF; Host ci.yml uses fetch-depth: 0 so the
	# remote-tracking base ref is present. workflow_dispatch / missing ref → full git.
	base_ref="${GITHUB_BASE_REF:-}"
	if [[ -n "${base_ref}" ]] && git rev-parse --verify "origin/${base_ref}" >/dev/null 2>&1; then
		args+=(git --log-opts="origin/${base_ref}..HEAD")
	else
		args+=(git)
	fi
else
	args+=(dir .)
fi

args+=(--no-banner --redact)
if ((is_verbose)); then
	args+=(--verbose)
fi

# Betterleaks takes one config, so the shared config and every
# .betterleaks.d/*.toml (Pack and Target allowlists) are concatenated into it.
if [[ -f "${REPO_ROOT}/.betterleaks.toml" ]]; then
	config="$(mktemp)"
	trap 'rm -f "${config}"' EXIT
	for part in "${REPO_ROOT}/.betterleaks.toml" "${REPO_ROOT}"/.betterleaks.d/*.toml; do
		if [[ -f "${part}" ]]; then
			cat "${part}"
			echo
		fi
	done >"${config}"
	args+=(-c "${config}")
fi
if [[ -f "${REPO_ROOT}/.betterleaksignore" ]]; then
	args+=(-i "${REPO_ROOT}/.betterleaksignore")
fi

args+=("${extra_args[@]+"${extra_args[@]}"}")
if ((${#diff_files[@]} > 0)); then
	args+=("${diff_files[@]}")
fi

bin="$(resolve_bin)"
rc=0
"${bin}" "${args[@]}" || rc=$?
if ((rc != 0)); then
	echo "fix: remove the secret/token from code (if a test fixture false positive, add fingerprint to .betterleaksignore)" >&2
fi
exit "${rc}"
