# Plan realizacji — review #2 (po v0.2.0)

Punkty z review z 2026-10-11 (R1–R9 + pozycja odroczona). Trzy pakiety wydawnicze:
**A (0.2.1)** — prywatność + UX live + ikona + sprzątanie; **B (0.3.0)** — infrastruktura
(release z CI, korutyny, EAP early-warning); **C** — dokumentacja/Marketplace (wchodzi z 0.3.0).

| Pakiet | Zakres | Szacunek | Bramka |
|---|---|---|---|
| A → 0.2.1 | R1, R2, R6, R7 | ~2 h | 38+ testów, e2e: log bez haseł, proces events reaguje na toggle, ikona w zipie |
| B → 0.3.0 | R3, R4, R5 | ~2 h | release 0.3.0 **stworzony przez nowy workflow** (self-test R3); e2e reconnect po migracji korutyn |
| C (z 0.3.0) | R8, R9 | ~1,5 h | README z zrzutem/sekcją troubleshooting; plugin.xml vendor/opis EN |

---

## Pakiet A

### R1 — redakcja haseł w logach (30 min)

Nowy czysty util `src/main/kotlin/tcds/ds/RedactedUrl.kt`:

```kotlin
fun redactUrl(url: String): String = url.replace(Regex("(password=)[^;&]+"), "$1***")
```

Regex obejmuje obie konwencje naraz: query (`?password=x&…` — postgres/mysql/mariadb/redis/mongo)
i średnikowe (`;password=x;` — MSSQL; potwierdzone w katalogu, linia ~100). Podmiana w dwóch logach
registrarza (`registered data source…`, `updated data source…`). Balony zostają pełne — są na
ekranie właściciela credentiali; logi podróżują.

- Testy: `RedactedUrlTest` — 5 przypadków (query, średnik, bez credentiali, encodowane znaki
  specjalne w haśle, mongo `mongodb://user:pass@` — **uwaga**: mongo ma hasło też w userinfo!
  Dodać drugi regex `(//[^:/@]+:)[^@]+(@)` → `$1***$2`).
- Bramka: `grep password idea.log` po e2e pokazuje wyłącznie `password=***`.

### R2 — ustawienia działają na żywo (45 min)

1. Topic na app message bus — nowy `src/main/kotlin/tcds/TcTopics.kt`:
   ```kotlin
   interface TcSettingsChangedListener { fun settingsChanged(enabled: Boolean) }
   val TC_SETTINGS_TOPIC = Topic.create("tcds.settings", TcSettingsChangedListener::class.java)
   ```
2. `TcSettingsConfigurable.apply()` po zapisie stanu publikuje `syncPublisher(TC_SETTINGS_TOPIC).settingsChanged(s.enabled)`.
3. `TcWatcherService`: w init — `ApplicationManager.getApplication().messageBus.connect(this).subscribe(TC_SETTINGS_TOPIC) { enabled -> if (enabled) start() else stopWatching() }`;
   nowy `stopWatching()`: `stopped`-like bez zamykania schedulera — destroy `eventsProcess`,
   `streamAlive=false` (restart przez istniejący `start()`; `start()` idempotentny dzięki guardom).
