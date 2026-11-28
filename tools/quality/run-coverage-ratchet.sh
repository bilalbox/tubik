#!/usr/bin/env bash
# Compare measured coverage against baselines/coverage.json (shrink-only / raise-only).
#
# Usage: ./tools/quality/run-coverage-ratchet.sh [--pack <id>] [--update|--prune] [--findings PATH]
#
# Without --findings:
#   - With --pack <id>: runs only tools/quality/<id>/collect-coverage.sh and
#     scopes comparison / --update / --prune to <id>'s rows in baselines/coverage.json.
#   - Without --pack: runs every Host collector matching
#       tools/quality/*/collect-coverage.sh
#     and merges results by id (namespaced as <pack>:<module> in multi-Host repos).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

update=0
prune=0
pack=""
findings_path=""
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
	--pack)
		pack="${2:-}"
		if [[ -z "${pack}" ]]; then
			echo "error: --pack requires a Pack id" >&2
			exit 2
		fi
		shift 2
		;;
	--findings)
		findings_path="${2:-}"
		shift 2
		;;
	-h | --help)
		sed -n '2,12p' "$0"
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

baseline="${REPO_ROOT}/baselines/coverage.json"
if [[ ! -f "${baseline}" ]]; then
	echo "error: missing ${baseline}" >&2
	echo "  apply the Shared layer, or copy languages/shared/templates/baselines/coverage.json" >&2
	exit 2
fi

mapfile -t all_collectors < <(
	# shellcheck disable=SC2012 # intentional glob listing of Host collectors
	ls tools/quality/*/collect-coverage.sh 2>/dev/null | sort -u || true
)

namespace_ids="$(
	python3 - "${baseline}" "${#all_collectors[@]}" <<'PY'
import json, sys
base = json.loads(open(sys.argv[1], encoding="utf-8").read())
n_collectors = int(sys.argv[2])
has_prefixed = any(":" in str(f.get("id", "")) for f in base.get("findings", []))
print("1" if (n_collectors > 1 or has_prefixed) else "0")
PY
)"

tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT
out="${tmpdir}/findings.json"

namespace_findings() {
	local src="$1" p="$2" ns="$3"
	python3 - "${src}" "${p}" "${ns}" <<'PY'
import json, sys
data = json.loads(open(sys.argv[1], encoding="utf-8").read())
pack = sys.argv[2]
ns = sys.argv[3] == "1"
out = []
for f in data.get("findings", []):
    row = dict(f)
    fid = str(row["id"])
    if ns and pack and not fid.startswith(f"{pack}:"):
        row["id"] = f"{pack}:{fid}"
    out.append(row)
print(json.dumps({"findings": out}, indent="\t"))
PY
}

if [[ -n "${findings_path}" ]]; then
	if [[ -n "${pack}" ]]; then
		namespace_findings "${findings_path}" "${pack}" "${namespace_ids}" >"${out}"
	else
		cp "${findings_path}" "${out}"
	fi
elif [[ -n "${pack}" ]]; then
	collector="tools/quality/${pack}/collect-coverage.sh"
	if [[ ! -f "${collector}" ]]; then
		echo "error: missing coverage collector at ${collector}" >&2
		exit 2
	fi
	raw="${tmpdir}/raw-${pack}.json"
	bash "${collector}" >"${raw}"
	namespace_findings "${raw}" "${pack}" "${namespace_ids}" >"${out}"
else
	if [[ ${#all_collectors[@]} -eq 0 ]]; then
		echo "error: no coverage collector at tools/quality/*/collect-coverage.sh" >&2
		echo "  Host Packs place collect-coverage.sh under tools/quality/<pack>/." >&2
		exit 2
	fi
	merged='{"findings":[]}'
	for c in "${all_collectors[@]}"; do
		c_pack="$(basename "$(dirname "${c}")")"
		raw="${tmpdir}/raw-${c_pack}.json"
		part="${tmpdir}/part-${c_pack}.json"
		bash "${c}" >"${raw}"
		namespace_findings "${raw}" "${c_pack}" "${namespace_ids}" >"${part}"
		merged="$(
			python3 - "${merged}" "${part}" <<'PY'
import json, sys
a = json.loads(sys.argv[1])
b = json.loads(open(sys.argv[2], encoding="utf-8").read())
by_id = {str(f["id"]): f for f in a.get("findings", [])}
for f in b.get("findings", []):
    by_id[str(f["id"])] = f
print(json.dumps({"findings": list(by_id.values())}, indent="\t"))
PY
		)"
	done
	printf '%s\n' "${merged}" >"${out}"
