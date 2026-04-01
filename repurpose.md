# CfEnv — Repurpose Spec

## Overview

A single-file, JBang-runnable Quarkus CLI tool for Cloud Foundry operators to conveniently inspect apps and fetch their
environment variables. This is a targeted CF v3 API client — not a general-purpose tool.

**Target API:** [CF v3](https://v3-apidocs.cloudfoundry.org/)

---

## File

Rename `CfOrgs.java` → `CfEnv.java`. Update all content (README, comments, configs) accordingly. Leave the GitHub repo
renaming to the owner.

---

## Stack (no changes to core approach)

- **JBang** — single `.java` file, zero scaffolding
- **Quarkus + Picocli** — CLI entry point
- **Quarkus Declarative REST Client** — `@RegisterRestClient`, `@RegisterProvider`
- **Auth** — CF UAA, Resource Owner Password Credentials grant (username + password via env vars). The existing
  `UaaClient`, `BearerTokenProvider`, and `CfAuthFilter` code is working and must not be changed.
- **Code style** — small, single-purpose classes; small methods at a single level of abstraction; digestible and
  maintainable.

---

## Commands

### `apps` — List all applications

Fetches orgs, spaces, and apps; joins them in memory via CF v3 relationships; prints a plain padded table to stdout.

```
ORG          SPACE    APP NAME    UUID
my-org       dev      my-app      abc123-...
other-org    prod     api         def456-...
```

**Relationship model (from the CF v3 API):**

- `app.relationships.space.data.guid` → space
- `space.relationships.organization.data.guid` → org

**Data fetching:**

1. `GET /v3/organizations?per_page=1000`
2. `GET /v3/spaces?per_page=1000`
3. `GET /v3/apps?per_page=1000`

Steps 1–3 are independent and must be executed in parallel (e.g., `CompletableFuture`). Results are then joined in
memory.

**Pagination:** implement a generic paginator that follows `pagination.next` until null, accumulating all resources.

---

### `env` — Export app environment variables to a `.env` file

Takes an app UUID, calls `GET /v3/apps/{app_guid}/environment_variables`, and writes the result to a `.env` file.

**Usage:**

```
jbang CfEnv.java env <app-uuid> [--output <file>]
```

**Default output file name:** `{app-name}-{app-uuid}.env` in the current directory.  
`--output` flag allows overriding the path.

**File format:** standard `.env` (`KEY=VALUE`, one per line).

---

## APIs to Support

| Method | Path                                        | Notes           |
|--------|---------------------------------------------|-----------------|
| GET    | `/v3/organizations?per_page=1000`           | Paginated       |
| GET    | `/v3/spaces?per_page=1000`                  | Paginated       |
| GET    | `/v3/apps?per_page=1000`                    | Paginated       |
| GET    | `/v3/apps/{app_guid}/environment_variables` | Single resource |

---

## WireMock Stubs

Replace existing stubs. All stubs must use **linked data** (guids matching across resources):

| Stub file                | Endpoint                                    | Returns                         |
|--------------------------|---------------------------------------------|---------------------------------|
| `oauth-token.json`       | `POST /oauth/token`                         | Keep as-is (working)            |
| `v3-organizations.json`  | `GET /v3/organizations`                     | 2 orgs                          |
| `v3-spaces.json`         | `GET /v3/spaces`                            | 2–3 spaces linked to above orgs |
| `v3-apps.json`           | `GET /v3/apps`                              | 2–3 apps linked to above spaces |
| `v3-app-env-{guid}.json` | `GET /v3/apps/{guid}/environment_variables` | 1 app (any of the above guids)  |

Keep stubs small (2–3 elements max). Data must be consistent: app GUIDs in `v3-apps.json` match the URL pattern in
`v3-app-env-*.json`, space GUIDs match, org GUIDs match.

---

## Testing

- Start WireMock via `start-wiremock.sh` (already ships in the repo).
- Run both commands against WireMock using `jbang CfEnv.java apps` and `jbang CfEnv.java env <uuid>`.
- Verify output correctness (table columns, `.env` file content, linked data accuracy).

