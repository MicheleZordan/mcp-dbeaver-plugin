#!/usr/bin/env bash
# Removes the plugin from a local DBeaver installation. DBeaver must be closed.
set -euo pipefail
FEATURE="com.afliant.dbeaver.mcp.feature.feature.group"
if [[ -n "${DBEAVER_HOME:-}" ]]; then
  LAUNCHER="$DBEAVER_HOME/dbeaver"
else
  LAUNCHER="${DBEAVER_APP:-/Applications/DBeaver.app}/Contents/MacOS/dbeaver"
fi
if pgrep -f "$LAUNCHER" >/dev/null 2>&1; then
  echo "DBeaver is running. Quit it first." >&2
  exit 1
fi
"$LAUNCHER" -nosplash -consoleLog -data "${TMPDIR:-/tmp}/dbeaver-mcp-director/workspace" \
  -application org.eclipse.equinox.p2.director -uninstallIU "$FEATURE"