4. Safety-net (działa nawet bez UI): `ensureWatching()` na każdym cyklu sprawdza
   `TcSettings.getInstance().enabled` — wyłączone → niszczy strumień i czeka.
   (`namePrefix` zmienia tylko nowe DS — dopisać w etykiecie pola w konfigurowalnym
   „applies to new data sources").
- Bramka: e2e z publikacją topicu z konsoli? Topic publish wymaga kodu w IDE — zamiast tego
  minimalna symulacja: po zmianie `tcds.xml` + restarcie watchera; właściwą bramką jest test
  manualny użytkownika (checkbox → proces `docker events` znika z `tasklist`, re-enable →
  log „stream opened"). Kompilacja + przegląd kodu jako gate automatyczny.

### R6 — ikona pluginu (30 min)

`src/main/resources/META-INF/pluginIcon.svg` + `pluginIcon_dark.svg` (40×40 viewBox):
prosty wektor — walec bazy danych wewnątrz konturu kontenera, dwukolorowy (motyw jasny/ciemny).
Nazwa pliku = autodetekcja przez platformę, zero zmian w plugin.xml.
- Bramka: zip zawiera oba pliki; w sandboxie plugin na liście ma ikonę (weryfikacja wzrokowa
  przy okazji bramki A).

### R7 — martwy kod (5 min)

Usunąć `DockerCli.isAvailable` (`DockerCli.kt:16`).

### Release 0.2.1

Istniejącym skillem (change-notes: „Passwords redacted in idea.log; Settings page takes effect
immediately; plugin icon").

---

## Pakiet B

### R3 — release z CI na tagach (45 min)

1. Nowy `.github/workflows/release.yml`:
   ```yaml
   on: { push: { tags: ['v*'] } }
   permissions: { contents: write }
   jobs:
     release:
       runs-on: ubuntu-latest
       steps:
         - uses: actions/checkout@v4
         - uses: actions/setup-java@v4
           with: { distribution: temurin, java-version: 25 }
         - uses: gradle/actions/setup-gradle@v4
         - run: ./gradlew test buildPlugin verifyPlugin --console=plain
         - uses: softprops/action-gh-release@v2
           with:
             files: build/distributions/*.zip
             generate_release_notes: true
   ```
2. Skrypt skilla odchudzić do „bump wersji + commit + push + `git tag vX && git push origin vX`"
   (czść API release znika — robi ją CI); SKILL.md: dopisać change-notes przed tagiem.
   Stary skrypt zostaje jako fallback offline.
- Bramka: **0.3.0 wydane tym workflowm** — zielony run, release z assetem, updatePlugins.xml
  wskazuje URL zgodny z tym, co wgrało CI.

### R4 — pełna migracja watchera na korutyny (1 h)

`TcWatcherService` bez `ScheduledExecutorService` i bez gołego `Thread`:

```kotlin
fun start() { if (!started) { started = true; cs.launch { watchLoop() } } }
private suspend fun watchLoop() {
    while (!stopped && !project.isDisposed) {
        if (!TcSettings.getInstance().enabled) { delay(5_000); continue }
        val version = withContext(Dispatchers.IO) { runCatching { cli.daemonVersion() }.getOrNull() }
        if (version == null) { warnDockerOnce(); delay(30_000); continue }
        openStreamAndAwait()   // czyta linie na Dispatchers.IO; po końcu strumienia:
        delay(10_000)          // reconnect
    }
}
```

- `handleEventLine` → zawiera `withContext(Dispatchers.IO) { cli.inspect(...) }` przed
  `registrar.registerContainer` (invokeLater w registrarze zostaje — EDT tylko tam).
- `dispose()` zostaje (destroy procesu); korutyny gaszą się z `cs` serwisu (cancel przy
  zamknięciu projektu).
- Snapshot na starcie pętli (po udanym `daemonVersion`) — obecna logika `reconcileSnapshot` bez
  zmian.
- Bramka: powtórka scenariuszy e2e z 0.2.0 (probe z `-p`: NoChange po padzie strumienia,
  usunięcie po `docker rm`); 38 testów jednostkowych nietkniętych.

### R5 — early-warning na kolejną linię Ridera (20 min)

`build.gradle.kts`:

```kotlin
intellijPlatform {
    pluginVerification {
        ides { recommended() }   // 262 (blokująco w verifyPlugin) + najnowsze linie
    }
}
```

W CI dwa kroki: blokujący `verifyPlugin` (status quo) oraz `continue-on-error: true`
`./gradlew verifyPlugin -PtcdsEapWarning` — property przełącza listę IDE na EAP (np.
`ides { ide(Rider("2026.3-EAP...")) }` pod property). Czerwony krok = informacja, nie blokada
(`until-build=262.*` i tak chroni instalacje).
- Bramka: w CI pojawia się krok z raportem EAP (zielony albo jawnie czerwony z powodu API);
  krok blokujący pozostaje zielony.

### Release 0.3.0 — z tagu (self-test R3)

Skill: change-notes → bump 0.3.0 → commit/push → `git tag v0.3.0 && git push origin vX`
→ CI buduje, weryfikuje, tworzy release z assetem → aktualizacja `updatePlugins.xml` musi
trafić do commitu PRZED tagiem (URL celuje w release, który CI dopiero utworzy — kolejność:
commit z XML → tag → CI tworzy release pod tym URL-em; weryfikacja HTTP po zakończeniu runu).

---

## Pakiet C (dokumentacja/Marketplace)

### R8 — README: zrzut + troubleshooting (45 min +Capture)

1. Troubleshooting: „docker CLI w PATH (Settings pokazuje balon)"; `TCDS_DOCKER_BINARY`;
   „balon się nie pokazuje, bo demon leży — retry co 30 s"; „prefiks zmienia tylko nowe DS";
   „plugin nie instaluje się na Riderze < 2026.2 (until-build 262.*)".
2. Zrzut ekranu: podczas bramki A otworzyć sandbox z aktywnym DS i zrobić PrintScreen okna
   (PowerShell `System.Drawing.CopyFromScreen`) → `docs/screenshot-datasource.png` w README.
   Fallback gdy automatyka zawiedzie: placeholder na zrzut od użytkownika.
3. Krótka sekcja „Jak to działa" już jest — połączyć linkami.

### R9 — przygotowanie Marketplace (30 min)

`plugin.xml`: `<vendor url="https://github.com/justflaky/rider-testcontainers-datasources">justflaky</vendor>`;
`<description>` po angielsku (tłumaczenie obecnego), `change-notes` EN przy okazji release'y.
Polityka `untilBuild` utrzymana (262.*) — podniesienie wyłącznie po zielonym EAP-check z R5.
Publikacja sama w sobie — osobna decyzja użytkownika (konto JB, upload zipa z CI artefaktu).

---

## Pozycja świadomie odroczona (bez zmian)

**Watcher app-level** (1 proces `docker events` na aplikację + MessageBus fan-out do projektów)
— szkic w poprzednim planie; wraca po 0.3.0, gdy R4 (korutyny) już ułatwi refaktor.

## Ryzyka

- R2: bus-listener w serwisie projektu podpięty do app-busa — connect(this) z Disposable
  serwisu gwarantuje odpięcie; dublowanie start() chroni guard `started`/`streamAlive`.
- R4: blokujące `docker` CLI w korutynach — koniecznie `Dispatchers.IO`, inaczej głodzenie
  wątków; e2e bramka to złapie.
- R5: DSL `ides{}` potrafił zmieniać składnię między wersjami pluginu Gradle — kompilacja
  buildscriptu jest bramką; w razie oporu — degradacja do `recommended()` bez property.
- R3: tagChronologia — release URL w `updatePlugins.xml` wskazuje asset, który powstanie
  dopiero po runie CI; weryfikacja HTTP dopiero po zielonym runie (spokojnie, Marketplace/IDE
  czytają XML dopiero przy update-check użytkowników).

## Sprzątanie

Odhaczenia w nowym pliku rewizji (lub sekcja „Review #2" w IMPROVEMENTS.md), commit per pakiet
(A/B/C), release 0.2.1 i 0.3.0 zgodnie z przepisami wyżej.
