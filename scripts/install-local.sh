#!/usr/bin/env bash
# Installs or updates the plugin into a local DBeaver installation with the p2 director.
# DBeaver must be closed (p2 locks its profile while DBeaver runs).
#
#   ./scripts/install-local.sh            # install/update from releng/repository/target/repository
#   DBEAVER_APP=/path/to/DBeaver.app ./scripts/install-local.sh
#   DBEAVER_HOME=/opt/dbeaver ./scripts/install-local.sh   (Linux: folder containing the 'dbeaver' launcher)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$ROOT/releng/repository/target/repository"
FEATURE="com.afliant.dbeaver.mcp.feature.feature.group"

if [[ -n "${DBEAVER_HOME:-}" ]]; then
  LAUNCHER="$DBEAVER_HOME/dbeaver"
  PROCESS_PATTERN="$DBEAVER_HOME/dbeaver"
else
  DBEAVER_APP="${DBEAVER_APP:-/Applications/DBeaver.app}"
  LAUNCHER="$DBEAVER_APP/Contents/MacOS/dbeaver"
  PROCESS_PATTERN="$DBEAVER_APP/Contents/MacOS/dbeaver"
fi

[[ -x "$LAUNCHER" ]] || { echo "DBeaver launcher not found: $LAUNCHER" >&2; exit 1; }
[[ -d "$REPO" ]] || { echo "p2 repository not built. Run: mvn verify" >&2; exit 1; }
if pgrep -f "$PROCESS_PATTERN" >/dev/null 2>&1; then
  echo "DBeaver is running. Quit it first, then rerun this script." >&2
  exit 1
fi

WORK="${TMPDIR:-/tmp}/dbeaver-mcp-director"
mkdir -p "$WORK"
director() {
  "$LAUNCHER" -nosplash -consoleLog -data "$WORK/workspace" -application org.eclipse.equinox.p2.director "$@"
}

if director -listInstalledRoots 2>/dev/null | grep -q "^$FEATURE/"; then
  echo "Updating $FEATURE ..."
  director -repository "file:$REPO" -uninstallIU "$FEATURE" -installIU "$FEATURE"
else
  echo "Installing $FEATURE ..."
  director -repository "file:$REPO" -installIU "$FEATURE"
fi
echo "Done. Start DBeaver: the MCP menu appears in the main menu bar."
