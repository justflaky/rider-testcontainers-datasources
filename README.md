# Testcontainers Data Sources — plugin do JetBrains Rider

Automatycznie tworzy data source'y w Database tool window dla baz danych uruchamianych przez
Testcontainers. Gdy test startuje kontener Postgresa, plugin w ułamku sekundy:

1. wykrywa kontener przez `docker events` (labelka `org.testcontainers=true` — stawiana przez
   Testcontainers dla .NET i Javy),
2. odczytuje z `docker inspect` opublikowany port hosta, obraz oraz zmienne środowiskowe
   (`POSTGRES_USER/PASSWORD/DB`, `MYSQL_*`, `MARIADB_*`, `MSSQL_SA_PASSWORD`, …),
3. tworzy data source `TC <obraz> <shortId>` z gotowym URL JDBC i credentialami,
4. po zakończeniu testu (kontener usuwa Ryuk) **usuwa data source automatycznie**.

Obsługiwane obrazy: **PostgreSQL** (i pochodne: postgis, timescaledb, pgvector), **MySQL**,
**MariaDB**, **SQL Server** (`mcr.microsoft.com/mssql/server`, azure-sql-edge) oraz **Redis**
(oficjalny `redis`, `redis-stack`/`redis-stack-server`, `valkey`; hasło czytane z
`--requirepass` w komendzie kontenera lub `REDIS_PASSWORD` — np. bitnami). Redis wymaga
wsparcia z pluginu Database Tools (wbudowane w Riderze 2026.2; sterownik `com.redis.jdbc.Driver`
zostanie pobrany automatycznie przy pierwszej synchronizacji).

## Wymagania

- Rider **2026.2.x** (build 262.*),
- plugin Database Tools and SQL (wbudowany w Ridera),
- `docker` CLI dostępny w PATH (Docker Desktop).

## Instalacja

Z gotowego zipa: `Settings → Plugins → ⚙ → Install Plugin from Disk…` →
`build/distributions/rider-testcontainers-datasources-0.1.0.zip`.

Albo przez **własne repozytorium pluginów** (plugin pojawia się w wyszukiwarce IDE i dostaje
automatyczne aktualizacje): `Settings → Plugins → ⚙ → Manage Plugin Repositories → [+]` i dodaj:

```
https://raw.githubusercontent.com/justflaky/rider-testcontainers-datasources/master/repository/updatePlugins.xml
```

Wymaga publicznego repo (raw GitHub nie serwuje anonimowo z prywatnych). Wersje dystrybuje się
tak: nowy zip jako asset release'a `vX.Y.Z` + aktualizacja `version`/`url` w
`repository/updatePlugins.xml`.

## Konfiguracja

`Settings → Tools → Testcontainers Data Sources`:

- **Watch for Testcontainers containers** — globalne włączenie/wyłączenie watchera,
- **Data source name prefix** — prefiks nazw data source'ów (domyślnie `TC`).

Zmienne środowiskowe (w procesie IDE): `TCDS_DOCKER_BINARY` — pełna ścieżka do binarki dockera,
gdy nie jest na PATH. Ustawienia trwałe trzymają się w `tcds.xml` (`enabled`, `namePrefix`).

## Rozwiązywanie problemów

- **Balon „Docker is not available" / nic się nie dzieje** — demon Dockera leży albo
  `docker` CLI nie jest w PATH procesu Ridera. Plugin ponawia próbę co 30 s; binarkę można
  wskazać zmienną `TCDS_DOCKER_BINARY` (wymaga restartu IDE po zmianie enva).
- **Data source nie powstaje** — kontener musi publikować port na hoście (`-p`/testcontainers
  robi to domyślnie) i nosić labelkę `org.testcontainers=true` (stawiają ją biblioteki
  Testcontainers; surowy `docker run` bez labelki jest ignorowany).
