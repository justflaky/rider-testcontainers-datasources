---
name: tcds-plugin-release
description: Release a new version of the Rider Testcontainers plugin from the rider-testcontainers-datasources repo — bumps version in build.gradle.kts and repository/updatePlugins.xml, runs test+buildPlugin, commits, pushes, and creates a GitHub release with the zip asset via the REST API. Use whenever the user asks to release or publish a new version of the Rider/Testcontainers plugin — e.g. "wydaj release", "zrób release 0.1.2", "nowa wersja pluginu", "opublikuj plugin", "release the plugin", even if no version number is given.
---

# Release: rider-testcontainers-datasources

Releases one version of the Rider plugin end-to-end. Everything is automated by
`scripts/release.sh` (next to this file); your job is the two decisions the script must not
make: the version number and the change notes.

## Fixed facts

- Repo = this repository (the skill lives in `.agents/skills/tcds-plugin-release/`); the
  script derives the repo root from its own location, so any clone works.
- Branch `master`, GitHub slug `justflaky/rider-testcontainers-datasources`.
- No standalone JDK and no `gh` CLI assumed. Build JVM defaults to Rider's JBR
  (`C:/Program Files/JetBrains/JetBrains Rider 2026.2.1/jbr`) — override by exporting
  `JAVA_HOME` to any JDK 25 before running the script. GitHub auth = token from
  `git credential fill` (the script handles it — do not install anything).
- The IDE's custom plugin repository is `repository/updatePlugins.xml` on `master`;
  its `url` must point at the release asset of the version being shipped.
- If the repo is private, anonymous verification URLs return 404 — that is expected
  until the repo is made public; the script prints the HTTP codes, you interpret them.

## Steps

1. **Pick the version.** Read `version = "..."` in `build.gradle.kts`. Default proposal:
   bump the patch segment. If the user named a version, use exactly that.
2. **Write the change notes** — edit `<change-notes>` in `repository/updatePlugins.xml`
   yourself (the script never touches this element; it only rewrites `version` and the
   asset `url`). Summarize what actually changed since the previous release (check
   `git log <prev-tag>..HEAD --oneline`). Polish or English, matching the existing style.
3. **Run the script** from the repo root (or anywhere — it cds itself):
   ```bash
   bash .agents/skills/tcds-plugin-release/scripts/release.sh <VERSION> "<release body>"
   ```
   It bumps both files, runs `./gradlew test buildPlugin`, commits **only those two
   files** (never `git add -A` — user files like `*.DotSettings.user` must not leak in),
   pushes, creates release `v<VERSION>` with the zip as an asset, and verifies URLs.
4. **Check the script's summary.** Green build + `state: uploaded` + HTTP 200 on both
   URLs = done. Interpret failures:
   - gradle "user-mapped section open" on gson jar → a sandbox IDE still runs;
     `powershell -File scripts/kill-ide.ps1` and rerun.
   - release API error "already_exists" → tag v<VERSION> exists; pick another version
     or delete the draft release first.
   - verification 404 while everything else succeeded → repo is private; remind the
     user the custom plugin repository only works once the repo is public.
5. **Report** to the user: version, commit, release URL, asset URL, and the
   updatePlugins.xml state (users with the custom repo configured get an auto-update).

## Rules that exist because of past incidents

- Rebuild before release — the zip must reflect current sources (a v0.1.0 zip once
  shipped a stale vendor tag).
- The script commits only `build.gradle.kts` and `repository/updatePlugins.xml`;
  your `<change-notes>` edit rides along in the second file. Anything else you changed
  must be committed separately **before** running the script, or it will not ship.
- Never print or persist the GitHub token; it lives only inside the script's variables.
