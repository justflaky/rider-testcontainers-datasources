# Review — lista ulepszeń (2026-10-11)

Stan wyjściowy: pipeline przetestowany e2e (postgres + redis w Riderze 2026.2.1), Plugin Verifier
„Compatible" na RD-262, 27/27 testów jednostkowych. Poniżej znalezione ulepszenia wg priorytetu.

## Priorytet 1 — poprawność (bugi)

- [x] **B1. Reconnect = „miganie" data source i spam powiadomień.** — v0.2.0:
  `registrationDecision()` (CreateNew/NoChange/UpdateInPlace); reconnect aktualizuje w miejscu
  i milczy, balon tylko przy faktycznym utworzeniu.
- [x] **B2. Heurystyka własności po nazwie mogła skasować cudzy data source.** — v0.2.0:
  marker `tcds.containerId` w additional properties (persistowany) + zaostrzony fallback
  wymagający znanego obrazu z katalogu w nazwie (`DsOwnership`).
- [x] **B3. Brak `until-build`.** — v0.2.0: `until-build="262.*"` + polityka podnoszenia
  opisana w README (sekcja „Ograniczenia").

## Priorytet 2 — UX

- [x] **Okno ustawień** — v0.2.0: `TcSettingsConfigurable` (Swing, `Settings → Tools`).
- [x] **`dockerUnavailable()` podpięte** — v0.2.0: balon raz do odzyskania demona.
- [x] **`TCDS_DOCKER_BINARY`** udokumentowane w README (sekcja „Konfiguracja").

## Priorytet 3 — jakość kodu

- [x] Martwe symbole usunięte: `asSafeObject`, `trackedNames`.
- [x] `scheduleSync` na korutynach (wstrzyknięty `CoroutineScope`, przerywalne przy dispose).
- [x] Czysta logika wyekstrahowana: `DsOwnership`, `registrationDecision` (testowane);
  `redisPassword` pozostaje prywatna w katalogu — w pełni pokryta testami przez `resolve()`.

## Priorytet 4 — testy i CI

- [x] **CI**: `.github/workflows/ci.yml` (test + buildPlugin + verifyPlugin + artefakt zip).
- [x] Testy reconcile/ownership/idempotentności: `DsOwnershipTest`, `RegistrationDecisionTest`.
- [x] (decyzja) e2e mysql/mssql pominięte — wspólna ścieżka runtime, różnice baz pokryte unit.

## Priorytet 5 — rozszerzenia funkcjonalne

- [x] **MongoDB** — wpis w katalogu (auth root + authSource=admin, URL-encodowanie) + testy.
- [ ] **Jeden watcher na aplikację** (MessageBus, fan-out do projektów) — odłożone po 0.2.0.
- [x] **Gradle task release'owy** — zamknięte: zastąpione skillem
  `.agents/skills/tcds-plugin-release` (automatyzuje cały release przez REST API).

---

# Review #2 — po v0.2.0 (2026-10-11)

- [x] **R1** Hasła w logach — `redactUrl()` (query/średnik/userinfo), weryfikacja e2e (`password=***`);
  natywne logi modułu Database (poza pluginem) udokumentowane w README.
- [x] **R2** Ustawienia na żywo — topic na message bus + `stopWatching()` + safety-net w pętli.
- [x] **R3** Release z CI — workflow `release.yml` na tagi `v*`; skill w flow tagowym
  (`release-local.sh` jako fallback offline); **self-test: v0.3.0 wydane przez CI**.
- [x] **R4** Watcher w pełni na korutynach (`watchLoop`, `Dispatchers.IO`); e2e zielone.
- [x] **R5** Early-warning EAP — `pluginVerification.ides` + nieblokujący krok CI
  (aktywny po ustawieniu zmiennej repo `TCDS_LATEST_IDE`).
- [x] **R6** Ikona pluginu (`pluginIcon.svg` + dark) — potwierdzona w jarze 0.3.0.
- [x] **R7** Martwy kod (`DockerCli.isAvailable`) usunięty.
- [x] **R8** Troubleshooting w README. Zrzut ekranu — celowo NIE scommitowany
  (przechwyt pełnego pulpitu nieweryfikowalny przed publikacją); do dodania ręcznie,
  przycięty do okna Database tool window.
- [x] **R9** Vendor URL + angielski opis w `plugin.xml` (gotowe pod Marketplace).
- [ ] **Watcher app-level** — nadal odroczony (po 0.3.0).
