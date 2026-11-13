package com.minos.explorer;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The one check every request goes through before any route is looked up. Listening on the loopback is not an
 * authentication: a page of another site opened in the same browser, another process of the machine or a
 * rebound DNS name can all reach the port. A request is served only when its {@code Host} is one of the names
 * the surface is served under, its {@code Origin}, when present, is the served origin, and, for the API, it
 * carries the session token.
 */
final class RequestGuard {

    /** Why a request is refused; the HTTP answer carries the status only, never the reason. */
    enum Refusal {
        HOST(403), ORIGIN(403), TOKEN(401);

        private final int status;

        Refusal(int status) {
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    private static final Set<String> SAME_SITE_FETCHES = Set.of("same-origin", "none");

    private final Set<String> hosts;
    private final Set<String> origins;
    private final SessionToken token;

    private RequestGuard(Set<String> hosts, Set<String> origins, SessionToken token) {
        this.hosts = hosts;
        this.origins = origins;
        this.token = token;
    }

    /** The guard of a surface served on {@code port}: the three loopback names, with that port, and no other. */
    static RequestGuard forPort(int port, SessionToken token) {
        List<String> names = List.of("127.0.0.1:" + port, "localhost:" + port, "[::1]:" + port);
        return new RequestGuard(Set.copyOf(names),
                Set.copyOf(names.stream().map(name -> "http://" + name).toList()), token);
    }

    /**
     * The refusal of a request, or empty when it may be served. {@code api} tells whether the request targets
     * the API, which alone requires the token: the static page holds no data.
     */
    Optional<Refusal> check(boolean api, String host, String origin, String fetchSite, String authorization) {
        if (host == null || !hosts.contains(host.toLowerCase(Locale.ROOT))) return Optional.of(Refusal.HOST);
        if (origin != null && !origins.contains(origin.toLowerCase(Locale.ROOT))) return Optional.of(Refusal.ORIGIN);
        if (fetchSite != null && !SAME_SITE_FETCHES.contains(fetchSite.toLowerCase(Locale.ROOT))) {
            return Optional.of(Refusal.ORIGIN);
        }
        if (api && !token.matches(authorization)) return Optional.of(Refusal.TOKEN);
        return Optional.empty();
    }
}