fi

if [[ -n "${pack}" && "${namespace_ids}" == "1" ]]; then
	scoped_baseline="${tmpdir}/scoped-baseline.json"
	other_rows="${tmpdir}/other-rows.json"
	python3 - "${baseline}" "${pack}" "${scoped_baseline}" "${other_rows}" <<'PY'
import json, sys
base = json.loads(open(sys.argv[1], encoding="utf-8").read())
pack = sys.argv[2]
prefix = f"{pack}:"
mine = [f for f in base.get("findings", []) if str(f.get("id", "")).startswith(prefix)]
others = [f for f in base.get("findings", []) if not str(f.get("id", "")).startswith(prefix)]
scoped = {k: v for k, v in base.items() if k != "findings"}
scoped["findings"] = mine
open(sys.argv[3], "w", encoding="utf-8").write(json.dumps(scoped, indent="\t") + "\n")
open(sys.argv[4], "w", encoding="utf-8").write(json.dumps(others, indent="\t") + "\n")
PY
	args=(
		python3 "${REPO_ROOT}/tools/quality/ratchets/compare.py"
		--baseline "${scoped_baseline}"
		--findings "${out}"
	)
	if [[ "${update}" -eq 1 ]]; then
		args+=(--update)
	elif [[ "${prune}" -eq 1 ]]; then
		args+=(--prune)
	fi
	echo "==> coverage ratchet (${pack})"
	if [[ "${update}" -eq 1 || "${prune}" -eq 1 ]]; then
		scoped_before="${tmpdir}/scoped-before.json"
		cp "${scoped_baseline}" "${scoped_before}"
		rc=0
		"${args[@]}" || rc=$?
		if [[ "${rc}" -eq 2 ]]; then
			exit 2
		fi
		if ! cmp -s "${scoped_before}" "${scoped_baseline}"; then
			python3 - "${scoped_baseline}" "${other_rows}" "${baseline}" <<'PY'
import json, sys
scoped = json.loads(open(sys.argv[1], encoding="utf-8").read())
others = json.loads(open(sys.argv[2], encoding="utf-8").read())
merged = sorted(scoped.get("findings", []) + others, key=lambda x: str(x["id"]))
out = {k: v for k, v in scoped.items() if k != "findings"}
out["findings"] = merged
open(sys.argv[3], "w", encoding="utf-8").write(json.dumps(out, indent="\t") + "\n")
PY
		fi
		if [[ "${rc}" -eq 1 ]]; then
			echo "fix: add unit tests for uncovered lines in the listed module(s) (baseline: baselines/coverage.json)" >&2
		fi
		exit "${rc}"
	fi
	rc=0
	"${args[@]}" || rc=$?
	if [[ "${rc}" -eq 1 ]]; then
		echo "fix: add unit tests for uncovered lines in the listed module(s) (baseline: baselines/coverage.json)" >&2
	fi
	exit "${rc}"
fi

args=(
	python3 "${REPO_ROOT}/tools/quality/ratchets/compare.py"
	--baseline "${baseline}"
	--findings "${out}"
)
if [[ "${update}" -eq 1 ]]; then
	args+=(--update)
elif [[ "${prune}" -eq 1 ]]; then
	args+=(--prune)
fi

echo "==> coverage ratchet"
rc=0
"${args[@]}" || rc=$?
if [[ "${rc}" -eq 1 ]]; then
	echo "fix: add unit tests for uncovered lines in the listed module(s) (baseline: baselines/coverage.json)" >&2
fi
exit "${rc}"
