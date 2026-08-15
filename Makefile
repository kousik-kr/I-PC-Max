SHELL := /usr/bin/env bash

ALGORITHM ?= pace-b
ABLATION ?= none
DATASET ?= demo
QUERIES ?= experiments/manifests/tiny.jsonl
THETA ?= 2
L ?= 32
K ?= 16
THREADS ?= 1
REPETITIONS ?= 1
OUTPUT ?= results/raw/manual.jsonl
CONFIG ?= experiments/configs/smoke.yaml
RESULT_INPUT ?= results/raw
PAPER_CONFIG ?= experiments/configs/paper_q1.yaml
PAPER_SERVER_CONFIG ?= experiments/configs/paper_q1_server_24c_250g.yaml
PAPER_SMOKE_CONFIG ?= experiments/configs/paper_smoke.yaml
RUN_ID ?= pace_q1_plan
PAPER_SMOKE_RUN_ID ?= pace_q1_smoke_final_v4
BACKEND ?= local
MAX_CONCURRENT ?= 1
PAPER_SERVER_RUN_ID ?= pace_q1_two_track_server_24c_250g
PAPER_SERVER_MAX_CONCURRENT ?= 1
NYC_ROOT ?= case_studies/nyc_shuttle
NYC_PY ?= $(NYC_ROOT)/.venv/bin/python
NYC_JAVA_CP ?= target/pace-bench.jar
NYC_DATASET ?= $(NYC_ROOT)/processed/NYC-REAL
NYC_QUERIES ?= $(NYC_ROOT)/manifests/nyc_queries.jsonl
NYC_RESULTS ?= $(NYC_ROOT)/results/nyc_case_results.jsonl
NYC_FINAL ?= experiments/results/Final-result/NYC-real-shuttle
NYC_BASELINE_THREADS ?= 8
NYC_TRAFFIC_DURATION_HOURS ?=

.PHONY: configure build test test-unit test-integration test-experiments benchmark-smoke \
	validate-results summarize-results clean-experiments run-candidate run-ablation run-matrix
.PHONY: paper-preflight paper-smoke paper-plan paper-reproduce paper-clean
.PHONY: paper-generate-assets paper-validate-assets paper-resume-assets paper-plan-assets
.PHONY: paper-generate-queries paper-validate-queries paper-resume-queries paper-plan-queries
.PHONY: paper-preflight-server paper-reproduce-server paper-monitor-server
.PHONY: nyc-setup nyc-download nyc-audit nyc-map nyc-build-profiles nyc-build-scores
.PHONY: nyc-queries nyc-run nyc-analyze nyc-figures nyc-report nyc-finalize nyc-collect-traffic

configure:
	@java -version
	@python3 scripts/run_maven.py -version

build:
	@bash scripts/build_experiments.sh --release

test:
	@python3 scripts/run_maven.py test

test-unit:
	@bash scripts/test_experiments.sh --unit

test-integration:
	@bash scripts/test_experiments.sh --integration

test-experiments:
	@bash scripts/test_experiments.sh --all

benchmark-smoke:
	@python3 scripts/run_matrix.py --config experiments/configs/smoke.yaml --jobs 1 --resume

validate-results:
	@python3 scripts/validate_results.py --schema experiments/schemas/result_record.schema.json \
		--expected-manifest $(QUERIES) $(RESULT_INPUT)

summarize-results:
	@python3 scripts/summarize_results.py --output-dir results/summaries $(RESULT_INPUT)

clean-experiments:
	@rm -rf results/raw results/profiles results/summaries results/summary results/logs results/manifests

run-candidate:
	@bash scripts/run_candidate.sh --algorithm $(ALGORITHM) --dataset $(DATASET) --queries $(QUERIES) \
		--theta $(THETA) --anchor-limit $(L) --k $(K) --threads $(THREADS) \
		--repetitions $(REPETITIONS) --output $(OUTPUT)

run-ablation:
	@bash scripts/run_ablation.sh --ablation $(ABLATION) --dataset $(DATASET) --queries $(QUERIES) \
		--theta $(THETA) --anchor-limit $(L) --k $(K) --threads $(THREADS) --output $(OUTPUT)

run-matrix:
	@python3 scripts/run_matrix.py --config $(CONFIG) --jobs 1

paper-preflight:
	@python3 experiments/scripts/preflight.py --config $(PAPER_CONFIG)

paper-generate-assets:
	@python3 experiments/scripts/generate_dataset_assets.py --config $(PAPER_SERVER_CONFIG) --overwrite

paper-validate-assets:
	@python3 experiments/scripts/generate_dataset_assets.py --config $(PAPER_SERVER_CONFIG) --validate-only

paper-resume-assets:
	@python3 experiments/scripts/generate_dataset_assets.py --config $(PAPER_SERVER_CONFIG) --resume

paper-plan-assets:
	@python3 experiments/scripts/generate_dataset_assets.py --config $(PAPER_SERVER_CONFIG) --plan-only

paper-generate-queries:
	@python3 experiments/scripts/generate_query_sets.py --config $(PAPER_SERVER_CONFIG) --overwrite

paper-validate-queries:
	@python3 experiments/scripts/generate_query_sets.py --config $(PAPER_SERVER_CONFIG) --validate-only

paper-resume-queries:
	@python3 experiments/scripts/generate_query_sets.py --config $(PAPER_SERVER_CONFIG) --resume

paper-plan-queries:
	@python3 experiments/scripts/generate_query_sets.py --config $(PAPER_SERVER_CONFIG) --plan-only

paper-preflight-server:
	@python3 experiments/scripts/preflight.py --config $(PAPER_SERVER_CONFIG)

