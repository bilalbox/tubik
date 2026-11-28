#!/usr/bin/env bash
# Compare source file line counts against baselines/file-size.json (shrink-only).
#
# Usage: ./tools/quality/run-file-size-ratchet.sh [--update|--prune]
#
# Enumerates tracked and untracked non-ignored source files, skips generated
# artifacts / build outputs / baselines / fixtures / snapshots / binaries /
# symlinks / custom "exclude" globs in baselines/file-size.json, and enforces
# maxLines (default 500) via the shared ratchet contract.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

update=0
prune=0
while [[ $# -gt 0 ]]; do
	case "$1" in
	--)
		shift
		;;
	--update)
		update=1
		shift
		;;
	--prune)
		prune=1
		shift
		;;
	-h | --help)
		sed -n '2,9p' "$0"
		exit 0
		;;
	*)
		echo "error: unknown argument: $1" >&2
		exit 2
		;;
	esac
done

if [[ "${update}" -eq 1 && "${prune}" -eq 1 ]]; then
	echo "error: cannot combine --update and --prune" >&2
	exit 2
fi

baseline="${REPO_ROOT}/baselines/file-size.json"
if [[ ! -f "${baseline}" ]]; then
	echo "error: missing ${baseline}" >&2
	echo "  apply the Shared layer, or copy languages/shared/templates/baselines/file-size.json" >&2
	exit 2
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT
findings_out="${tmpdir}/findings.json"
max_lines_out="${tmpdir}/max-lines.txt"

mode_args=()
if [[ "${update}" -eq 1 ]]; then
	mode_args+=(--update)
elif [[ "${prune}" -eq 1 ]]; then
	mode_args+=(--prune)
fi

if command -v python3 >/dev/null 2>&1; then
	python3 - "${baseline}" "${findings_out}" "${max_lines_out}" <<'PY'
import json
import os
import re
import subprocess
import sys
from pathlib import Path

baseline_path = Path(sys.argv[1])
findings_path = Path(sys.argv[2])
max_lines_path = Path(sys.argv[3])

baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
raw_max = baseline.get("maxLines", 500)
max_lines = int(raw_max) if isinstance(raw_max, (int, float)) and not isinstance(raw_max, bool) else 500
max_lines_path.write_text(str(max_lines), encoding="utf-8")

raw_excludes = baseline.get("exclude") or []
exclude_globs = [str(g) for g in raw_excludes if isinstance(g, str) and g]

SOURCE_EXTS = {
    ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs",
    ".py", ".dart", ".kt", ".kts", ".rs", ".sh",
}
EXCLUDED_DIRS = {
    "build", "dist", ".dart_tool", "node_modules", ".venv",
    "fixtures", "__snapshots__",
}
EXCLUDED_NAMES = {
    "worker-configuration.d.ts",
    "pnpm-lock.yaml",
    "vulture_whitelist.py",
}
EXCLUDED_SUFFIXES = (".g.dart", ".freezed.dart", ".pb.dart")


def glob_to_regex(pattern: str) -> re.Pattern[str]:
    pat = pattern.replace("\\", "/")
    out = ["^"]
    i = 0
    n = len(pat)
    while i < n:
        if pat[i : i + 3] == "**/":
            out.append("(?:.*/)?")
            i += 3
        elif pat[i : i + 2] == "**":
            out.append(".*")
            i += 2
        elif pat[i] == "*":
            out.append("[^/]*")
            i += 1
        elif pat[i] == "?":
            out.append("[^/]")
            i += 1
        else:
            out.append(re.escape(pat[i]))
            i += 1
    out.append("$")
    return re.compile("".join(out))


exclude_res = [glob_to_regex(g) for g in exclude_globs]


def is_excluded(rel: str) -> bool:
    parts = rel.split("/")
    if not parts:
        return True
    if parts[0] == "baselines":
        return True
    for seg in parts[:-1]:
        if seg in EXCLUDED_DIRS:
            return True
    name = parts[-1]
    if name in EXCLUDED_NAMES:
        return True
    if name.endswith(EXCLUDED_SUFFIXES):
        return True
    if ".gen." in name or ".min." in name or ".lock" in name:
        return True
    for rx in exclude_res:
        if rx.match(rel):
            return True
    return False


