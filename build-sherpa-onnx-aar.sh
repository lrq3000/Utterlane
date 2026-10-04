#!/usr/bin/env bash
set -euo pipefail
# Compatibility entry point. Python owns the portable, pinned build procedure;
# unlike the old upstream Android script it uses no GitHub-release binary ZIPs.
exec python3 "$(dirname "$0")/tools/build_sherpa.py" "$@"
