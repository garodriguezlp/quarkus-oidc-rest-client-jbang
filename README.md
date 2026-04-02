# cf_env

A Cloud Foundry operator tool for inspecting apps and extracting environment variables. It runs as a single Java file
via [JBang](https://www.jbang.dev/) — no build tool, no project scaffolding required.

Built on **Quarkus** (Picocli + declarative REST client), it authenticates against CF UAA using the Resource Owner
Password Credentials grant, injects the bearer token automatically on every request via a `ClientRequestFilter`, and
handles CF API pagination generically. [WireMock](https://wiremock.org/) stubs ship with the repo for fully offline
local runs.

---

## Commands

### `apps` — list all applications

Fetches orgs, spaces, and apps in parallel and prints a joined table.

```bash
./jbang cf_env.java apps
```

```
ORG                  SPACE           APP NAME                       UUID
my-org               dev             my-app                         app-guid-0001
my-org               staging         worker                         app-guid-0003
system               prod            api                            app-guid-0002
```

### `env` — export app environment variables

Writes a `.env` file for the given app UUID.

```bash
./jbang cf_env.java env <app-uuid>
./jbang cf_env.java env <app-uuid> --output /path/to/output.env
```

Default output file: `{app-name}-{uuid}.env` in the current directory.

```
APP_ENV='development'
DATABASE_URL='postgres://db.internal:5432/myapp'
REDIS_URL='redis://cache.internal:6379'
```

---

## Running against a real CF instance

Set the following environment variables before invoking the tool:

```bash
export QUARKUS_REST_CLIENT_UAA_URL=https://uaa.cf.example.com
export QUARKUS_REST_CLIENT_CF__API_URL=https://api.cf.example.com
export CF_USERNAME=me@example.com
export CF_PASSWORD=mysecret

./jbang cf_env.java apps
./jbang cf_env.java env <app-uuid>
```

> `CF__API_URL` uses a double underscore because SmallRye Config maps hyphens in config key segments to `__` in
> environment variable names (`cf-api` → `CF__API`).

---

## Running locally with WireMock

Pre-built stubs covering all required endpoints are included under `wiremock-data/mappings/`.

**Terminal 1 — start WireMock:**

```bash
bash start-wiremock.sh
```

**Terminal 2 — run the tool:**

```bash
./jbang cf_env.java apps
./jbang cf_env.java env app-guid-0001
```

WireMock listens on port `9090`, which matches the default REST client URLs in `cf_env.java`.

To record live traffic from a real CF environment instead of using the pre-built stubs, switch `start-wiremock.sh` to
recording mode:

```bash
VERSION=3.5.3
./jbang org.wiremock:wiremock-standalone:${VERSION} \
  --port 9090 \
  --proxy-all "https://api.cf.example.com" \
  --record-mappings \
  --root-dir ./wiremock-data \
  --verbose
```

---

## Prerequisites

- Java 17+
- Internet access on first run (JBang fetches dependencies from Maven Central and caches them)

The repo ships with a [JBang wrapper](https://www.jbang.dev/documentation/guide/latest/usage.html#jbang-wrapper)
(`jbang` / `jbang.cmd` / `jbang.ps1`) — no separate JBang installation needed.

---

## References

- [Quarkus Picocli guide](https://quarkus.io/guides/picocli)
- [Quarkus REST Client guide](https://quarkus.io/guides/rest-client)
- [Cloud Foundry API v3 docs](https://v3-apidocs.cloudfoundry.org/)
- [JBang documentation](https://www.jbang.dev/documentation/guide/latest/)
- [WireMock documentation](https://wiremock.org/docs/)
