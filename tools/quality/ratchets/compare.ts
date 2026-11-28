/**
 * Shared shrink-only ratchet compare (findings + coverage) — native Node/TS.
 *
 * Parity with compare.py (which stays in the Shared layer for bash Hosts
 * that do not assume Node):
 * - novelty: finding IDs absent from the baseline fail
 * - stale prune: baseline rows that no longer reproduce fail
 * - score regression: same ID, worse score than baseline fails
 *   - worseWhen "higher" (default): current > baseline fails
 *   - worseWhen "lower" (coverage): current < baseline fails
 * - floor (coverage): score below baseline.floor fails
 * - empty coverage baseline: only floor is enforced (adopt / greenfield)
 * - --update rewrites the baseline to current findings, refused when CI=true
 * - --prune removes stale rows and tightens improved scores without adding novel
 *   findings or loosening regressed scores, refused when CI=true
 *
 * CLI usage: tsx tools/quality/ratchets/compare.ts \
 *   --baseline <path> --findings <path> [--update|--prune] [--result-json <path>]
 */

import { readFileSync, realpathSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

export type Finding = {
	id: string;
	/** Present for scored findings; compared for regressions. */
	score?: number;
};

export type BaselineFinding = Finding & {
	note?: string;
	issue?: number;
};

export type BaselineFile = {
	version: 1;
	/** Default "higher" (complexity). Coverage uses "lower". */
	worseWhen?: "higher" | "lower";
	/** Absolute minimum score when worseWhen is "lower" (coverage). */
	floor?: number;
	/** Extra ratchet-specific metadata (e.g. file-size maxLines / exclude). */
	[key: string]: unknown;
	findings: BaselineFinding[];
};

export type FindingsFile = {
	findings: Finding[];
};

export type CompareResult = {
	ok: boolean;
	novel: string[];
	stale: string[];
	regressions: Array<{ id: string; baseline: number; current: number }>;
	belowFloor: Array<{ id: string; floor: number; current: number }>;
};

export function isCiEnv(env: NodeJS.ProcessEnv = process.env): boolean {
	const v = env.CI;
	return (
		v !== undefined && v !== "" && v !== "0" && v.toLowerCase() !== "false"
	);
}

function lexCompare(a: string, b: string): number {
	return a < b ? -1 : a > b ? 1 : 0;
}

function worseWhenOf(baseline: BaselineFile): "higher" | "lower" {
	const w = baseline.worseWhen ?? "higher";
	if (w !== "higher" && w !== "lower") {
		throw new Error(
			`error: baseline worseWhen must be higher|lower, got ${JSON.stringify(w)}`,
		);
	}
	return w;
}

function floorOf(baseline: BaselineFile): number | undefined {
	if (baseline.floor === undefined) return undefined;
	return Number(baseline.floor);
}

export function compareFindings(
	baseline: BaselineFile,
	current: readonly Finding[],
): CompareResult {
	const direction = worseWhenOf(baseline);
	const floor = floorOf(baseline);

	const baselineById = new Map<string, BaselineFinding>();
	for (const f of baseline.findings ?? []) {
		baselineById.set(String(f.id), f);
	}
	const currentById = new Map<string, Finding>();
	for (const f of current) {
		currentById.set(String(f.id), f);
	}

	const emptyBaseline = baselineById.size === 0 && direction === "lower";

	let novel = [...currentById.keys()]
		.filter((id) => !baselineById.has(id))
		.sort(lexCompare);
	let stale = [...baselineById.keys()]
		.filter((id) => !currentById.has(id))
		.sort(lexCompare);

	// Greenfield / pre-adopt coverage: empty findings → floor only (no novelty/stale).
	if (emptyBaseline) {
		novel = [];
		stale = [];
	}

	const regressions: Array<{ id: string; baseline: number; current: number }> =
		[];
	const belowFloor: Array<{ id: string; floor: number; current: number }> = [];

	for (const [fid, cur] of currentById.entries()) {
		const curScore = cur.score;
		if (
			floor !== undefined &&
			typeof curScore === "number" &&
			curScore < floor
		) {
			belowFloor.push({ id: fid, floor, current: curScore });
		}

		if (emptyBaseline) continue;
		const base = baselineById.get(fid);
		if (!base) continue;
		const baseScore = base.score;
		if (typeof baseScore !== "number" || typeof curScore !== "number") continue;
		if (
			(direction === "higher" && curScore > baseScore) ||
			(direction === "lower" && curScore < baseScore)
		) {
			regressions.push({ id: fid, baseline: baseScore, current: curScore });
		}
	}

	regressions.sort((a, b) => lexCompare(a.id, b.id));
	belowFloor.sort((a, b) => lexCompare(a.id, b.id));

	const ok =
		novel.length === 0 &&
		stale.length === 0 &&
		regressions.length === 0 &&
		belowFloor.length === 0;

	return { ok, novel, stale, regressions, belowFloor };
}

export function rewriteBaseline(
	previous: BaselineFile,
	current: readonly Finding[],
): BaselineFile {
	const prevById = new Map<string, BaselineFinding>();
	for (const f of previous.findings ?? []) {
		prevById.set(String(f.id), f);
	}

	const sorted = [...current].sort((a, b) =>
		lexCompare(String(a.id), String(b.id)),
	);
	const findings: BaselineFinding[] = [];
	for (const f of sorted) {
		const fid = String(f.id);
		const prev = prevById.get(fid);
		const row: BaselineFinding = { id: fid };
		if (typeof f.score === "number") {
			row.score = f.score;
		} else if (prev && typeof prev.score === "number") {
			row.score = prev.score;
		}
		row.note =
			prev?.note && prev.note.length > 0
				? prev.note
				: "Added via --update; replace with owning issue note.";
		if (prev && typeof prev.issue === "number" && Number.isInteger(prev.issue)) {
			row.issue = prev.issue;
		}
		findings.push(row);
	}

	const out: BaselineFile = { version: 1, findings };
	for (const [k, v] of Object.entries(previous)) {
		if (k !== "version" && k !== "findings" && v !== undefined) {
			out[k] = v;
		}
	}
	return out;
}

export type PruneResult = {
	next: BaselineFile;
	pruned: number;
	tightened: number;
	changed: boolean;
};

export function pruneBaseline(
	previous: BaselineFile,
	current: readonly Finding[],
): PruneResult {
	const direction = worseWhenOf(previous);
	const currentById = new Map<string, Finding>();
	for (const f of current) {
		currentById.set(String(f.id), f);
	}

	const sortedPrev = [...(previous.findings ?? [])].sort((a, b) =>
		lexCompare(String(a.id), String(b.id)),
	);

	let pruned = 0;
	let tightened = 0;
	const findings: BaselineFinding[] = [];

	for (const prev of sortedPrev) {
		const fid = String(prev.id);
		const cur = currentById.get(fid);
		if (!cur) {
			pruned += 1;
			continue;
		}
		const row: BaselineFinding = { ...prev, id: fid };
		if (typeof prev.score === "number" && typeof cur.score === "number") {
			if (
				(direction === "higher" && cur.score < prev.score) ||
				(direction === "lower" && cur.score > prev.score)
			) {
				row.score = cur.score;
				tightened += 1;
			}
		}
		findings.push(row);
	}

	const next: BaselineFile = { version: 1, findings };
	for (const [k, v] of Object.entries(previous)) {
		if (k !== "version" && k !== "findings" && v !== undefined) {
			next[k] = v;
		}
	}

	return { next, pruned, tightened, changed: pruned > 0 || tightened > 0 };
}

export function reportFailure(result: CompareResult): void {
	if (result.novel.length > 0) {
		console.error("Novel findings (not in baseline):");
		for (const id of result.novel) console.error(`  + ${id}`);
	}
	if (result.stale.length > 0) {
		console.error(
			"Stale baseline rows (finding gone — run with --prune to remove):",
		);
		for (const id of result.stale) console.error(`  - ${id}`);
	}
	if (result.regressions.length > 0) {
		console.error("Score regressions (worse than baseline):");
		for (const r of result.regressions) {
			console.error(`  ^ ${r.id}  baseline=${r.baseline} current=${r.current}`);
		}
	}
	if (result.belowFloor.length > 0) {
		console.error("Below configured floor:");
		for (const r of result.belowFloor) {
			console.error(`  v ${r.id}  floor=${r.floor} current=${r.current}`);
		}
	}
}

export function runCompareCli(argv: readonly string[]): number {
	let baselinePath: string | undefined;
	let findingsPath: string | undefined;
	let resultJsonPath: string | undefined;
	let update = false;
	let prune = false;

	for (let i = 0; i < argv.length; i++) {
		const arg = argv[i];
		if (arg === "--baseline") {
			baselinePath = argv[++i];
		} else if (arg === "--findings") {
			findingsPath = argv[++i];
		} else if (arg === "--result-json") {
			resultJsonPath = argv[++i];
		} else if (arg === "--update") {
			update = true;
		} else if (arg === "--prune") {
			prune = true;
		} else {
			console.error(`Unknown argument: ${arg}`);
			return 2;
		}
	}

	if (!baselinePath || !findingsPath) {
		console.error(
			"Usage: tsx tools/quality/ratchets/compare.ts --baseline <path> --findings <path> [--update|--prune] [--result-json <path>]",
		);
		return 2;
	}

	if (update && prune) {
		console.error("Cannot combine --update and --prune.");
		return 2;
	}

	const baseline = JSON.parse(
		readFileSync(baselinePath, "utf8"),
	) as BaselineFile;
	const findingsFile = JSON.parse(
		readFileSync(findingsPath, "utf8"),
	) as FindingsFile;
	const findings = findingsFile.findings ?? [];

	if (update) {
		if (isCiEnv()) {
			console.error(
				"Refusing baseline --update under CI=true. Baseline growth is a conscious human act.",
			);
			return 2;
		}
		const next = rewriteBaseline(baseline, findings);
		writeFileSync(baselinePath, `${JSON.stringify(next, null, "\t")}\n`, "utf8");
		console.log(
			`Updated baseline ${baselinePath} (${next.findings.length} findings).`,
		);
		return 0;
	}

	if (prune) {
		if (isCiEnv()) {
			console.error(
				"Refusing baseline --prune under CI=true. Baseline changes are local only.",
			);
			return 2;
		}
		const original = compareFindings(baseline, findings);
		const { next, pruned, tightened, changed } = pruneBaseline(
			baseline,
			findings,
		);
		if (changed) {
			writeFileSync(
				baselinePath,
				`${JSON.stringify(next, null, "\t")}\n`,
				"utf8",
			);
			if (!resultJsonPath) {
				console.log(
					`Pruned ${pruned} stale / tightened ${tightened} rows in ${baselinePath}.`,
				);
			}
		}
		const remaining: CompareResult = {
			ok:
				original.novel.length === 0 &&
				original.regressions.length === 0 &&
				original.belowFloor.length === 0,
			novel: original.novel,
			stale: [],
			regressions: original.regressions,
			belowFloor: original.belowFloor,
		};
		if (resultJsonPath) {
			writeFileSync(
				resultJsonPath,
				`${JSON.stringify(remaining, null, "\t")}\n`,
				"utf8",
			);
		}
		if (!remaining.ok) {
			if (!resultJsonPath) {
				reportFailure(remaining);
			}
			return 1;
		}
		if (!changed && !resultJsonPath) {
			console.log(`Ratchet OK (${findings.length} findings match baseline).`);
		}
		return 0;
	}

	const result = compareFindings(baseline, findings);
	if (resultJsonPath) {
		writeFileSync(
			resultJsonPath,
			`${JSON.stringify(result, null, "\t")}\n`,
			"utf8",
		);
	}
	if (!result.ok) {
		if (!resultJsonPath) {
			reportFailure(result);
		}
		return 1;
	}
	if (!resultJsonPath) {
		console.log(`Ratchet OK (${findings.length} findings match baseline).`);
	}
	return 0;
}

function isMainModule(): boolean {
	const entry = process.argv[1];
	if (!entry) return false;
	try {
		return import.meta.url === pathToFileURL(realpathSync(entry)).href;
	} catch {
		return import.meta.url === pathToFileURL(resolve(entry)).href;
	}
}

if (isMainModule()) {
	process.exit(runCompareCli(process.argv.slice(2)));
}
