.PHONY: run clean format help

.DEFAULT_GOAL := help

DEFAULT_SCRIPT := proxyParser
SCRIPT ?= $(DEFAULT_SCRIPT)

DEFAULT_OUT_FILE := downloaded-proxies.txt
OUT_FILE := $(or $(PROXY_FILE_NAME),$(DEFAULT_OUT_FILE))

help:
	@echo "Usage: make [target]"
	@echo "Targets:"
	@echo "  run    - Run specific Kotlin script (default: $(DEFAULT_SCRIPT))"
	@echo "  clean  - Remove generated files and build artifacts"
	@echo "  help   - Show this help message"

run:
	kotlin $(SCRIPT).kts

clean:
	rm -f $(OUT_FILE) $(OUT_FILE).bak
	rm -rf .gradle build
