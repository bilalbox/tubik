/**
 * Shared entry scaffolding for Node/TS tool-specific ratchets.
 * Collect findings → compare in-process against committed baseline via compare.ts → optional --update or --prune.
 */

import { readFileSync, writeFileSync } from "node:fs";
import {
	type BaselineFile,
	type CompareResult,
	compareFindings,
	type Finding,
	isCiEnv,
	pruneBaseline,
	reportFailure,
	rewriteBaseline,
} from "./compare";

export type CollectFindings = () => Finding[] | Promise<Finding[]>;

function parseFlags(argv: string[]): { update: boolean; prune: boolean } {
	return {
		update: argv.includes("--update"),
		prune: argv.includes("--prune"),
	};
}

export async function runRatchetTool(options: {
	name: string;
	baselinePath: string;
	collect: CollectFindings;
	argv?: string[];
	/** Extra guidance printed after a failed compare (locations, fix command). */
	onFailure?: (result: CompareResult) => void;
}): Promise<void> {
	const { update, prune } = parseFlags(options.argv ?? process.argv.slice(2));
	if (update && prune) {
		console.error("Cannot combine --update and --prune.");
		process.exit(2);
	}
	if (update && isCiEnv()) {
		console.error(
			"Refusing baseline --update under CI=true. Baseline growth is a conscious human act.",
		);
		process.exit(2);
	}
	if (prune && isCiEnv()) {
		console.error(
			"Refusing baseline --prune under CI=true. Baseline changes are local only.",
		);
		process.exit(2);
	}

	const findings = await options.collect();
	const baseline = JSON.parse(
		readFileSync(options.baselinePath, "utf8"),
	) as BaselineFile;

	if (update) {
		const next = rewriteBaseline(baseline, findings);
		writeFileSync(
			options.baselinePath,
			`${JSON.stringify(next, null, "\t")}\n`,
			"utf8",
		);
		console.log(
			`Updated baseline ${options.baselinePath} (${next.findings.length} findings).`,
		);
		process.exit(0);
	}

	if (prune) {
		const original = compareFindings(baseline, findings);
		const { next, pruned, tightened, changed } = pruneBaseline(
			baseline,
			findings,
		);
		if (changed) {
			writeFileSync(
				options.baselinePath,
				`${JSON.stringify(next, null, "\t")}\n`,
				"utf8",
			);
			console.log(
				`Pruned ${pruned} stale / tightened ${tightened} rows in ${options.baselinePath}.`,
			);
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
		if (!remaining.ok) {
			console.error(`${options.name} ratchet failed:`);
			reportFailure(remaining);
			options.onFailure?.(remaining);
			process.exit(1);
		}
		if (!changed) {
			console.log(
				`${options.name} ratchet OK (${findings.length} findings match baseline).`,
			);
		}
		process.exit(0);
	}

	const result = compareFindings(baseline, findings);
	if (result.ok) {
		console.log(
			`${options.name} ratchet OK (${findings.length} findings match baseline).`,
		);
		process.exit(0);
	}

	console.error(`${options.name} ratchet failed:`);
	reportFailure(result);
	options.onFailure?.(result);
	process.exit(1);
}