def list_candidates() -> list[str]:
    in_git = subprocess.run(
        ["git", "rev-parse", "--is-inside-work-tree"],
        capture_output=True,
        text=True,
    )
    if in_git.returncode == 0:
        files: set[str] = set()
        for cmd in (
            ["git", "ls-files", "-z"],
            ["git", "ls-files", "-z", "--others", "--exclude-standard"],
        ):
            r = subprocess.run(cmd, capture_output=True)
            if r.returncode == 0 and r.stdout:
                for raw in r.stdout.split(b"\0"):
                    if raw:
                        files.add(raw.decode("utf-8", errors="surrogateescape").replace("\\", "/"))
        return sorted(files)

    files_list: list[str] = []
    for root, dirs, filenames in os.walk("."):
        dirs[:] = [d for d in dirs if d != ".git" and d not in EXCLUDED_DIRS]
        for fn in filenames:
            rel = os.path.relpath(os.path.join(root, fn), ".").replace("\\", "/")
            files_list.append(rel)
    return sorted(files_list)


findings: list[dict[str, object]] = []
for rel in list_candidates():
    p = Path(rel)
    if p.suffix.lower() not in SOURCE_EXTS:
        continue
    if is_excluded(rel):
        continue
    if p.is_symlink() or not p.is_file():
        continue
    try:
        data = p.read_bytes()
    except OSError:
        continue
    if b"\0" in data[:8192]:
        continue
    count = len(data.splitlines())
    if count > max_lines:
        findings.append({"id": rel, "score": count})

findings_path.write_text(
    json.dumps({"findings": findings}, indent="\t") + "\n",
    encoding="utf-8",
)
PY
	max_lines="$(cat "${max_lines_out}")"
	rc=0
	python3 "${REPO_ROOT}/tools/quality/ratchets/compare.py" \
		--baseline "${baseline}" \
		--findings "${findings_out}" \
		"${mode_args[@]+"${mode_args[@]}"}" || rc=$?
	if [[ "${rc}" -eq 1 ]]; then
		echo "fix: split cohesive modules so each source file stays <= ${max_lines} lines (or run with --prune after shrinking a baselined file)" >&2
	fi
	exit "${rc}"
elif command -v node >/dev/null 2>&1; then
	node - "${baseline}" "${findings_out}" "${max_lines_out}" <<'JS'
const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const baselinePath = process.argv[2];
const findingsPath = process.argv[3];
const maxLinesPath = process.argv[4];

const baseline = JSON.parse(fs.readFileSync(baselinePath, "utf8"));
const maxLines =
	typeof baseline.maxLines === "number" && Number.isFinite(baseline.maxLines)
		? Math.trunc(baseline.maxLines)
		: 500;
fs.writeFileSync(maxLinesPath, String(maxLines), "utf8");

const excludeGlobs = Array.isArray(baseline.exclude)
	? baseline.exclude.filter((g) => typeof g === "string" && g.length > 0)
	: [];

const SOURCE_EXTS = new Set([
	".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs",
	".py", ".dart", ".kt", ".kts", ".rs", ".sh",
]);
const EXCLUDED_DIRS = new Set([
	"build", "dist", ".dart_tool", "node_modules", ".venv",
	"fixtures", "__snapshots__",
]);
const EXCLUDED_NAMES = new Set([
	"worker-configuration.d.ts",
	"pnpm-lock.yaml",
	"vulture_whitelist.py",
]);
const EXCLUDED_SUFFIXES = [".g.dart", ".freezed.dart", ".pb.dart"];

function globToRegex(pattern) {
	const pat = pattern.replace(/\\/g, "/");
	let out = "^";
	let i = 0;
	while (i < pat.length) {
		if (pat.slice(i, i + 3) === "**/") {
			out += "(?:.*/)?";
			i += 3;
		} else if (pat.slice(i, i + 2) === "**") {
			out += ".*";
			i += 2;
		} else if (pat[i] === "*") {
			out += "[^/]*";
			i += 1;
		} else if (pat[i] === "?") {
			out += "[^/]";
			i += 1;
		} else {
			out += pat[i].replace(/[.+^${}()|[\]\\]/g, "\\$&");
			i += 1;
		}
	}
	out += "$";
	return new RegExp(out);
}

