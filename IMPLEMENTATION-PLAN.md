# Plan realizacji IMPROVEMENTS.md

Stan wyjściowy: v0.1.1 (commit `454d477`+), 27/27 testów, registrar z dopasowaniem sterowników
przez `DatabaseDriver.matchesUrl()`. Kolejność kamieni milowych odzwierciedla ryzyko i zależności;
po M1–M4 wydajemy **0.2.0** skillem release'owym.

## Kamienie milowe

| M | Zakres | Szacunek | Bramka weryfikacyjna |
|---|---|---|---|
| M1 | B1 + B2 (registrar) | ~1,5 h | testy jednostkowe + e2e sandbox (reconnect + cudzy DS przeżywa reconcile) |
| M2 | B3 + korutyny + martwy kod + dokumentacja | ~45 min | zbudowany plugin.xml zawiera until-build; `test` zielony; e2e: sync nadal się odpala |
| M3 | Settings UI + `dockerUnavailable` | ~45 min | strona ustawień otwiera się w sandboxie; kompilacja |
| M4 | CI (GitHub Actions) | ~30 min | zielony run na GitHubie po pushu |
| M5 | MongoDB (wchodzimy) / watcher app-level (odkładamy) / release task (zamykamy) | ~30 min (mongo) | testy + opcjonalne e2e |
| — | Release 0.2.0 | ~10 min | `release.sh` przez skill, verifier Compatible |

---

## M1 — B1: idempotentne rejestrowanie (koniec „migania" DS i spamu balonów)

**Plik:** `src/main/kotlin/tcds/ds/DataSourceRegistrar.kt`.

1. Wyekstrahować czystą decyzję (testowalną bez IDE) — nowy plik
   `src/main/kotlin/tcds/ds/RegistrationDecision.kt`:
   ```kotlin
   sealed interface RegistrationDecision {
       data object CreateNew : RegistrationDecision
       data object NoChange : RegistrationDecision
       data class UpdateInPlace(val url: String, val user: String) : RegistrationDecision
   }
   fun registrationDecision(existingUrl: String?, incomingUrl: String): RegistrationDecision
   ```
2. W `registerContainer` (obecnie: zawsze remove→add→notify):
   - `tracked(info.id) == null` → dotychczasowa ścieżka tworzenia (z `matchesUrl`);
   - istnieje i `existing.url == target.url` → `NoChange` (log INFO „already up to date", **bez**
     powiadomienia i bez remove/add);
   - istnieje, URL inny (restart kontenera na nowym porcie) → `UpdateInPlace`:
     `existing.url = target.url; existing.username = target.user; manager.saveDataSource(existing)`;
     `scheduleSync(existing)`; log INFO; bez balonu.
3. Testy: `RegistrationDecisionTest` — 3 przypadki (null/identyczny/inny).

**Bramka e2e:** sandbox + `dotnet test`; potem zabić proces pluginu `docker events`
(`docker.exe` z „events testcontainers" w command line) → po ~10 s reconnect i
`reconcileSnapshot` re-rejestruje żywe kontenery → w logu wyłącznie
„already up to date", zero linii „registered data source" po raz drugi, DS nie znika.

## M1 — B2: marker własności zamiast heurystyki nazwy

**Pliki:** `DataSourceRegistrar.kt`, nowy `src/main/kotlin/tcds/ds/DsOwnership.kt`.

1. Przy tworzeniu DS: `ds.setAdditionalProperty("tcds.containerId", info.id)`
   (`set/getAdditionalProperty` publiczne w RD-262; additional properties są persistowane
   w konfiguracji DS — zweryfikować w e2e po restarcie sandboxa).
2. `tracked(id)`: najpierw `ds.getAdditionalProperty("tcds.containerId") == id`, potem fallback
   nazwowy.
3. Zaostrzyć fallback nazwowy — nowa funkcja czysta w `DsOwnership.kt`:
   ```kotlin
   fun ownedContainerId(additionalId: String?, name: String, prefix: String,
                        knownImageBases: Set<String>): String?
   ```
   Nazwa musi pasować do `^<prefix> <imageBase> [0-9a-f]{7}$`, gdzie `imageBase` pochodzi z
   katalogu obrazów (postgres/mysql/mariadb/server-mcr/redis/…). To eliminuje false-positive
   typu „TC staging deadbee" (słowo „staging" nie jest znanym obrazem).
