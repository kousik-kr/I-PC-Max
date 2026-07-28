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
PAPER_SERVER_RUN_ID ?= pace_q1_server_24c_250g
PAPER_SERVER_MAX_CONCURRENT ?= 24

.PHONY: configure build test test-unit test-integration test-experiments benchmark-smoke \
	validate-results summarize-results clean-experiments run-candidate run-ablation run-matrix
.PHONY: paper-preflight paper-smoke paper-plan paper-reproduce paper-clean
.PHONY: paper-generate-assets paper-validate-assets paper-resume-assets paper-plan-assets
.PHONY: paper-generate-queries paper-validate-queries paper-resume-queries paper-plan-queries
.PHONY: paper-preflight-server paper-reproduce-server paper-monitor-server

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
