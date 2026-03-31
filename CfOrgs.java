///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17+

//DEPS io.quarkus:quarkus-bom:3.16.4@pom
//DEPS io.quarkus:quarkus-picocli
//DEPS io.quarkus:quarkus-rest-client-oidc-filter
//DEPS io.quarkus:quarkus-rest-client-jackson

//Q:CONFIG quarkus.banner.enabled=false
//Q:CONFIG quarkus.log.level=WARN
//Q:CONFIG quarkus.log.min-level=TRACE
//Q:CONFIG quarkus.log.console.level=TRACE

// ---------------------------------------------------------------------------
// HTTP traffic logging — troubleshooting (shows full req/resp + sensitive data)
// ---------------------------------------------------------------------------
// REST client: log request + response headers and body
//Q:CONFIG quarkus.rest-client.logging.scope=request-response
//Q:CONFIG quarkus.rest-client.logging.body-limit=100000
//Q:CONFIG quarkus.log.category."org.jboss.resteasy.reactive.client.logging".level=DEBUG
// OIDC client: log token endpoint calls (includes credentials + access token)
//Q:CONFIG quarkus.log.category."io.quarkus.oidc.client".level=TRACE
//Q:CONFIG quarkus.log.category."io.quarkus.oidc".level=TRACE

// ---------------------------------------------------------------------------
// OIDC Client — CF UAA, Resource Owner Password Credentials grant
//   The OidcClient fetches (and caches/refreshes) the bearer token
//   automatically before every REST call via OidcClientRequestReactiveFilter.
//
//   CF UAA quirk: client_id=cf is a public client (empty client_secret).
//   Credentials are sent in the POST body (method=post) rather than
//   Basic-Auth, which is what CF UAA expects.
// ---------------------------------------------------------------------------
//Q:CONFIG quarkus.oidc-client.auth-server-url=${CF_UAA_URL:http://localhost:9090}
//Q:CONFIG quarkus.oidc-client.discovery-enabled=false
//Q:CONFIG quarkus.oidc-client.token-path=/oauth/token
//Q:CONFIG quarkus.oidc-client.client-id=cf
//Q:CONFIG quarkus.oidc-client.credentials.client-secret.value=
//Q:CONFIG quarkus.oidc-client.credentials.client-secret.method=post
//Q:CONFIG quarkus.oidc-client.grant.type=password
//Q:CONFIG quarkus.oidc-client.grant-options.password.username=${CF_USERNAME:admin}
//Q:CONFIG quarkus.oidc-client.grant-options.password.password=${CF_PASSWORD:admin}

// ---------------------------------------------------------------------------
// CF REST Client — base URL defaults to WireMock; override via env var:
//   export QUARKUS_REST_CLIENT_CF_API_URL=https://api.cf.example.com
// ---------------------------------------------------------------------------
//Q:CONFIG quarkus.rest-client."cf-api".url=${CF_API_URL:http://localhost:9090}

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
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
// Declarative REST client
//   @RegisterProvider(OidcClientRequestReactiveFilter.class) wires in the
//   Quarkus OIDC client filter. Before each request it:
//     1. Calls POST /oauth/token with the configured grant parameters
//     2. Caches the token until it expires, then refreshes automatically
//     3. Adds "Authorization: Bearer <token>" to the outgoing request
// ---------------------------------------------------------------------------
@RegisterRestClient(configKey = "cf-api")
@RegisterProvider(OidcClientRequestReactiveFilter.class)
@Path("/v3")
interface CloudFoundryClient {

    @GET
    @Path("/organizations")
    @Produces(MediaType.APPLICATION_JSON)
    OrganizationsResponse getOrganizations();
}

// ---------------------------------------------------------------------------
// Response model — CF API v3 /v3/organizations
// ---------------------------------------------------------------------------

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