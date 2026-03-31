# quarkus-oidc-rest-client-jbang

A self-contained educational POC that demonstrates how to wire a **Quarkus declarative REST client with automatic OIDC
bearer-token injection**, packaged as a single Java file runnable via [JBang](https://www.jbang.dev/) — no build tool,
no project scaffolding.

The app targets the [Cloud Foundry API v3](https://v3-apidocs.cloudfoundry.org/): it authenticates against CF UAA using
the Resource Owner Password Credentials grant and lists organizations. [WireMock](https://wiremock.org/) stands in for
the live CF environment so the demo works fully offline.

---

## Concepts

| Layer                       | What it demonstrates                                                                                                                                                            |
|-----------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **JBang**                   | Running a Quarkus app from a single `.java` file with zero project scaffolding                                                                                                  |
| **Quarkus OIDC Client**     | Acquiring and auto-refreshing a bearer token using the `password` grant                                                                                                         |
| **Declarative REST Client** | `@RegisterRestClient` + `@RegisterProvider(OidcClientRequestReactiveFilter.class)` — the framework injects `Authorization: Bearer …` automatically before each outgoing request |
| **WireMock**                | Stubbing both the UAA token endpoint (`POST /oauth/token`) and the CF API (`GET /v3/organizations`) without needing a live CF instance                                          |

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

Alternative (without the script):

```bash
./jbang org.wiremock:wiremock-standalone:3.5.3 \
  --port 9090 \
  --root-dir ./wiremock-data \
  --verbose
```

WireMock starts on **port 9090** and loads pre-built stubs from `wiremock-data/mappings/`:

| Stub                    | What it returns                                   |
|-------------------------|---------------------------------------------------|
| `POST /oauth/token`     | A fake `access_token` as a CF UAA would           |
| `GET /v3/organizations` | Two fake orgs; requires `Authorization: Bearer …` |

Watch the console for matched request logs — these confirm the full auth dance is happening.

### 2 — Run the app (terminal 2)

```bash
./jbang CfOrgs.java       # Linux / macOS
jbang.cmd CfOrgs.java     # Windows (cmd)
```

Expected output:

```
Fetching organizations from Cloud Foundry API...

Total: 2 organization(s) across 1 page(s)

  a1b2c3d4-e5f6-7890-abcd-ef1234567890  my-org
  b2c3d4e5-f6a7-8901-bcde-f12345678901  system
```

After the first run, JBang caches the compiled Quarkus app — subsequent runs start in seconds.

---

## Pointing to a Real CF Instance

Override the defaults via environment variables before running the app:

```bash
export QUARKUS_OIDC_CLIENT_AUTH_SERVER_URL=https://uaa.cf.example.com
export QUARKUS_OIDC_CLIENT_GRANT_OPTIONS_PASSWORD_USERNAME=me@example.com
export QUARKUS_OIDC_CLIENT_GRANT_OPTIONS_PASSWORD_PASSWORD=mysecret
export QUARKUS_REST_CLIENT_CF__API_URL=https://api.cf.example.com

./jbang CfOrgs.java
```

> **Note on the double underscore in `CF__API_URL`:** SmallRye Config maps hyphens in config key segments to `__` in
> environment variable names. The config key `cf-api` → `CF__API`.

---

## How It Works

```
./jbang CfOrgs.java
        │
        └─► Quarkus boots (picocli command)
                │
                └─► @Inject @RestClient CloudFoundryClient.getOrganizations()
                          │
                          └─► OidcClientRequestReactiveFilter intercepts
                                    │
                                    ├─► POST /oauth/token  (grant_type=password, client_id=cf)
                                    │         └─► receives access_token
                                    │
                                    └─► GET /v3/organizations
                                              Header: Authorization: Bearer <token>
                                              └─► parses JSON → prints orgs
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
CfOrgs.java                      # The entire application — JBang entry point
jbang                            # JBang wrapper (Linux / macOS)
jbang.cmd                        # JBang wrapper (Windows cmd)
jbang.ps1                        # JBang wrapper (PowerShell)
.jbang/jbang.jar                 # Bundled JBang bootstrap JAR
start-wiremock.sh                # Starts WireMock with pre-built stubs
wiremock-data/
  mappings/
    oauth-token.json             # Stub: POST /oauth/token → fake bearer token
    v3-organizations.json        # Stub: GET /v3/organizations → two fake orgs
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

| Artifact                          | Purpose                                                                                                    |
|-----------------------------------|------------------------------------------------------------------------------------------------------------|
| `quarkus-picocli`                 | CLI entry point                                                                                            |
| `quarkus-rest-client-oidc-filter` | Brings in `OidcClientRequestReactiveFilter` — the reactive filter that transparently injects bearer tokens |
| `quarkus-rest-client-jackson`     | Reactive REST client with Jackson JSON mapping                                                             |

> **Quarkus version note:** The `quarkus-rest-client-oidc-filter` and `quarkus-rest-client-jackson` artifacts replaced the older `quarkus-oidc-client-reactive-filter` and `quarkus-rest-client-reactive-jackson` respectively. This project targets **Quarkus 3.16.4**, the most recent version that supports the quoted-key REST client config syntax (`quarkus.rest-client."cf-api".url`). Starting from Quarkus 3.17.0, that quoted form is no longer recognized; the required key becomes `quarkus.rest-client.cf-api.url` (without quotes).

---

## References

- [Quarkus OIDC Client & Filters reference guide](https://quarkus.io/guides/security-openid-connect-client-reference)
- [Quarkus REST Client guide](https://quarkus.io/guides/rest-client)
- [Cloud Foundry API v3 docs](https://v3-apidocs.cloudfoundry.org/)
- [JBang documentation](https://www.jbang.dev/documentation/guide/latest/)
- [WireMock documentation](https://wiremock.org/docs/)
