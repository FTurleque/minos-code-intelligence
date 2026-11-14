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
 * carries the session token. A request the browser announces as coming from another site is refused, except the
 * read of the page and of its resources: a link followed from another page is such a request, and the page holds
 * no data.
 */
final class RequestGuard {

    /** What a request asks for, which decides the checks it goes through. */
    enum Target {
        /** A route of the API: token required, no request from another site. */
        API,
        /** The {@code GET} of the page or of one of its resources: served to a navigation from another site. */
        PAGE_READ,
        /** Anything else outside the API: no token, no request from another site. */
        OTHER
    }

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
     * The refusal of a request, or empty when it may be served. The API alone requires the token: the static
     * page holds no data. {@code Host} and {@code Origin} are checked whatever the target.
     */
    Optional<Refusal> check(Target target, String host, String origin, String fetchSite, String authorization) {
        if (host == null || !hosts.contains(host.toLowerCase(Locale.ROOT))) return Optional.of(Refusal.HOST);
        if (origin != null && !origins.contains(origin.toLowerCase(Locale.ROOT))) return Optional.of(Refusal.ORIGIN);
        if (target != Target.PAGE_READ && fetchSite != null
                && !SAME_SITE_FETCHES.contains(fetchSite.toLowerCase(Locale.ROOT))) {
            return Optional.of(Refusal.ORIGIN);
        }
        if (target == Target.API && !token.matches(authorization)) return Optional.of(Refusal.TOKEN);
        return Optional.empty();
    }
}
