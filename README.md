# quarkus-oidc-rest-client-jbang

A self-contained educational POC that demonstrates how to wire a **Quarkus declarative REST client with automatic OIDC
bearer-token injection**, packaged as a single Java file runnable via [JBang](https://www.jbang.dev/) — no build tool,
no project scaffolding.

The app targets the [Cloud Foundry API v3](https://v3-apidocs.cloudfoundry.org/): it authenticates against CF UAA using
the Resource Owner Password Credentials grant and provides two CLI commands — `apps` (list all apps across orgs and
spaces) and `env` (export an app's environment variables to a `.env` file).
[WireMock](https://wiremock.org/) stands in for the live CF environment so the demo works fully offline.

---

## Concepts

| Layer                       | What it demonstrates                                                                                                                                                            |
|-----------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **JBang**                   | Running a Quarkus app from a single `.java` file with zero project scaffolding                                                                                                  |
| **Quarkus Picocli**         | Multi-level CLI with subcommands (`apps`, `env`)                                                                                                                                |
| **Declarative REST Client** | `@RegisterRestClient` + `@RegisterProvider(CfAuthFilter.class)` — the filter injects `Authorization: Bearer …` automatically before each outgoing request                      |
| **Parallel fetching**       | `CompletableFuture` to fetch orgs, spaces, and apps concurrently; results joined in memory                                                                                      |
| **Pagination**              | Generic paginator that follows `pagination.next` until null, accumulating all resources                                                                                          |
| **WireMock**                | Stubbing the UAA token endpoint and all CF API endpoints without needing a live CF instance                                                                                     |

---

## Prerequisites

- **Java 17+**
- **Internet access** on first run (JBang downloads Quarkus/WireMock JARs from Maven Central once, then caches them)

No JBang installation required — the repository ships with
a [JBang wrapper](https://www.jbang.dev/documentation/guide/latest/usage.html#jbang-wrapper) (`jbang` / `jbang.cmd` /
`jbang.ps1`) that bootstraps JBang automatically.

---

## Quick Start

### 1 — Start WireMock (terminal 1)

```bash
bash start-wiremock.sh          # Linux / macOS (or Git Bash on Windows)
```

The script starts WireMock from local stubs on port `9090`.

WireMock loads pre-built stubs from `wiremock-data/mappings/`:

| Stub                                        | What it returns                                              |
|---------------------------------------------|--------------------------------------------------------------|
| `POST /oauth/token`                         | A fake `access_token` as a CF UAA would                      |
| `GET /v3/organizations`                     | 2 fake orgs                                                  |
| `GET /v3/spaces`                            | 3 fake spaces linked to the above orgs                       |
| `GET /v3/apps`                              | 3 fake apps linked to the above spaces                       |
| `GET /v3/apps/app-guid-0001`                | Single app detail                                            |
| `GET /v3/apps/app-guid-0001/environment_variables` | Env vars for `my-app`                               |

### 2 — Run the app (terminal 2)

#### List all apps

```bash
./jbang CfEnv.java apps       # Linux / macOS
jbang.cmd CfEnv.java apps     # Windows (cmd)
```

Expected output:

```
ORG                  SPACE           APP NAME                       UUID
my-org               dev             my-app                         app-guid-0001
system               prod            api                            app-guid-0002
my-org               staging         worker                         app-guid-0003
```

#### Export app environment variables

```bash
./jbang CfEnv.java env app-guid-0001
```

Expected output:

```
Written to my-app-app-guid-0001.env
```

The generated `my-app-app-guid-0001.env` file:

```
APP_ENV=development
DATABASE_URL=postgres://db.internal:5432/myapp
REDIS_URL=redis://cache.internal:6379
```

Use `--output` / `-o` to override the output file path:

```bash
./jbang CfEnv.java env app-guid-0001 --output /tmp/myapp.env
```

---

## Pointing to a Real CF Instance

Override the defaults via environment variables before running the app:

```bash
export QUARKUS_REST_CLIENT_UAA_URL=https://uaa.cf.example.com
export QUARKUS_REST_CLIENT_CF__API_URL=https://api.cf.example.com
export CF_USERNAME=me@example.com
export CF_PASSWORD=mysecret

./jbang CfEnv.java apps
./jbang CfEnv.java env <app-uuid>
```

> **Note on the double underscore in `CF__API_URL`:** SmallRye Config maps hyphens in config key segments to `__` in
> environment variable names. The config key `cf-api` → `CF__API`.

---

## How It Works

```
./jbang CfEnv.java apps
        │
        └─► Quarkus boots (picocli command: apps)
                │
                ├─► CompletableFuture → GET /v3/organizations  ─┐
                ├─► CompletableFuture → GET /v3/spaces          ├─ (parallel)
                └─► CompletableFuture → GET /v3/apps            ─┘
                          │
                          └─► Each request: CfAuthFilter intercepts
                                    │
                                    ├─► POST /oauth/token  (grant_type=password, client_id=cf)
                                    │         └─► receives access_token
                                    │
                                    └─► GET /v3/...
                                              Header: Authorization: Bearer <token>
                          │
                          └─► Join results → print ORG / SPACE / APP NAME / UUID table
```

```
./jbang CfEnv.java env <app-uuid>
        │
        └─► Quarkus boots (picocli command: env)
                │
                ├─► GET /v3/apps/<app-uuid>                        → fetch app name
                └─► GET /v3/apps/<app-uuid>/environment_variables  → fetch env vars
                          │
                          └─► write KEY=VALUE lines to <app-name>-<app-uuid>.env
```

### CF UAA Quirks

CF's UAA uses a public client (`client_id=cf`, empty `client_secret`). Credentials are sent in the POST body (
`method=post`) rather than HTTP Basic Auth. The Quarkus OIDC client handles this via:

```
quarkus.oidc-client.credentials.client-secret.method=post
quarkus.oidc-client.credentials.client-secret.value=   ← intentionally empty
```

---

## Project Structure

```
CfEnv.java                              # The entire application — JBang entry point
jbang                                   # JBang wrapper (Linux / macOS)
jbang.cmd                               # JBang wrapper (Windows cmd)
jbang.ps1                               # JBang wrapper (PowerShell)
.jbang/jbang.jar                        # Bundled JBang bootstrap JAR
start-wiremock.sh                       # Starts WireMock with pre-built stubs
wiremock-data/
  mappings/
    oauth-token.json                    # Stub: POST /oauth/token → fake bearer token
    oauth-token-reject.json             # Stub: POST /oauth/token (fallback) → 401
    v3-organizations.json               # Stub: GET /v3/organizations → 2 orgs
    v3-spaces.json                      # Stub: GET /v3/spaces → 3 spaces
    v3-apps.json                        # Stub: GET /v3/apps → 3 apps
    v3-app-app-guid-0001.json           # Stub: GET /v3/apps/app-guid-0001
    v3-app-env-app-guid-0001.json       # Stub: GET /v3/apps/app-guid-0001/environment_variables
```

---

## WireMock Recording Mode

If you have a real CF environment and want to capture live traffic instead of using the pre-built stubs, edit
`start-wiremock.sh` and switch to recording mode:

```bash
VERSION=3.5.3
./jbang org.wiremock:wiremock-standalone:${VERSION} \
  --port 9090 \
  --proxy-all "https://api.cf.example.com" \
  --record-mappings \
  --root-dir ./wiremock-data \
  --verbose
```

WireMock writes captured interactions to `wiremock-data/mappings/` for offline replay.

---

## Key Dependencies

| Artifact                      | Purpose                                                                                  |
|-------------------------------|------------------------------------------------------------------------------------------|
| `quarkus-picocli`             | CLI entry point with subcommands                                                         |
| `quarkus-rest-client-jackson` | Reactive REST client with Jackson JSON mapping                                           |

> **Quarkus version note:** This demo is pinned to **Quarkus 3.16.4**.
> In our tests, upgrading to Quarkus 3.17+ caused the REST client property
> `quarkus.rest-client."cf-api".url` to stop working in this setup.
> The exact root cause is currently unknown.

---

## References

- [Quarkus Picocli guide](https://quarkus.io/guides/picocli)
- [Quarkus REST Client guide](https://quarkus.io/guides/rest-client)
- [Cloud Foundry API v3 docs](https://v3-apidocs.cloudfoundry.org/)
- [JBang documentation](https://www.jbang.dev/documentation/guide/latest/)
- [WireMock documentation](https://wiremock.org/docs/)
