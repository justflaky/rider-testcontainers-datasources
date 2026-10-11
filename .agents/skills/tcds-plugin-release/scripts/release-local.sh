#!/usr/bin/env bash
# Release the rider-testcontainers-datasources plugin end-to-end.
# Usage: release.sh <VERSION> [release body text]
# Bumps build.gradle.kts + repository/updatePlugins.xml (version & asset URL only —
# <change-notes> must be edited manually beforehand), runs test+buildPlugin,
# commits & pushes the two files, creates the GitHub release and uploads the zip.
set -euo pipefail

VERSION="${1:?usage: release.sh <VERSION> [release body]}"
BODY="${2:-"Release $VERSION"}"

# repo root = 4 levels up from this script (<repo>/.agents/skills/tcds-plugin-release/scripts)
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
REPO_SLUG="justflaky/rider-testcontainers-datasources"
ZIP="$REPO_DIR/build/distributions/rider-testcontainers-datasources-$VERSION.zip"
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/JetBrains/JetBrains Rider 2026.2.1/jbr}"

cd "$REPO_DIR"

echo "== 1/6 bump version to $VERSION"
sed -i "s/^version = .*/version = \"$VERSION\"/" build.gradle.kts
sed -i "s/version=\"[0-9][0-9.]*\"/version=\"$VERSION\"/" repository/updatePlugins.xml
sed -i "s|releases/download/v[0-9.][0-9.]*/rider-testcontainers-datasources-[0-9.][0-9.]*\.zip|releases/download/v$VERSION/rider-testcontainers-datasources-$VERSION.zip|" repository/updatePlugins.xml

echo "== 2/6 build (test + buildPlugin)"
./gradlew test buildPlugin --console=plain -q
[ -f "$ZIP" ] || { echo "ERROR: zip not found: $ZIP"; exit 1; }

echo "== 3/6 commit & push (only the two release files)"
git add build.gradle.kts repository/updatePlugins.xml
git commit -m "Release v$VERSION"
git push origin master

echo "== 4/6 create GitHub release"
CRED=$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill 2>/dev/null)
TOKEN=$(printf '%s' "$CRED" | sed -n 's/^password=//p')
[ -n "$TOKEN" ] || { echo "ERROR: no GitHub credential (git credential fill empty)"; exit 1; }

PAYLOAD=$(python -c "import json,sys; print(json.dumps({'tag_name':'v'+sys.argv[1],'target_commitish':'master','name':'v'+sys.argv[1],'body':sys.argv[2]}))" "$VERSION" "$BODY")
RID=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$REPO_SLUG/releases" -d "$PAYLOAD" \
  | python -c "import json,sys; d=json.load(sys.stdin); print(d.get('id') or ''); print('API error: '+str(d.get('message','')), file=sys.stderr) if not d.get('id') else None")
[ -n "$RID" ] || { echo "ERROR: release creation failed"; exit 1; }
echo "release id: $RID"

echo "== 5/6 upload zip asset"
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/zip" \
  --data-binary @"$ZIP" \
  "https://uploads.github.com/repos/$REPO_SLUG/releases/$RID/assets?name=$(basename "$ZIP")" \
  | python -c "import json,sys; d=json.load(sys.stdin); print('asset:', d.get('name'), '| state:', d.get('state')); sys.exit(0 if d.get('state')=='uploaded' else 1)"

echo "== 6/6 verify URLs (404 = repo is private)"
ASSET_CODE=$(curl -sI -o /dev/null -w "%{http_code}" -L "https://github.com/$REPO_SLUG/releases/download/v$VERSION/$(basename "$ZIP")")
XML_CODE=$(curl -sI -o /dev/null -w "%{http_code}" "https://raw.githubusercontent.com/$REPO_SLUG/master/repository/updatePlugins.xml")
echo "asset: HTTP $ASSET_CODE | updatePlugins.xml: HTTP $XML_CODE"

echo
echo "DONE v$VERSION"
echo "  release: https://github.com/$REPO_SLUG/releases/tag/v$VERSION"
echo "  asset:   https://github.com/$REPO_SLUG/releases/download/v$VERSION/$(basename "$ZIP")"
echo "  commit:  $(git rev-parse --short HEAD)"