4. `reconcile` korzysta z `ownedContainerId` (marker > fallback).
5. Testy: `DsOwnershipTest` — marker, fallback zgodny, cudza nazwa odrzucona, prefiks niestandardowy.

**Bramka e2e (razem z B1):** w sandboxie utworzyć ręcznie DS „TC staging deadbee" → odpalić
test → reconcile nie usuwa go; pluginowy DS znika normalnie po kontenerze.

## M2 — B3 + sprzątanie + dokumentacja

1. **B3 `until-build`** — `build.gradle.kts`:
   ```kotlin
   pluginConfiguration {
       version = project.version.toString()
       ideaVersion { untilBuild = provider { "262.*" } }
   }
   ```
   Bramka: rozpakować zbudowany zip i sprawdzić `<idea-version … until-build="262.*"/>`.
   Do README (sekcja „Ograniczenia"): przy nowej linii Ridera podnieść `untilBuild` + odpalić
   `verifyPlugin` — to świadoma polityka bezpieczeństwa na pół-internal API.
2. **Martwy kod:** usunąć `asSafeObject` (`DockerJson.kt`) i `trackedNames`
   (`DataSourceRegistrar.kt`). `dockerUnavailable` zostaje — wtapia go M3.
3. **Korutyny zamiast `Thread.sleep`:** projektowy serwis dostaje wstrzyknięty
   `CoroutineScope` (`class TcWatcherService(project, cs: CoroutineScope)` — wspierane od
   2024.x), a `scheduleSync` przepisuje się na
   `cs.launch { delay(SYNC_DELAY_MS); withContext(Dispatchers.EDT) { … } }` — przerywalne
   przy dispose (obecnie wątek ze sleepem ucieka po zamknięciu projektu).
4. **`redisPassword`/`pluginOwnedShortId` jako funkcje czyste:** `pluginOwnedShortId` —
   ekstrakcja w M1-B2 (`DsOwnership.kt`); `redisPassword` — pozostaje prywatna w katalogu,
   bo jest już w pełni pokryta testami przez `DatabaseImageCatalog.resolve(...)` (testy
   requirepass/env/encodowanie) — odhaczyć z uzasadnieniem.
5. **Dokumentacja:** README — sekcja „Konfiguracja": `TCDS_DOCKER_BINARY` (nadpisanie binarki
   dockera), `tcds.xml` (`enabled`, `namePrefix`), prefiks nazw DS.
6. **Opcjonalne e2e mysql/mssql — decyzja: pomijamy w 0.2.0.** Ścieżka runtime wspólna
   (eventy → inspect → katalog → `matchesUrl`), różnice baz pokryte testami jednostkowymi;
   wrócić, gdyby katalog zdywergował per-baza (np. osobne buildery URL z parametrami).

## M3 — Settings UI + powiadomienie o braku Dockera

1. **`TcSettingsConfigurable`** — nowy plik `src/main/kotlin/tcds/TcSettingsConfigurable.kt`:
   ```kotlin
   class TcSettingsConfigurable : BoundConfigurable("Testcontainers Data Sources", "tcds.settings") {
       init {
           val s = TcSettings.getInstance()
           checkBox("Watch for Testcontainers containers", s::enabled)
           textField("Data source name prefix", s::namePrefix)
       }
   }
   ```
   `plugin.xml`: `<applicationConfigurable parentId="tools" instance="tcds.TcSettingsConfigurable"/>`.
   BoundConfigulatory obsługuje apply/reset/isModified automatycznie (bindowanie przez
   getter/setter `TcSettings`).
