#!/usr/bin/env bash
# Release the rider-testcontainers-datasources plugin via the CI tag workflow.
# Usage: release.sh <VERSION> [notes]
# Bumps build.gradle.kts + repository/updatePlugins.xml (version & asset URL only —
# <change-notes> must be edited manually beforehand), runs a local sanity build,
# commits & pushes the two files, then pushes tag v<VERSION>.
# The GitHub Actions "Release" workflow builds, verifies and creates the release
# with the zip asset under the URL that updatePlugins.xml points at.
# Offline fallback (no CI): scripts/release-local.sh <VERSION> [body]
set -euo pipefail

VERSION="${1:?usage: release.sh <VERSION> [notes]}"
NOTES="${2:-"Release $VERSION"}"

# repo root = 4 levels up from this script (<repo>/.agents/skills/tcds-plugin-release/scripts)
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
REPO_SLUG="justflaky/rider-testcontainers-datasources"
ZIP="$REPO_DIR/build/distributions/rider-testcontainers-datasources-$VERSION.zip"
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/JetBrains/JetBrains Rider 2026.2.1/jbr}"

cd "$REPO_DIR"

echo "== 1/5 bump version to $VERSION"
sed -i "s/^version = .*/version = \"$VERSION\"/" build.gradle.kts
sed -i "s/version=\"[0-9][0-9.]*\"/version=\"$VERSION\"/" repository/updatePlugins.xml
sed -i "s|releases/download/v[0-9.][0-9.]*/rider-testcontainers-datasources-[0-9.][0-9.]*\.zip|releases/download/v$VERSION/rider-testcontainers-datasources-$VERSION.zip|" repository/updatePlugins.xml

# updatePlugins.xml must stay well-formed - a corrupted tag broke the custom
# plugin repository once ("Connection failed" in the IDE's repository dialog)
python - <<'PYEOF' || { echo "ERROR: repository/updatePlugins.xml is not well-formed XML"; exit 1; }
import xml.etree.ElementTree as ET
ET.parse('repository/updatePlugins.xml')
print('updatePlugins.xml well-formed: OK')
PYEOF

echo "== 2/5 local sanity build (test + buildPlugin; CI runs the full verification)"
./gradlew test buildPlugin --console=plain -q
[ -f "$ZIP" ] || { echo "ERROR: zip not found: $ZIP"; exit 1; }

echo "== 3/5 commit & push (only the two release files)"
git add build.gradle.kts repository/updatePlugins.xml
git commit -m "Release v$VERSION

$NOTES"
git push origin master

echo "== 4/5 push tag v$VERSION (triggers the Release workflow)"
git tag "v$VERSION"
git push origin "v$VERSION"

echo "== 5/5 done — CI does the rest"
echo "  watch:    https://github.com/$REPO_SLUG/actions/workflows/release.yml"
echo "  release:  https://github.com/$REPO_SLUG/releases/tag/v$VERSION"
echo "  asset:    https://github.com/$REPO_SLUG/releases/download/v$VERSION/$(basename "$ZIP")"
echo "verify the asset URL returns HTTP 200 once the workflow is green"