- **Zmiana prefiksu w ustawieniach** — działa tylko dla nowo tworzonych data source'ów.
- **Plugin nie instaluje się** — Rider 2026.2+ wymagany (`since-build=262`, `until-build=262.*`).
- **Hasła w `idea.log`** — plugin redaguje własne wpisy (`password=***`), ale natywne logi
  modułu Database (`DatabaseConnectionEstablisher`, historia DS) wypisują pełny URL połączenia
  — dotyczy to każdego data source'a z credentialami w URL (także ręcznych). Nie udostępniaj
  logów z sesji z wrażliwymi testowymi hasłami.

## Budowanie ze źródeł

```bash
export JAVA_HOME="C:/Program Files/JetBrains/JetBrains Rider 2026.2.1/jbr"   # dowolne JDK 25
./gradlew test          # testy jednostkowe (parser docker JSON, katalog obrazów)
./gradlew buildPlugin   # zip w build/distributions/
./gradlew verifyPlugin  # Plugin Verifier (RD-262: Compatible, 1 użycie deprecated API)
```

Sandbox IDE z pluginem (do ręcznych prób):

```bash
./gradlew runIde
# lub od razu z otwartym projektem:
./gradlew runIde -PtcdsOpenProject=<ścieżka-do-projektu>
```

Po zatrzymaniu sandboxa najlepiej ubić procesy IDE i backendu: `powershell -File scripts/kill-ide.ps1`
(twarde kille zostawiają osierocone backendi .NET blokujące kolejne uruchomienia).

## Projekt demo (e2e)

`tc-demo/` — testy xUnit + Testcontainers.PostgreSql i Testcontainers.Redis. Po odpaleniu
`dotnet test` z otwartym Riderem data source'y pojawiają się w trakcie testów i znikają po
ich zakończeniu. Działanie pluginu widać w `idea.log` (wpisy `tcds:`).

## Architektura

```
src/main/kotlin/tcds/
  TcStartupActivity.kt            # start po otwarciu projektu
  TcSettings.kt                   # enabled / namePrefix (tcds.xml)
  TcNotifications.kt              # balloon z akcją otwarcia Database tool window
  docker/DockerCli.kt             # docker CLI (version/inspect/ps), timeouty, drenowanie pipe
  docker/DockerJson.kt            # parsowanie events/inspect (Gson; czysta logika, testowalna)
  docker/TcWatcherService.kt      # strumień docker events + reconnect + snapshot docker ps
  catalog/DatabaseImageCatalog.kt # obraz → URL JDBC + driverClass (credentials w URL)
  ds/DataSourceRegistrar.kt       # LocalDataSourceManager add/remove + auto-sync
  model/ContainerInfo.kt
```

Kluczowe decyzje techniczne:

- **Śledzenie po nazwie**: container id jest zakodowane w nazwie data source'a (`TC postgres a1b2c3d`),
  bo `LocalDataSource` nie ma user data, a nazwa przeżywa restart IDE (reconcile po starcie czyta
  `docker ps` i sprząta martwe wpisy).
- **Credentials w URL**: `LocalDataSource.create(name, driverClass, url, user)` (kolejność
  zweryfikowana deasemblacją bajtkodu RD-262) nie przyjmuje hasła — user/password idą jako
  parametry URL (wspierane przez wszystkie 4 sterowniki JDBC), z URL-encodowaniem.
- **Wewnętrzne API `com.intellij.database`**: pół-otwarte, bez gwarancji stabilności — `verifyPlugin`
  pilnuje kompatybilności; `sinceBuild=262` bez `untilBuild` (przy aktualizacji Ridera odpal
  `verifyPlugin` i ewentualnie skoryguj wywołania).
- **Drenowanie pipe w `DockerCli`**: `docker inspect` zwraca >4KB (buffer pipe na Windows) —
  output czytany współbieżnie z `waitFor`, inaczej deadlock.

## Ograniczenia

- Tylko bazy z mapowania w `DatabaseImageCatalog` (dodanie kolejnej = jeden wpis w `specs`).
- Kontenery muszą publikować port na hoście (tryb host-network bez mapowań portów nie jest
  obsługiwany).
- Hasło jest zapisane jawnie w URL data source'a — akceptowalne dla lokalnych, efemerycznych
  kontenerów testowych.
