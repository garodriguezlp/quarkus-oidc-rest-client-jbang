///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17+

//COMPILE_OPTIONS -encoding UTF-8
//RUNTIME_OPTIONS -Dfile.encoding=UTF-8

//DEPS io.quarkus:quarkus-bom:3.16.4@pom
//DEPS io.quarkus:quarkus-picocli
//DEPS io.quarkus:quarkus-rest-client-jackson

//Q:CONFIG quarkus.banner.enabled=false
//Q:CONFIG quarkus.log.level=WARN
//Q:CONFIG quarkus.log.min-level=TRACE
//Q:CONFIG quarkus.log.console.level=WARN

// ---------------------------------------------------------------------------
// REST client URLs — default to WireMock on :9090
// Override via Quarkus env-var convention (property path → uppercase, dots/hyphens → underscores):
//   export QUARKUS_REST_CLIENT_UAA_URL=https://uaa.cf.example.com
//   export QUARKUS_REST_CLIENT_CF__API_URL=https://api.cf.example.com
//   export CF_USERNAME=me@example.com
//   export CF_PASSWORD=secret
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
import io.quarkus.arc.Unremovable;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
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

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

/**
 * Top-level Picocli command for Cloud Foundry v3 operations.
 *
 * <p>Provides the {@code apps} and {@code env} subcommands.</p>
 */
@TopCommand
@CommandLine.Command(
    name = "CfEnv",
    mixinStandardHelpOptions = true,
    description = "Cloud Foundry v3 CLI tool for inspecting apps and environment variables.",
    subcommands = {AppsCommand.class, EnvCommand.class})
class CfEnv {

}

@Dependent
@Unremovable
@CommandLine.Command(name = "apps", mixinStandardHelpOptions = true,
    description = "List all applications across all orgs and spaces.")
class AppsCommand implements Runnable {

    @Inject
    @RestClient
    CloudFoundryClient cfClient;

    @Override
    public void run() {
        CompletableFuture<List<Organization>> orgsFuture =
            CompletableFuture.supplyAsync(() -> PaginationSupport.paginate(p -> cfClient.getOrganizations(1000, p)));
        CompletableFuture<List<Space>> spacesFuture =
            CompletableFuture.supplyAsync(() -> PaginationSupport.paginate(p -> cfClient.getSpaces(1000, p)));
        CompletableFuture<List<App>> appsFuture =
            CompletableFuture.supplyAsync(() -> PaginationSupport.paginate(p -> cfClient.getApps(1000, p)));

        List<Organization> orgs = orgsFuture.join();
        List<Space> spaces = spacesFuture.join();
        List<App> apps = appsFuture.join();

        Map<String, String> orgNameByGuid = orgs.stream()
            .collect(Collectors.toMap(Organization::guid, Organization::name));
        Map<String, Space> spaceByGuid = spaces.stream()
            .collect(Collectors.toMap(Space::guid, s -> s));

        System.out.printf("%-20s %-15s %-30s %s%n", "ORG", "SPACE", "APP NAME", "UUID");
        apps.stream()
            .sorted(Comparator.comparing(App::name))
            .forEach(app -> {
                Space space = spaceByGuid.get(app.relationships().space().data().guid());
                String spaceName = space != null ? space.name() : "unknown";
                String orgName = space != null
                    ? orgNameByGuid.getOrDefault(space.relationships().organization().data().guid(), "unknown")
                    : "unknown";
                System.out.printf("%-20s %-15s %-30s %s%n", orgName, spaceName, app.name(), app.guid());
            });
    }
}

@Dependent
@Unremovable
@CommandLine.Command(name = "env", mixinStandardHelpOptions = true,
    description = "Export app environment variables to a .env file.")
class EnvCommand implements Runnable {

    @CommandLine.Parameters(index = "0", description = "The app UUID.")
    String appGuid;

    @CommandLine.Option(names = {"--output", "-o"},
        description = "Output file path (default: {app-name}-{uuid}.env).")
    String outputFile;

    @Inject
    @RestClient
    CloudFoundryClient cfClient;

    @Override
    public void run() {
        App app = cfClient.getApp(appGuid);
        AppEnvResponse envResponse = cfClient.getAppEnvironmentVariables(appGuid);

        String path = outputFile != null ? outputFile : app.name() + "-" + appGuid + ".env";

        try (PrintWriter writer = new PrintWriter(new FileWriter(path))) {
            envResponse.var().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> writer.printf("%s='%s'%n", e.getKey(), e.getValue()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write .env file: " + e.getMessage(), e);
        }

        System.out.println("Written to " + path);
    }
}

class PaginationSupport {
    private PaginationSupport() {
    }

    static <R> List<R> paginate(IntFunction<? extends PagedResponse<R>> fetcher) {
        List<R> all = new ArrayList<>();
        int page = 1;
        PagedResponse<R> response;
        do {
            response = fetcher.apply(page++);
            all.addAll(response.resources());
        } while (response.pagination().next() != null);
        return all;
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
            @FormParam("grant_type")    String grantType,
            @FormParam("client_id")     String clientId,
            @FormParam("client_secret") String clientSecret,
            @FormParam("username")      String username,
            @FormParam("password")      String password);
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
    OrganizationsResponse getOrganizations(
            @QueryParam("per_page") int perPage,
            @QueryParam("page")     int page);

    @GET
    @Path("/spaces")
    @Produces(MediaType.APPLICATION_JSON)
    SpacesResponse getSpaces(
            @QueryParam("per_page") int perPage,
            @QueryParam("page")     int page);

    @GET
    @Path("/apps")
    @Produces(MediaType.APPLICATION_JSON)
    AppsResponse getApps(
            @QueryParam("per_page") int perPage,
            @QueryParam("page")     int page);

    @GET
    @Path("/apps/{guid}")
    @Produces(MediaType.APPLICATION_JSON)
    App getApp(@PathParam("guid") String guid);

    @GET
    @Path("/apps/{guid}/environment_variables")
    @Produces(MediaType.APPLICATION_JSON)
    AppEnvResponse getAppEnvironmentVariables(@PathParam("guid") String guid);
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
// Pagination contract
// ---------------------------------------------------------------------------
interface PagedResponse<T> {
    Pagination pagination();
    List<T> resources();
}

// ---------------------------------------------------------------------------
// Response models
// ---------------------------------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in")   long expiresIn) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record Pagination(
        @JsonProperty("total_results") int totalResults,
        @JsonProperty("total_pages")   int totalPages,
    @JsonProperty("next")          Pagination.NextLink next) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NextLink(@JsonProperty("href") String href) {
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
record OrganizationsResponse(Pagination pagination, List<Organization> resources)
        implements PagedResponse<Organization> {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record Organization(
        String guid,
        String name,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record SpacesResponse(Pagination pagination, List<Space> resources)
        implements PagedResponse<Space> {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record Space(
        String guid,
        String name,
    Space.Relationships relationships) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Relationships(
        @JsonProperty("organization") OrganizationRelationship organization) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrganizationRelationship(@JsonProperty("data") Data data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(@JsonProperty("guid") String guid) {
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
record AppsResponse(Pagination pagination, List<App> resources)
        implements PagedResponse<App> {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record App(
        String guid,
        String name,
    App.Relationships relationships) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Relationships(@JsonProperty("space") SpaceRelationship space) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpaceRelationship(@JsonProperty("data") Data data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(@JsonProperty("guid") String guid) {
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
record AppEnvResponse(@JsonProperty("var") Map<String, String> var) {
}
