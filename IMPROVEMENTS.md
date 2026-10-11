# Review — lista ulepszeń (2026-10-11)

Stan wyjściowy: pipeline przetestowany e2e (postgres + redis w Riderze 2026.2.1), Plugin Verifier
„Compatible" na RD-262, 27/27 testów jednostkowych. Poniżej znalezione ulepszenia wg priorytetu.

## Priorytet 1 — poprawność (bugi)

- [ ] **B1. Reconnect = „miganie" data source i spam powiadomień.**
  `reconcileSnapshot()` (`TcWatcherService.kt:56`) po każdym reconnectcie woła `registerContainer`
  dla każdego działającego kontenera, a `registerContainer` zawsze robi remove→add→powiadomienie.
  Skutek: restart Docker Desktop → każdy istniejący data source zostanie odtworzony od zera +
  balon „Data source created" dla każdego, choć nic się nie zmieniło.
  *Fix: jeśli śledzony DS już istnieje i URL się nie zmienił — aktualizacja w miejscu
  (`setUrl` + `saveDataSource`) bez powiadomienia; balon tylko przy faktycznym utworzeniu.*

- [ ] **B2. Heurystyka własności po nazwie może skasować cudzy data source.**
  `pluginOwnedShortId` (`DataSourceRegistrar.kt`) uznaje każdy DS o nazwie
  `TC cokolwiek <7 hexów>` za własny — reconcile usunie ręcznie stworzony „TC staging deadbee".
  *Fix: marker `setAdditionalProperty("tcds.containerId", id)` (persistowany w konfiguracji DS)
  jako klucz pierwotny; heurystyka nazwy tylko jako fallback po restarcie IDE.*

- [ ] **B3. Brak `until-build`.** Plugin zainstaluje się na Riderze 2026.3+, gdzie
  pół-wewnętrzne API `com.intellij.database` może się zmienić → błędy runtime zamiast odmowy
  instalacji. Decyzja: `until-build="262.*"` (bezpieczniej) albo otwarty + obowiązkowy
  `verifyPlugin` przed każdym release'em.

## Priorytet 2 — UX

- [ ] **Okno ustawień** — `TcSettings` ma `enabled`/`namePrefix`, ale brak `Configurable`;
  zmiana wymaga ręcznej edycji `tcds.xml`. Prosta strona ustawień (checkbox + prefix).
- [ ] **`dockerUnavailable()` martwy kod** — zdefiniowane w `TcNotifications`, nigdzie nieużywane.
  Podpiąć raz przy pierwszym wykryciu braku demona (z akcją „spróbuj ponownie") — dziś użytkownik
  bez Dockera nie dostaje żadnej wskazówki, tylko milczące retry co 30 s.
- [ ] **Zadokumentować `TCDS_DOCKER_BINARY`** (nadpisanie ścieżki dockera w `DockerCli.kt`) w README.

## Priorytet 3 — jakość kodu

- [ ] Usunąć martwe symbole (zweryfikowane grepem): `asSafeObject` (`DockerJson.kt:92`),
  `trackedNames`, `dockerUnavailable`.
- [ ] `scheduleSync` — `Thread.sleep` na wątku z puli; po przepisaniu serwisu na korutyny
  (`CoroutineScope` wstrzykiwany do project service) stanie się przerywalne przy dispose.
- [ ] Wyciągnąć czystą logikę `pluginOwnedShortId`/`redisPassword` do testowalnych funkcji.

## Priorytet 4 — testy i CI

- [ ] **CI (GitHub Actions)**: `./gradlew test buildPlugin verifyPlugin` + zip jako artefakt;
  przy publicznym repo to największa dziura.
- [ ] Testy najryzykowniejszej logiki: heurystyka reconcile (B2), idempotentność rejestrowania (B1).
- [ ] (opcjonalnie) e2e dla mysql/mssql — dziś tylko testy jednostkowe (wspólna ścieżka runtime;
  akceptowalne).

## Priorytet 5 — rozszerzenia funkcjonalne

- [ ] **MongoDB** — `Dbms.MONGO` istnieje w API, testcontainers ma moduł, URL `mongodb://`
  analogiczny; jeden wpis w katalogu.
- [ ] **Jeden watcher na aplikację** zamiast per-projekt — N otwartych projektów = N procesów
  `docker events`; serwis app-level z fan-outem.
- [ ] **Gradle task release'owy** — jeden task: bump wersji + regeneracja `updatePlugins.xml`
  + tag + release (dziś ręczna choreografia opisana w README).

## Proponowany pierwszy pakiet

**B1 + B2 + CI + sprzątanie martwego kodu** — zamyka oba defekty, daje automat pilnujący
jakości, nie rusza architektury.
