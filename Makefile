CHLOGGEN_VERSION = v0.30.0
CHLOGGEN = go run go.opentelemetry.io/build-tools/chloggen@$(CHLOGGEN_VERSION)
CHLOGGEN_CONFIG = .chloggen/config.yaml

.PHONY: chlog-new chlog-validate chlog-preview chlog-update check-relocations

chlog-new:
	$(CHLOGGEN) new --config $(CHLOGGEN_CONFIG) --filename $(shell git branch --show-current)

chlog-validate:
	$(CHLOGGEN) validate --config $(CHLOGGEN_CONFIG)

chlog-preview:
	$(CHLOGGEN) update --config $(CHLOGGEN_CONFIG) --dry

chlog-update:
ifndef VERSION
	$(error VERSION is required, e.g. make chlog-update VERSION="0.2.0 - 2026-10-01")
endif
ifeq ($(shell printf '%s' '$(VERSION)' | cut -c1),v)
	$(error VERSION must not start with v. The tag to push is prefixed and the CHANGELOG.rst heading is not, and release.yml finds the section by matching the heading, so a v here renders a section the release cannot find. Drop it, e.g. make chlog-update VERSION="0.2.0 - 2026-10-01")
endif
	$(CHLOGGEN) update --config $(CHLOGGEN_CONFIG) --version "$(VERSION)"

check-relocations:
	scripts/check-relocations.sh
