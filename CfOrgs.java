///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17+

//DEPS io.quarkus:quarkus-bom:3.16.4@pom
//DEPS io.quarkus:quarkus-picocli
//DEPS io.quarkus:quarkus-rest-client-jackson

//Q:CONFIG quarkus.banner.enabled=false
//Q:CONFIG quarkus.log.level=WARN
//Q:CONFIG quarkus.log.min-level=TRACE
//Q:CONFIG quarkus.log.console.level=TRACE

// ---------------------------------------------------------------------------
// REST client URLs — default to WireMock on :9090
// Override via Quarkus env-var convention (property path → uppercase, dots/hyphens → underscores):
//   export QUARKUS_REST_CLIENT_UAA_URL=https://uaa.cf.example.com
//   export QUARKUS_REST_CLIENT_CF_API_URL=https://api.cf.example.com
//   export CF_USERNAME=me@example.com
//   export CF_PASSWORD=secret
// Note: ${VAR:default} SmallRye expressions in //Q:CONFIG do not resolve OS env vars.
// ---------------------------------------------------------------------------
//Q:CONFIG quarkus.rest-client."uaa".url=http://localhost:9090
//Q:CONFIG quarkus.rest-client."cf-api".url=http://localhost:9090

// ---------------------------------------------------------------------------
// TLS — trust ALL certificates (self-signed, expired, mismatched hostnames).
// ⚠️  NOT SAFE FOR PRODUCTION. Use only in dev/test environments where you
//     control the server and cannot easily install a trusted certificate.
// ---------------------------------------------------------------------------
//Q:CONFIG quarkus.tls.trust-all=true

// ---------------------------------------------------------------------------
// HTTP traffic logging — exposes credentials and tokens; disable when not needed
// ---------------------------------------------------------------------------
//Q:CONFIG quarkus.rest-client.logging.scope=request-response
//Q:CONFIG quarkus.rest-client.logging.body-limit=100000
//Q:CONFIG quarkus.log.category."org.jboss.resteasy.reactive.client.logging".level=DEBUG
//Q:CONFIG quarkus.log.category."io.quarkus.oidc.client".level=TRACE
//Q:CONFIG quarkus.log.category."io.quarkus.oidc".level=TRACE

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import picocli.CommandLine;

import java.util.List;

/**
 * Entry point — lists CF organizations.
 *
 * Run (with WireMock already started on :9090):
 *   jbang CfOrgs.java
 *
 * Point to a real CF instance:
 *   CF_API_URL=https://api.cf.example.com \
 *   CF_UAA_URL=https://uaa.cf.example.com \
 *   CF_USERNAME=me@example.com CF_PASSWORD=secret \
 *   jbang CfOrgs.java
 */
@CommandLine.Command(
    name = "cf-orgs",
    mixinStandardHelpOptions = true,
    description = "Lists Cloud Foundry v3 organizations using a declarative REST client with OIDC password grant.")
public class CfOrgs implements Runnable {

    @Inject
    @RestClient
    CloudFoundryClient cfClient;

    @Override
    public void run() {
        System.out.println("Fetching organizations from Cloud Foundry API...\n");
        OrganizationsResponse response = cfClient.getOrganizations();
        System.out.printf("Total: %d organization(s) across %d page(s)%n%n",
                response.pagination().totalResults(),
                response.pagination().totalPages());
        response.resources().forEach(org ->
                System.out.printf("  %-36s  %s%n", org.guid(), org.name()));
    }
}

// ---------------------------------------------------------------------------
// UAA token endpoint client
//   Calls POST /oauth/token with explicit @FormParam values.
//   client_secret is passed as "" directly — bypasses SmallRye Config's
//   BuiltInConverter which converts empty strings to null (SRCFG00040).
// ---------------------------------------------------------------------------
@RegisterRestClient(configKey = "uaa")
@Path("/oauth")
interface UaaClient {

    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    TokenResponse token(
            @FormParam("grant_type")   String grantType,
            @FormParam("client_id")    String clientId,
            @FormParam("client_secret") String clientSecret,
            @FormParam("username")     String username,
            @FormParam("password")     String password);
}

// ---------------------------------------------------------------------------
// CF API declarative REST client — CfAuthFilter injects Authorization header
// ---------------------------------------------------------------------------
@RegisterRestClient(configKey = "cf-api")
@RegisterProvider(CfAuthFilter.class)
@Path("/v3")
interface CloudFoundryClient {

    @GET
    @Path("/organizations")
    @Produces(MediaType.APPLICATION_JSON)
    OrganizationsResponse getOrganizations();
}

// ---------------------------------------------------------------------------
// Token provider — fetches & caches bearer token from CF UAA
// ---------------------------------------------------------------------------
@ApplicationScoped
class BearerTokenProvider {

    @Inject
    @RestClient
    UaaClient uaaClient;

    @ConfigProperty(name = "cf.username", defaultValue = "admin")
    String username;

    @ConfigProperty(name = "cf.password", defaultValue = "admin")
    String password;

    private volatile String cachedToken;
    private volatile long expiresAt;

    public String getToken() {
        if (cachedToken == null || System.currentTimeMillis() >= expiresAt) {
            refresh();
        }
        return cachedToken;
    }

    private synchronized void refresh() {
        // client_secret="" — CF UAA requires the key present with empty value.
        // Passed as a Java literal so SmallRye Config is never involved.
        TokenResponse resp = uaaClient.token("password", "cf", "", username, password);
        cachedToken = resp.accessToken();
        expiresAt = System.currentTimeMillis() + Math.max(0L, resp.expiresIn() - 30L) * 1000L;
    }
}

// ---------------------------------------------------------------------------
// Client request filter — injects Authorization: Bearer <token>
// ---------------------------------------------------------------------------
@ApplicationScoped
@Priority(jakarta.ws.rs.Priorities.AUTHENTICATION)
class CfAuthFilter implements ClientRequestFilter {

    @Inject
    BearerTokenProvider tokenProvider;

    @Override
    public void filter(ClientRequestContext ctx) {
        ctx.getHeaders().putSingle("Authorization", "Bearer " + tokenProvider.getToken());
    }
}

// ---------------------------------------------------------------------------
// Response models
// ---------------------------------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") long expiresIn) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record OrganizationsResponse(
        Pagination pagination,
        List<Organization> resources) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record Pagination(
        @JsonProperty("total_results") int totalResults,
        @JsonProperty("total_pages") int totalPages) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record Organization(
        String guid,
        String name,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {
}