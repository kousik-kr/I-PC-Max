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

.PHONY: configure build test test-unit test-integration test-experiments benchmark-smoke \
	validate-results summarize-results clean-experiments run-candidate run-ablation run-matrix

configure:
	@java -version
	@mvn -version

build:
	@bash scripts/build_experiments.sh --release

test:
	@mvn test

test-unit:
	@bash scripts/test_experiments.sh --unit

test-integration:
	@bash scripts/test_experiments.sh --integration

test-experiments:
	@bash scripts/test_experiments.sh --all

benchmark-smoke:
	@python scripts/run_matrix.py --config experiments/configs/smoke.yaml --jobs 1 --resume

validate-results:
	@python scripts/validate_results.py --schema experiments/schemas/result_record.schema.json \
		--expected-manifest $(QUERIES) $(RESULT_INPUT)

summarize-results:
	@python scripts/summarize_results.py --output-dir results/summaries $(RESULT_INPUT)

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
	@python scripts/run_matrix.py --config $(CONFIG) --jobs 1