const excludeRes = excludeGlobs.map(globToRegex);

function isExcluded(rel) {
	const parts = rel.split("/");
	if (parts.length === 0) return true;
	if (parts[0] === "baselines") return true;
	for (let i = 0; i < parts.length - 1; i++) {
		if (EXCLUDED_DIRS.has(parts[i])) return true;
	}
	const name = parts[parts.length - 1];
	if (EXCLUDED_NAMES.has(name)) return true;
	if (EXCLUDED_SUFFIXES.some((s) => name.endsWith(s))) return true;
	if (name.includes(".gen.") || name.includes(".min.") || name.includes(".lock")) {
		return true;
	}
	return excludeRes.some((rx) => rx.test(rel));
}

function listCandidates() {
	let inGit = false;
	try {
		execFileSync("git", ["rev-parse", "--is-inside-work-tree"], {
			stdio: "ignore",
		});
		inGit = true;
	} catch {
		inGit = false;
	}

	if (inGit) {
		const files = new Set();
		for (const args of [
			["ls-files", "-z"],
			["ls-files", "-z", "--others", "--exclude-standard"],
		]) {
			try {
				const buf = execFileSync("git", args);
				for (const entry of buf.toString("utf8").split("\0")) {
					if (entry) files.add(entry.replace(/\\/g, "/"));
				}
			} catch {
				// ignore
			}
		}
		return [...files].sort();
	}

	const out = [];
	function walk(dir) {
		for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
			if (ent.name === ".git" || EXCLUDED_DIRS.has(ent.name)) continue;
			const full = path.join(dir, ent.name);
			if (ent.isDirectory()) {
				walk(full);
			} else if (ent.isFile()) {
				out.push(path.relative(".", full).replace(/\\/g, "/"));
			}
		}
	}
	walk(".");
	return out.sort();
}

function countLines(buf) {
	if (buf.length === 0) return 0;
	let count = 0;
	for (let i = 0; i < buf.length; i++) {
		if (buf[i] === 0x0a) count++;
	}
	if (buf[buf.length - 1] !== 0x0a) count++;
	return count;
}

const findings = [];
for (const rel of listCandidates()) {
	const ext = path.extname(rel).toLowerCase();
	if (!SOURCE_EXTS.has(ext)) continue;
	if (isExcluded(rel)) continue;
	let st;
	try {
		st = fs.lstatSync(rel);
	} catch {
		continue;
	}
	if (st.isSymbolicLink() || !st.isFile()) continue;
	let buf;
	try {
		buf = fs.readFileSync(rel);
	} catch {
		continue;
	}
	const sampleLen = Math.min(buf.length, 8192);
	let isBinary = false;
	for (let i = 0; i < sampleLen; i++) {
		if (buf[i] === 0) {
			isBinary = true;
			break;
		}
	}
	if (isBinary) continue;
	const count = countLines(buf);
	if (count > maxLines) {
		findings.push({ id: rel, score: count });
	}
}

fs.writeFileSync(
	findingsPath,
	`${JSON.stringify({ findings }, null, "\t")}\n`,
	"utf8",
);
JS
	max_lines="$(cat "${max_lines_out}")"
	rc=0
	node "${REPO_ROOT}/tools/quality/ratchets/compare.ts" \
		--baseline "${baseline}" \
		--findings "${findings_out}" \
		"${mode_args[@]+"${mode_args[@]}"}" || rc=$?
	if [[ "${rc}" -eq 1 ]]; then
		echo "fix: split cohesive modules so each source file stays <= ${max_lines} lines (or run with --prune after shrinking a baselined file)" >&2
	fi
	exit "${rc}"
else
	echo "error: file-size ratchet requires python3 or node on PATH" >&2
	exit 2
fi