paper-smoke:
	@python3 scripts/run_maven.py test
	@python3 experiments/scripts/run_all.py --config $(PAPER_SMOKE_CONFIG) --run-id $(PAPER_SMOKE_RUN_ID) \
		--backend local --stages all --resume --max-concurrent 1

paper-plan:
	@python3 experiments/scripts/run_all.py --config $(PAPER_CONFIG) --run-id $(RUN_ID) \
		--backend $(BACKEND) --stages all --plan-only --resume

paper-reproduce:
	@if [ -z "$(RUN_ID)" ] || [ "$(RUN_ID)" = "pace_q1_plan" ]; then \
		echo "Set an immutable release run ID: make paper-reproduce RUN_ID=<id> BACKEND=local|slurm" >&2; exit 2; \
	fi
	@python3 experiments/scripts/run_all.py --config $(PAPER_CONFIG) --run-id $(RUN_ID) \
		--backend $(BACKEND) --stages all --resume --max-concurrent $(MAX_CONCURRENT)

paper-reproduce-server:
	@PAPER_SERVER_CONFIG=$(PAPER_SERVER_CONFIG) PAPER_SERVER_RUN_ID=$(PAPER_SERVER_RUN_ID) \
		BACKEND=$(BACKEND) PAPER_SERVER_MAX_CONCURRENT=$(PAPER_SERVER_MAX_CONCURRENT) \
		bash scripts/run_paper_q1_server.sh

paper-monitor-server:
	@PAPER_SERVER_CONFIG=$(PAPER_SERVER_CONFIG) PAPER_SERVER_RUN_ID=$(PAPER_SERVER_RUN_ID) \
		bash scripts/monitor_paper_q1_server.sh

paper-clean:
	@python3 experiments/scripts/clean_run.py --config $(PAPER_CONFIG) --run-id $(RUN_ID) --confirm

# Isolated NYC real-world shuttle case study. The live collector is intentionally
# excluded from every ordinary dependency chain.
nyc-setup:
	@python3 -m venv $(NYC_ROOT)/.venv
	@$(NYC_ROOT)/.venv/bin/pip install -r $(NYC_ROOT)/requirements.txt

nyc-download:
	@$(NYC_PY) $(NYC_ROOT)/scripts/download_official_sources.py

nyc-collect-traffic:
	@if [ -z "$(NYC_TRAFFIC_DURATION_HOURS)" ]; then \
		echo "Set NYC_TRAFFIC_DURATION_HOURS, e.g. make nyc-collect-traffic NYC_TRAFFIC_DURATION_HOURS=24" >&2; exit 2; \
	fi
	@$(NYC_PY) $(NYC_ROOT)/scripts/collect_dot_traffic_speeds.py \
		--duration-hours $(NYC_TRAFFIC_DURATION_HOURS)

nyc-audit:
	@PYTHONPATH=$(NYC_ROOT)/scripts $(NYC_PY) -m unittest discover -s $(NYC_ROOT)/tests -v
	@python3 scripts/run_maven.py -Dtest=NycCaseStudyRunnerTest test

nyc-map:
	@$(NYC_PY) $(NYC_ROOT)/scripts/aggregate_dot_traffic_speeds.py \
		--input $(NYC_ROOT)/raw/dot_traffic_speeds --source-timezone America/New_York
	@$(NYC_PY) $(NYC_ROOT)/scripts/prepare_dimacs_ny.py
	@$(NYC_PY) $(NYC_ROOT)/scripts/map_real_data.py --stage all

nyc-build-profiles:
	@$(NYC_PY) $(NYC_ROOT)/scripts/build_temporal_graph.py

nyc-build-scores:
	@$(NYC_PY) $(NYC_ROOT)/scripts/build_scores.py

nyc-queries:
	@$(NYC_PY) $(NYC_ROOT)/scripts/select_terminal_pairs.py
	@python3 scripts/run_maven.py -DskipTests package
	@java -Xmx48g -cp $(NYC_JAVA_CP) edu.ipcmax.casestudy.nyc.NycQueryManifestBuilder \
		--dataset $(NYC_DATASET) \
		--terminal-pairs $(NYC_ROOT)/manifests/nyc_terminal_pairs.csv \
		--config $(NYC_ROOT)/config/case_study.yaml \
		--output $(NYC_QUERIES) \
		--exclusions $(NYC_ROOT)/manifests/nyc_query_exclusions.jsonl \
		--threads $(NYC_BASELINE_THREADS)

nyc-run:
	@python3 scripts/run_maven.py -DskipTests package
	@java -Xmx48g -cp $(NYC_JAVA_CP) edu.ipcmax.casestudy.nyc.NycShuttleCaseStudyBench \
		--dataset $(NYC_DATASET) --query-file $(NYC_QUERIES) --output $(NYC_RESULTS) \
		--theta 2 --pivot-limit-l 32 --frontier-limit-kf 16 --breakpoint-cap-mb 1000000 \
		--threads 1 --baseline-threads $(NYC_BASELINE_THREADS) --timeout-seconds 5 --resume

nyc-analyze:
	@$(NYC_PY) $(NYC_ROOT)/scripts/analyze_results.py --input $(NYC_RESULTS)

nyc-figures:
	@MPLCONFIGDIR=/tmp/pace-nyc-matplotlib $(NYC_PY) $(NYC_ROOT)/scripts/make_figures.py

nyc-report:
	@$(NYC_PY) $(NYC_ROOT)/scripts/make_tables.py
	@$(NYC_PY) $(NYC_ROOT)/scripts/build_reports.py

nyc-finalize:
	@$(NYC_PY) $(NYC_ROOT)/scripts/package_final_results.py --destination $(NYC_FINAL)
