# quality-kit kotlin-android gates — see AGENTS.quality.md
SHELL := /bin/bash
QUALITY := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
ROOT := $(abspath $(QUALITY)/../..)
KOTLIN_Q := $(QUALITY)/kotlin-android

.PHONY: gate gate-fast gate-diff format format-fix secrets vulns screenshots-record screenshots-compare doctor help

help:
	@echo "Targets:"
	@echo "  make gate-diff            changed-file ktfmt + secrets + file-size (+ qualityFast when Kotlin/Gradle changed)"
	@echo "  make gate-fast            ktfmt + secrets + detekt + compile"
	@echo "  make gate                 gate-fast + osv-scanner + lint + tests + screenshots + coverage + buildHealth"
	@echo "  make format               ktfmt check"
	@echo "  make format-fix           ktfmt in place"
	@echo "  make secrets              Betterleaks scan"
	@echo "  make vulns                osv-scanner lockfile scan"
	@echo "  make screenshots-record   re-record Roborazzi goldens"
	@echo "  make screenshots-compare  write *_compare.png diffs without failing"
	@echo "  make doctor               check JDK / Android SDK / tools"

gate-diff:
	@$(QUALITY)/gate.sh diff

gate-fast:
	@$(QUALITY)/gate.sh fast

gate:
	@$(QUALITY)/gate.sh full

format:
	@$(KOTLIN_Q)/run-format.sh

format-fix:
	@$(KOTLIN_Q)/run-format.sh --fix

secrets:
	@$(QUALITY)/run-secret-scan.sh

vulns:
	@$(QUALITY)/run-vuln-scan.sh

screenshots-record:
	@$(KOTLIN_Q)/screenshots.sh record

screenshots-compare:
	@$(KOTLIN_Q)/screenshots.sh compare

doctor:
	@$(KOTLIN_Q)/doctor.sh
