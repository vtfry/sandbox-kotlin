-include Makefile.local

.PHONY: run parse check proxies clean help

.DEFAULT_GOAL := help

MAKEFLAGS += --no-print-directory

DEFAULT_SCRIPT ?= proxyParser
DEFAULT_OUT_FILE ?= downloaded-proxies.txt
OUT_FILE ?= $(or $(PROXY_FILE_NAME),$(DEFAULT_OUT_FILE))

RUN_ARGS := $(wordlist 2,$(words $(MAKECMDGOALS)),$(MAKECMDGOALS))
$(eval $(RUN_ARGS):;@:)
SCRIPT_NAME := $(if $(RUN_ARGS),$(RUN_ARGS),$(DEFAULT_SCRIPT))
CLEAN_SCRIPT := $(patsubst %.kts,%,$(SCRIPT_NAME))

help:
	@echo "Usage: make [target] [arguments]"
	@echo ""
	@echo "Single script execution:"
	@echo "  run [name]     - Run a specific script by name (default: $(DEFAULT_SCRIPT))"
	@echo "  parse          - Shortcut to run proxyParser"
	@echo "  check          - Shortcut to run proxyChecker"
	@echo ""
	@echo "Pipelines & utils:"
	@echo "  proxies        - Fetch proxies, then validate results"
	@echo "  clean          - Remove build artifacts and data files ($(OUT_FILE))"
	@echo "  help           - Show this help message"

run:
	@echo "Run script: $(CLEAN_SCRIPT).kts ..."
	@kotlin $(CLEAN_SCRIPT).kts

parse:
	@kotlin proxyParser.kts

check:
	@kotlin proxyChecker.kts

proxies:
	@echo "Starting full proxy processing pipeline..."
	@$(MAKE) parse
	@echo "Parsing completed successfully. Proceeding to validation..."
	@$(MAKE) check
	@echo "Pipeline finished successfully."

clean:
	rm -f $(OUT_FILE) $(OUT_FILE).bak
	rm -rf .gradle build
