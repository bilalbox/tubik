#!/usr/bin/env bash
# Shared run-all-then-summarize helpers for Host gates (REFERENCE.md Host gate contract).
# Sourced by tools/quality/<pack>/gate.sh as ../gate-lib.sh. Not a Host entrypoint.
# Host gates set the global `mode` (or call quality_gate_parse_args) before calling
# quality_gate_require_mode / finish.
# shellcheck shell=bash

_quality_gate_tmp=""
_quality_gate_par_dir=""
_qg_par_names=()
_qg_par_pids=()
_qg_par_cmds=()
_qg_par_running=0
_qg_par_reaped=0

quality_gate_cleanup() {
	if [[ -n "${_quality_gate_tmp:-}" ]]; then
		rm -f "${_quality_gate_tmp}"
		_quality_gate_tmp=""
	fi
	if [[ -n "${_quality_gate_par_dir:-}" ]]; then
		rm -rf "${_quality_gate_par_dir}"
		_quality_gate_par_dir=""
	fi
}

_quality_gate_is_verbose() {
	case "${QUALITY_GATE_VERBOSE:-}" in
		"" | 0 | [Ff][Aa][Ll][Ss][Ee]) return 1 ;;
		*) return 0 ;;
	esac
}

# Parse common gate flags (--verbose, --base <ref>) and positional args from "$@".
# Sets global `mode` (default: full) and `quality_gate_args` (remaining positional args).
# Exports QUALITY_GATE_VERBOSE=1 when --verbose is passed and QUALITY_GATE_BASE=<ref>
# when --base is passed so dispatched Host gates and helpers inherit them.
quality_gate_parse_args() {
	local positional=()
	while (($# > 0)); do
		case "$1" in
			--verbose)
				QUALITY_GATE_VERBOSE=1
				export QUALITY_GATE_VERBOSE
				shift
				;;
			--base)
				if (($# < 2)); then
					echo "error: --base requires a git ref" >&2
					exit 2
				fi
				QUALITY_GATE_BASE="$2"
				export QUALITY_GATE_BASE
				shift 2
				;;
			--base=*)
				QUALITY_GATE_BASE="${1#--base=}"
				export QUALITY_GATE_BASE
				shift
				;;
			--)
				shift
				;;
			*)
				positional+=("$1")
				shift
				;;
		esac
	done

	if ((${#positional[@]} > 0)); then
		mode="${positional[0]}"
		if ((${#positional[@]} > 1)); then
			# shellcheck disable=SC2034 # read by Host gates with extra positional args (e.g. js-ts)
			quality_gate_args=("${positional[@]:1}")
		else
			# shellcheck disable=SC2034
			quality_gate_args=()
		fi
	else
		mode="full"
		# shellcheck disable=SC2034
		quality_gate_args=()
	fi
}

# List existing modified, added, or untracked non-ignored files relative to the repo root.
# Usage: quality_changed_files [--base <ref>]
# Defaults to QUALITY_GATE_BASE if set, else HEAD (covering staged + unstaged + untracked).
quality_changed_files() {
	local base="${QUALITY_GATE_BASE:-HEAD}"
	while (($# > 0)); do
		case "$1" in
			--base)
				if (($# < 2)); then
					echo "error: --base requires a git ref" >&2
					return 2
				fi
				base="$2"
				shift 2
				;;
			--base=*)
				base="${1#--base=}"
				shift
				;;
			*)
				shift
				;;
		esac
	done

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

_quality_gate_start_timer() {
	if [[ -n "${EPOCHREALTIME:-}" ]]; then
		local rt="${EPOCHREALTIME}"
		local sec="${rt%%[.,]*}"
		local frac="${rt#*[.,]}"
		frac="${frac:-0}"
		_qg_t0_ds=$((sec * 10 + 10#${frac:0:1}))
		_qg_t0_s=""
	else
		_qg_t0_ds=""
		_qg_t0_s="${SECONDS}"
	fi
}

_quality_gate_format_elapsed() {
	if [[ -n "${_qg_t0_ds:-}" && -n "${EPOCHREALTIME:-}" ]]; then
		local rt="${EPOCHREALTIME}"
		local sec="${rt%%[.,]*}"
		local frac="${rt#*[.,]}"
		frac="${frac:-0}"
		local now_ds=$((sec * 10 + 10#${frac:0:1}))
		local diff=$((now_ds - _qg_t0_ds))
		if ((diff < 0)); then diff=0; fi
		_qg_elapsed="$((diff / 10)).$((diff % 10))s"
	else
		local t0="${_qg_t0_s:-0}"
		local diff=$((SECONDS - t0))
		if ((diff < 0)); then diff=0; fi
		_qg_elapsed="${diff}s"
	fi
}

# Clear the failure list (Hosts that parse mode themselves call this instead of require_mode).
quality_gate_begin() {
	failed=()
	_qg_par_names=()
	_qg_par_pids=()
	_qg_par_cmds=()
	_qg_par_running=0
	_qg_par_reaped=0
	trap quality_gate_cleanup EXIT
}

# Validate `mode` (already set by the caller) and clear the failure list.
# Usage string is appended after `$0` on bad input (default: [diff|fast|full]).
quality_gate_require_mode() {
	local usage="${1:-[diff|fast|full]}"
	# shellcheck disable=SC2154 # mode set by Host gate before sourcing this lib
	case "${mode}" in
		diff|fast|full) ;;
		*)
			echo "usage: $0 ${usage}" >&2
			exit 2
			;;
	esac
	quality_gate_begin
}

_quality_gate_print_capped() {
	local file="$1"
	shift
	local max_lines="${QUALITY_GATE_MAX_LINES:-80}"
	local total_lines
	total_lines="$(wc -l < "${file}")"
	total_lines="${total_lines// /}"

	if ((total_lines <= max_lines)); then
		cat "${file}"
		return 0
	fi

	local head_lines=$(( (max_lines * 5 + 4) / 8 ))
	if ((head_lines < 1 && max_lines >= 1)); then
		head_lines=1
	fi
	local tail_lines=$((max_lines - head_lines))
	local omitted=$((total_lines - head_lines - tail_lines))

	if ((head_lines > 0)); then
		head -n "${head_lines}" "${file}"
	fi
	echo "[... ${omitted} lines omitted; re-run with --verbose or QUALITY_GATE_VERBOSE=1, or run: $*]"
	if ((tail_lines > 0)); then
		tail -n "${tail_lines}" "${file}"
	fi
}

# Run one named step; failures append to `failed` and do not abort the gate.
step() {
	local name="$1"
	shift

	if _quality_gate_is_verbose; then
		echo "==> ${name}"
		if ! "$@"; then
			failed+=("${name}")
		fi
		return 0
	fi

	local tmp rc=0
	tmp="$(mktemp)"
	_quality_gate_tmp="${tmp}"

	_quality_gate_start_timer
	if "$@" >"${tmp}" 2>&1; then
		rc=0
	else
		rc=$?
	fi
	_quality_gate_format_elapsed

	if ((rc == 0)); then
		echo "✓ ${name} (${_qg_elapsed})"
	else
		failed+=("${name}")
		echo "✗ ${name} (${_qg_elapsed})"
		_quality_gate_print_capped "${tmp}" "$@"
	fi

	rm -f "${tmp}"
	_quality_gate_tmp=""
}

_quality_gate_wait_slot() {
	local max_jobs="$1"
	while ((_qg_par_running >= max_jobs)); do
		if ((BASH_VERSINFO[0] > 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] >= 3))); then
			wait -n 2>/dev/null || true
		else
			wait "${_qg_par_pids[_qg_par_reaped]}" 2>/dev/null || true
			_qg_par_reaped=$((_qg_par_reaped + 1))
		fi
		_qg_par_running=$((_qg_par_running - 1))
	done
}

# Queue an independent read-only step for concurrent execution (capped by
# QUALITY_GATE_JOBS, default 4). Falls back to serial `step` when verbose or
# QUALITY_GATE_JOBS=1. Call `flush_parallel` to emit results in registration order.
step_parallel() {
	local name="$1"
	shift

	local jobs="${QUALITY_GATE_JOBS:-4}"
	if _quality_gate_is_verbose || ! [[ "${jobs}" =~ ^[0-9]+$ ]] || ((jobs <= 1)); then
		step "${name}" "$@"
		return 0
	fi

	if [[ -z "${_quality_gate_par_dir:-}" ]]; then
		_quality_gate_par_dir="$(mktemp -d)"
	fi

	_quality_gate_wait_slot "${jobs}"

	local idx="${#_qg_par_names[@]}"
	local out_file="${_quality_gate_par_dir}/${idx}.out"
	local rc_file="${_quality_gate_par_dir}/${idx}.rc"
	local time_file="${_quality_gate_par_dir}/${idx}.time"

	(
		_quality_gate_start_timer
		local rc=0
		if "$@" >"${out_file}" 2>&1; then
			rc=0
		else
			rc=$?
		fi
		_quality_gate_format_elapsed
		printf '%s\n' "${rc}" >"${rc_file}"
		printf '%s\n' "${_qg_elapsed}" >"${time_file}"
	) &

	_qg_par_names+=("${name}")
	_qg_par_pids+=("$!")
	_qg_par_cmds+=("$*")
	_qg_par_running=$((_qg_par_running + 1))
}

# Wait for all queued `step_parallel` jobs and emit results strictly in
# registration order so gate output and failure summaries stay deterministic.
flush_parallel() {
	local count="${#_qg_par_names[@]}"
	if ((count == 0)); then
		return 0
	fi

	local i name pid out_file rc_file time_file rc elapsed
	for ((i = 0; i < count; i++)); do
		name="${_qg_par_names[i]}"
		pid="${_qg_par_pids[i]}"
		wait "${pid}" 2>/dev/null || true

		out_file="${_quality_gate_par_dir}/${i}.out"
		rc_file="${_quality_gate_par_dir}/${i}.rc"
		time_file="${_quality_gate_par_dir}/${i}.time"

		rc=1
		if [[ -f "${rc_file}" ]]; then
			rc="$(<"${rc_file}")"
		fi
		elapsed="0s"
		if [[ -f "${time_file}" ]]; then
			elapsed="$(<"${time_file}")"
		fi

		if ((rc == 0)); then
			echo "✓ ${name} (${elapsed})"
		else
			failed+=("${name}")
			echo "✗ ${name} (${elapsed})"
			if [[ -f "${out_file}" ]]; then
				_quality_gate_print_capped "${out_file}" "${_qg_par_cmds[i]}"
			fi
		fi
	done

	if [[ -n "${_quality_gate_par_dir:-}" ]]; then
		rm -rf "${_quality_gate_par_dir}"
		_quality_gate_par_dir=""
	fi
	_qg_par_names=()
	_qg_par_pids=()
	_qg_par_cmds=()
	_qg_par_running=0
	_qg_par_reaped=0
}

quality_gate_succeeded() {
	flush_parallel
	((${#failed[@]} == 0))
}

# Print the success/failure summary and exit (0 or 1).
quality_gate_finish() {
	if quality_gate_succeeded; then
		echo "# gate:${mode} ok"
		exit 0
	fi
	echo
	echo "# gate:${mode} FAILED: ${failed[*]}"
	exit 1
}

# Failure summary without exiting — for Hosts that print extra diagnostics first.
quality_gate_print_failure() {
	echo
	echo "# gate:${mode} FAILED: ${failed[*]}"
}
