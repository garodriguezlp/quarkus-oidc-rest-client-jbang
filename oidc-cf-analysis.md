# OIDC Deep-Dive: The CF UAA `client_secret=` Problem

---

## The OIDC Dance — A Refresher

OpenID Connect (OIDC) sits on top of OAuth 2.0. Three actors:

- **Client** — your app (`CfOrgs.java`)
- **Authorization Server (AS)** — CF UAA, issues tokens
- **Resource Server** — CF API, accepts tokens

The client never touches user credentials directly: it gets an **Access Token** from the AS and presents it to the
Resource Server as a Bearer token in the `Authorization` header.

### OAuth 2.0 Grant Types

| Grant                                          | Who uses it                               | Notes                                                                        |
|------------------------------------------------|-------------------------------------------|------------------------------------------------------------------------------|
| **Authorization Code**                         | Web apps — user present, browser redirect | The gold standard for human login                                            |
| **Authorization Code + PKCE**                  | SPAs, mobile, CLI with browser            | No client secret; code verifier replaces it                                  |
| **Client Credentials**                         | Machine-to-machine                        | No user — service authenticates as itself                                    |
| **Resource Owner Password Credentials (ROPC)** | Trusted CLI tools                         | User hands credentials directly to the client — **this is what CF CLI uses** |
| **Device Authorization**                       | TV/CLI with QR code                       | Out-of-band user approval                                                    |
| **Implicit**                                   | *Deprecated*                              | Old SPAs — do not use                                                        |
| **Refresh Token**                              | Any                                       | Exchange a refresh token for a new access token                              |

### ROPC — Password Grant Anatomy

```http
POST /oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=password
  &username=alice
  &password=s3cr3t
  &client_id=my-app
  &client_secret=my-secret    ← authenticates the client itself
```

The AS authenticates **both** the end-user (username + password) and the client application (client_id + client_secret).
The client receives an access token immediately — no browser, no redirects. This is exactly why the CF CLI uses it.

---

## The CF UAA Quirk

CF ships two well-known hardcoded clients:

| `client_id` | `client_secret`       | Purpose                         |
|-------------|-----------------------|---------------------------------|
| `cf`        | *(empty string `""`)* | Official CF CLI — public client |
| `login`     | `loginsecret`         | Internal login server           |

The `cf` client is a **public client**: conceptually it has no secret. According to RFC 6749 §3.2.1, public clients
SHOULD NOT authenticate and MAY simply omit `client_secret` from the request.

**CF UAA does not follow this.** It requires `client_secret=` (the key present with an empty value). If the key is
absent entirely, it returns:

```
HTTP 401
{"error": "invalid_client", "error_description": "Bad credentials"}
```

This is a non-spec implementation detail baked into CF UAA since its early days. The `cf` CLI works around it by always
appending `&client_secret=` to the token request body.

---

## Why Quarkus OIDC Doesn't Send `client_secret=`

The config in `CfOrgs.java`:

```properties
quarkus.oidc-client.credentials.client-secret.value=# empty string
quarkus.oidc-client.credentials.client-secret.method=post  # form body, not Basic Auth
```

Inside Quarkus OIDC Client (`OidcClientImpl.java`), before the field is added to the form, there is a guard roughly
equivalent to:

```java
String secret = config.credentials.clientSecret.value.orElse(null);
if (secret != null && !secret.isBlank() && method == POST) {
    formParams.add("client_secret", secret);
}
```

An empty string passes `!= null` but fails `!isBlank()`. **The field is silently dropped.** This is a deliberate design
decision: the library treats a blank secret as "no secret configured" and will not pollute the form body with an empty
field.

The result — the actual request Quarkus sends:

```
grant_type=password&username=admin&password=admin&client_id=cf
```

What CF UAA expects:

```
grant_type=password&username=admin&password=admin&client_id=cf&client_secret=
```

One field. One `=`. No value. CF UAA fails the request because of its absence.

---

## Solutions Ranked by Simplicity

### Option 1 — `grant-options` Override ❌ Fails (SmallRye Config)

The idea: use `grant-options.password.client_secret=` in config so Quarkus OIDC appends form fields verbatim, bypassing
the blank-secret guard.

**Why it fails:** SmallRye Config's `BuiltInConverter` converts empty string values (`""`) to `null` for any
non-Optional `String` mapping. When Quarkus OIDC reads the grant-options map and encounters `client_secret → null`, it
throws:

```
SRCFG00040: The config property quarkus.oidc-client.grant-options.password.client_secret
  is defined as the empty String ("") which the following Converter considered to be null:
  io.smallrye.config.Converters$BuiltInConverter
```

This is a hard constraint of SmallRye Config, not a JBang quirk. **Option 1 cannot work for this use case.**

### Option 2 — Plain REST Client for Token + Manual Filter ✅ Applied

Drop `quarkus-rest-client-oidc-filter` entirely. Add a `UaaClient` REST interface that calls `POST /oauth/token` with
`@FormParam("client_secret") String clientSecret` — and pass `""` as a Java literal. SmallRye Config is never consulted
for this value.

```java
@RegisterRestClient(configKey = "uaa")
@Path("/oauth")
interface UaaClient {
    @POST
    @Path("/token")
    @Consumes(APPLICATION_FORM_URLENCODED)
    @Produces(APPLICATION_JSON)
    TokenResponse token(
            @FormParam("grant_type") String grantType,
            @FormParam("client_id") String clientId,
            @FormParam("client_secret") String clientSecret, // "" in Java → client_secret= in body
            @FormParam("username") String username,
            @FormParam("password") String password);
}
```

A `BearerTokenProvider` bean calls `uaaClient.token("password", "cf", "", username, password)`, caches the result, and
refreshes before expiry. A `CfAuthFilter implements ClientRequestFilter` picks up the token and injects
`Authorization: Bearer <token>` on every CF API call.

This is reliable because the empty string lives entirely in Java heap — no config source, no converter, no stripping.

### Option 3 — `cf-java-client`

```xml
<dependency>
    <groupId>org.cloudfoundry</groupId>
    <artifactId>cloudfoundry-client-reactor</artifactId>
</dependency>
```

[cf-java-client](https://github.com/cloudfoundry/cf-java-client) is purpose-built for CF and handles all UAA quirks,
multi-page responses, reactive streams, and token refresh natively. The right choice if you need broader CF API
coverage (spaces, apps, services, etc.). Heavy dependency — overkill for listing orgs.

---

## What Was Changed

| File                                             | Change                                                                                                                                                                |
|--------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `CfOrgs.java`                                    | Dropped `quarkus-rest-client-oidc-filter` dep + all OIDC config. Added `UaaClient`, `BearerTokenProvider`, `CfAuthFilter`. `client_secret=""` passed as Java literal. |
| `wiremock-data/mappings/oauth-token.json`        | Added strict `bodyPatterns` — only matches when `client_secret=` is present                                                                                           |
| `wiremock-data/mappings/oauth-token-reject.json` | New fallback stub — returns 401 when the token request doesn't match the strict stub                                                                                  |

To verify the fix, enable OIDC trace logging and check the request body:

```properties
# Uncomment in CfOrgs.java to see the exact token request
quarkus.log.category."io.quarkus.oidc.client".level=TRACE
quarkus.log.category."io.quarkus.oidc".level=TRACE
```

Or check WireMock's own request log after running:

```bash
curl -s http://localhost:9090/__admin/requests | jq '.requests[].request.body'
```