2. **`dockerUnavailable`** — w `TcWatcherService.ensureWatching()` gałąź „daemon == null":
   ```kotlin
   if (!dockerWarnedOnce) { TcNotifications.dockerUnavailable(project); dockerWarnedOnce = true }
   ```
   (@Volatile pole; reset na `false` po udanym połączeniu). Balloon raz do odzyskania demona,
   żeby nie spamować, gdy Dockera nie ma wcale.

## M4 — CI

`.github/workflows/ci.yml`:
```yaml
name: CI
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 25 }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew test buildPlugin verifyPlugin --console=plain
      - uses: actions/upload-artifact@v4
        if: always()
        with: { name: plugin-zip, path: build/distributions/*.zip }
```
Uwagi: na ubuntu `JAVA_HOME` z setup-java zastępuje domyślny JBR z Windows (skrypt release'owy
i tak ma override env); `verifyPlugin` pobiera IDE (~10 min jobu); `gradle.properties` nie
pinuje `org.gradle.java.home` (wykomentowane) — nic nie koliduje.
**Bramka:** zielony run po pushu (Actions działa też w repo prywatnym w ramach limitów).

## M5 — rozszerzenia (decyzje)

- **MongoDB — robimy:** wpis w katalogu (obraz `mongo`, port 27017,
  `MONGO_INITDB_ROOT_USERNAME/PASSWORD`; URL `mongodb://user:pass@host:port/` z encodowaniem
  lub bez auth `mongodb://host:port`; `TcDbms.MONGO`; sterownik dopasuje `matchesUrl` do
  predefiniowanego drivera mongo — `Dbms.MONGO` potwierdzone w dumpie). Testy jednostkowe;
  opcjonalnie test w `tc-demo` (`Testcontainers.MongoDb`).
- **Watcher app-level — odkładamy (po 0.2.0):** szkic: serwis aplikacyjny trzyma `docker events`,
  projekty subskrybują przez topic na MessageBus (`Topic.create("tcds.containers", Listener)`),
  registrar per projekt. Zysk: 1 proces zamiast N; koszt: ~2 h + przepisanie testów e2e.
- **Gradle task release'owy — ZAMYKAMY jako zastąpiony:** release automatyzuje
  `.agents/skills/tcds-plugin-release` (commit `b1dd258`); dublowanie w Gradle nie wnosi wartości.
  Odhaczyć w IMPROVEMENTS.md z adnotacją „superseded by release skill".

## Release 0.2.0 (po M1–M4; M5-mongo opcjonalnie przed)

1. Uzupełnić `<change-notes>` w `repository/updatePlugins.xml` (B1/B2/B3/settings/CI/mongo).
2. `bash .agents/skills/tcds-plugin-release/scripts/release.sh 0.2.0 "<body>"`.
3. Zweryfikować: build zielony, asset uploaded, HTTP 200/404 (prywatne repo), verifier
   Compatible na 262.

## Ryzyka

- Persistencja `additionalProperties` między sesjami IDE — jeśli e2e pokaże, że marker nie
  przeżywa restartu, fallback nazwowy (zaostrzony) pozostaje jedynym mechanizmem (akceptowalne).
- `BoundConfigurable` z parametrem `id` — jeśli konstruktor dwuargumentowy nie istnieje w 262,
  nadpisać `getId()`; wykryje kompilacja.
- `saveDataSource` vs `fireDataSourceUpdated` — jeśli UI nie odświeży URL po UpdateInPlace,
  dodać `fireDataSourceUpdated` po `saveDataSource`.

## Sprzątanie po każdym kamieniu

Odhaczyć pozycje w IMPROVEMENTS.md, commit per kamień milowy (`M1: idempotent registration
+ ownership marker`, …), testy `./gradlew test` zielone przed commitem.
