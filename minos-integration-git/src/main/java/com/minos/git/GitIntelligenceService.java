package com.minos.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * M12 factual Git intelligence over a local repository.
 *
 * <p>The service reads Git data with JGit and deliberately reports activity facts only.
 * Commit frequency is never promoted to architectural or business importance.</p>
 */
public final class GitIntelligenceService {

    private static final int MAX_COMMITS = 10_000;
    private static final int MAX_FILES = 10_000;
    private static final int MAX_ZONE_DEPTH = 8;

    private final ActivityBudget budget;

    public GitIntelligenceService() {
        this(ActivityBudget.DEFAULT);
    }

    /** Explicit budget, so a caller (and an adversarial test) can bound the analysis frontier itself. */
    public GitIntelligenceService(ActivityBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    /** The resource frontier this service enforces while it walks history. */
    public ActivityBudget budget() {
        return budget;
    }

    public RepositoryView inspect(Path projectRoot) throws IOException, GitAPIException {
        try (Repository repository = open(projectRoot); Git git = new Git(repository)) {
            return repositoryView(repository, git);
        }
    }

    public ActivityReport analyze(Path projectRoot, ActivityQuery query) throws IOException, GitAPIException {
        Objects.requireNonNull(query, "query");
        try (Repository repository = open(projectRoot); Git git = new Git(repository)) {
            RepositoryView repositoryView = repositoryView(repository, git);
            Set<String> limitations = new LinkedHashSet<>(repositoryView.limitations());
            List<CommitActivity> commits = new ArrayList<>();
            Map<String, MutableFileActivity> files = new HashMap<>();
            Map<String, MutableZoneActivity> zones = new HashMap<>();
            boolean historyTruncated = false;

            try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
                 RevWalk revWalk = new RevWalk(repository)) {
                formatter.setRepository(repository);
                formatter.setDetectRenames(true);

                int processed = 0;
                Iterable<RevCommit> history;
                try {
                    history = git.log().setMaxCount(query.maxCommits() + 1).call();
                } catch (NoHeadException exception) {
                    limitations.add("UNBORN_HEAD");
                    history = List.of();
                }

                int retainedPaths = 0;
                for (RevCommit commit : history) {
                    Instant committedAt = commit.getCommitterIdent().getWhenAsInstant();
                    if (committedAt.isBefore(query.since())) {
                        break;
                    }
                    if (processed >= query.maxCommits()) {
                        historyTruncated = true;
                        limitations.add("HISTORY_TRUNCATED");
                        break;
                    }

                    List<String> changedPaths = changedPaths(commit, formatter, revWalk, repository, limitations);
                    String author = authorKey(commit);
                    for (String path : changedPaths) {
                        MutableFileActivity file = files.get(path);
                        if (file == null && files.size() >= budget.maxTrackedFiles()) {
                            limitations.add("FILE_TRACKING_TRUNCATED");
                        } else {
                            files.computeIfAbsent(path, MutableFileActivity::new)
                                    .record(commit.getName(), committedAt, author);
                        }
                        String zone = zone(path, query.zoneDepth());
                        MutableZoneActivity zoneActivityEntry = zones.get(zone);
                        if (zoneActivityEntry == null && zones.size() >= budget.maxTrackedZones()) {
                            limitations.add("ZONE_TRACKING_TRUNCATED");
                        } else {
                            zones.computeIfAbsent(zone, MutableZoneActivity::new)
                                    .record(path, committedAt);
                        }
                    }
                    List<String> retained = changedPaths;
                    int remaining = budget.maxRetainedChangedPaths() - retainedPaths;
                    if (retained.size() > remaining) {
                        retained = retained.subList(0, Math.max(0, remaining));
                        limitations.add("PATHS_TRUNCATED");
                    }
                    retainedPaths += retained.size();
                    commits.add(new CommitActivity(
                            commit.getName(),
                            committedAt,
                            commit.getAuthorIdent().getName(),
                            commit.getAuthorIdent().getEmailAddress(),
                            commit.getShortMessage(),
                            retained
                    ));
                    processed++;
                }
            }

            List<FileActivity> fileActivity = files.values().stream()
                    .map(MutableFileActivity::freeze)
                    .sorted(Comparator.comparingInt(FileActivity::commitCount).reversed()
                            .thenComparing(FileActivity::lastChangedAt, Comparator.reverseOrder())
                            .thenComparing(FileActivity::path))
                    .toList();
            boolean filesTruncated = fileActivity.size() > query.maxFiles();
            if (filesTruncated) {
                limitations.add("FILES_TRUNCATED");
                fileActivity = fileActivity.subList(0, query.maxFiles());
            }

            List<ZoneActivity> zoneActivity = zones.values().stream()
                    .map(MutableZoneActivity::freeze)
                    .sorted(Comparator.comparingInt(ZoneActivity::commitTouches).reversed()
                            .thenComparing(ZoneActivity::lastChangedAt, Comparator.reverseOrder())
                            .thenComparing(ZoneActivity::zone))
                    .limit(query.maxFiles())
                    .toList();

            return new ActivityReport(
                    repositoryView,
                    query,
                    commits.size(),
                    historyTruncated,
                    filesTruncated,
                    commits,
                    fileActivity,
                    zoneActivity,
                    List.copyOf(limitations)
            );
        }
    }

    private static Repository open(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path root = projectRoot.toRealPath();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("projectRoot must be an existing directory: " + projectRoot);
        }
        FileRepositoryBuilder builder = new FileRepositoryBuilder()
                .readEnvironment()
                .findGitDir(root.toFile());
        if (builder.getGitDir() == null) {
            throw new IllegalArgumentException("projectRoot is not inside a Git repository: " + root);
        }
        return builder.build();
    }

    private static RepositoryView repositoryView(Repository repository, Git git)
            throws IOException, GitAPIException {
        Path workTree = repository.isBare()
                ? repository.getDirectory().toPath().toAbsolutePath().normalize()
                : repository.getWorkTree().toPath().toRealPath();
        String rawRemote = repository.getConfig().getString("remote", "origin", "url");
        String remote = sanitizeRemote(rawRemote);
        String head = objectId(repository.resolve(Constants.HEAD));
        String fullBranch = repository.getFullBranch();
        boolean detached = fullBranch != null && !fullBranch.startsWith(Constants.R_HEADS);
        String branch = detached || fullBranch == null ? null : Repository.shortenRefName(fullBranch);
        boolean shallow = repository.getDirectory() != null
                && Files.isRegularFile(repository.getDirectory().toPath().resolve("shallow"));
        Status status = repository.isBare() ? null : git.status().call();
        boolean clean = status == null || status.isClean();

        List<String> limitations = new ArrayList<>();
        if (remote == null) {
            limitations.add("NO_ORIGIN_REMOTE");
        }
        if (detached) {
            limitations.add("DETACHED_HEAD");
        }
        if (shallow) {
            limitations.add("SHALLOW_HISTORY");
        }
        if (head == null) {
            limitations.add("UNBORN_HEAD");
        }

        String identityBasis = remote == null ? "path:" + workTree : "remote:" + remote;
        return new RepositoryView(
                sha256(identityBasis),
                workTree.toString(),
                remote,
                branch,
                head,
                detached,
                shallow,
                clean,
                limitations
        );
    }

    /**
     * Returns the changed paths of one commit without ever materializing an unbounded diff.
     *
     * <p>{@code DiffFormatter.scan} answers a fully materialized {@code List<DiffEntry>}: for a
     * commit that touches a million paths it allocates a million entries before any MINOS budget
     * could look at the result. The diff size is therefore measured first, with a plain {@link
     * TreeWalk} that counts and stops one entry past the budget -- linear in {@code
     * min(diffSize, budget)} and constant in memory. Only a commit that fits inside the budget goes
     * through {@code scan}, which keeps rename detection, and therefore the reported paths, exactly
     * as they were for every normal commit. A commit over budget falls back to the bounded walk's
     * own paths and is reported as {@code DIFF_SCAN_TRUNCATED}.</p>
     */
    private List<String> changedPaths(
            RevCommit commit,
            DiffFormatter formatter,
            RevWalk revWalk,
            Repository repository,
            Set<String> limitations
    ) throws IOException {
        RevTree parentTree = commit.getParentCount() == 0
                ? null
                : revWalk.parseCommit(commit.getParent(0).getId()).getTree();
        if (exceedsDiffBudget(repository, parentTree, commit.getTree())) {
            limitations.add("DIFF_SCAN_TRUNCATED");
            return boundedChangedPaths(repository, parentTree, commit.getTree());
        }
        List<DiffEntry> entries = formatter.scan(parentTree, commit.getTree());
        return entries.stream()
                .map(GitIntelligenceService::path)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /** Whether this commit's raw diff has more entries than one commit is allowed to cost. */
    private boolean exceedsDiffBudget(Repository repository, RevTree parentTree, RevTree tree)
            throws IOException {
        int limit = budget.maxDiffEntriesPerCommit();
        int seen = 0;
        try (TreeWalk walk = diffWalk(repository, parentTree, tree)) {
            while (walk.next()) {
                if (++seen > limit) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The first {@code maxDiffEntriesPerCommit} changed paths in tree order, then sorted. */
    private List<String> boundedChangedPaths(Repository repository, RevTree parentTree, RevTree tree)
            throws IOException {
        int limit = budget.maxDiffEntriesPerCommit();
        Set<String> paths = new TreeSet<>();
        try (TreeWalk walk = diffWalk(repository, parentTree, tree)) {
            while (paths.size() < limit && walk.next()) {
                paths.add(walk.getPathString());
            }
        }
        return List.copyOf(paths);
    }

    private static TreeWalk diffWalk(Repository repository, RevTree parentTree, RevTree tree)
            throws IOException {
        TreeWalk walk = new TreeWalk(repository);
        boolean created = false;
        try {
            walk.setRecursive(true);
            if (parentTree == null) {
                walk.addTree(new EmptyTreeIterator());
            } else {
                walk.addTree(parentTree);
            }
            walk.addTree(tree);
            walk.setFilter(TreeFilter.ANY_DIFF);
            created = true;
            return walk;
        } finally {
            if (!created) {
                walk.close();
            }
        }
    }

    private static String path(DiffEntry entry) {
        String candidate = entry.getChangeType() == DiffEntry.ChangeType.DELETE
                ? entry.getOldPath()
                : entry.getNewPath();
        return DiffEntry.DEV_NULL.equals(candidate) ? null : candidate;
    }

    private static String authorKey(RevCommit commit) {
        String email = commit.getAuthorIdent().getEmailAddress();
        if (email != null && !email.isBlank()) {
            return email.trim().toLowerCase(Locale.ROOT);
        }
        return commit.getAuthorIdent().getName();
    }

    private static String zone(String path, int depth) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash < 0) {
            return "(root)";
        }
        String[] segments = normalized.substring(0, slash).split("/");
        int count = Math.min(depth, segments.length);
        return String.join("/", java.util.Arrays.copyOf(segments, count));
    }

    private static String sanitizeRemote(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            URI uri = new URI(value);
            if (uri.getScheme() != null && uri.getHost() != null) {
                URI sanitized = new URI(
                        uri.getScheme(),
                        null,
                        uri.getHost(),
                        uri.getPort(),
                        trimGitSuffix(uri.getPath()),
                        null,
                        null
                );
                return sanitized.toString();
            }
        } catch (URISyntaxException ignored) {
            // Fall through to SCP-style normalization.
        }
        int at = value.indexOf('@');
        int colon = value.indexOf(':', at + 1);
        if (at >= 0 && colon > at) {
            value = value.substring(at + 1);
        }
        return trimGitSuffix(value);
    }

    private static String trimGitSuffix(String value) {
        return value != null && value.endsWith(".git") ? value.substring(0, value.length() - 4) : value;
    }

    private static String objectId(org.eclipse.jgit.lib.ObjectId id) {
        return id == null ? null : id.getName();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Resource frontier applied <em>while</em> history is analysed, as opposed to {@code
     * ActivityQuery.maxFiles}, which shapes the answer once the work is already done.
     *
     * <p>These two limits answer different questions and must stay separate. {@code maxFiles} is
     * part of the published query contract ("return at most N files/zones"); silently reusing it as
     * a work budget would change what a caller asked for. The budget below is what stops one
     * pathological commit -- an import of a vendored tree, a repository-wide reformat -- from
     * costing memory and CPU proportional to the repository rather than to the request. Reaching
     * any of these limits is reported through the report's {@code limitations}, never hidden.</p>
     *
     * <p>The defaults are deliberately far above any realistic engineering commit, so a normal
     * repository is analysed exactly as before and only a genuinely adversarial history is
     * bounded.</p>
     */
    public record ActivityBudget(
            int maxDiffEntriesPerCommit,
            int maxTrackedFiles,
            int maxTrackedZones,
            int maxRetainedChangedPaths
    ) {
        /** Bounds one commit's diff, the tracked file/zone maps, and the retained per-commit paths. */
        public static final ActivityBudget DEFAULT = new ActivityBudget(2_000, 20_000, 5_000, 50_000);

        public ActivityBudget {
            requirePositive(maxDiffEntriesPerCommit, "maxDiffEntriesPerCommit");
            requirePositive(maxTrackedFiles, "maxTrackedFiles");
            requirePositive(maxTrackedZones, "maxTrackedZones");
            requirePositive(maxRetainedChangedPaths, "maxRetainedChangedPaths");
        }

        private static void requirePositive(int value, String field) {
            if (value < 1) {
                throw new IllegalArgumentException(field + " must be greater than zero");
            }
        }
    }

    public record ActivityQuery(Instant since, int maxCommits, int maxFiles, int zoneDepth) {
        public ActivityQuery {
            Objects.requireNonNull(since, "since");
            if (maxCommits < 1 || maxCommits > MAX_COMMITS) {
                throw new IllegalArgumentException("maxCommits must be between 1 and " + MAX_COMMITS);
            }
            if (maxFiles < 1 || maxFiles > MAX_FILES) {
                throw new IllegalArgumentException("maxFiles must be between 1 and " + MAX_FILES);
            }
            if (zoneDepth < 1 || zoneDepth > MAX_ZONE_DEPTH) {
                throw new IllegalArgumentException("zoneDepth must be between 1 and " + MAX_ZONE_DEPTH);
            }
        }
    }

    public record RepositoryView(
            String repositoryId,
            String workTree,
            String originRemote,
            String branch,
            String headCommit,
            boolean detachedHead,
            boolean shallow,
            boolean clean,
            List<String> limitations
    ) {
        public RepositoryView {
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }

    public record CommitActivity(
            String commitId,
            Instant committedAt,
            String authorName,
            String authorEmail,
            String message,
            List<String> changedPaths
    ) {
        public CommitActivity {
            changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
        }
    }

    public record FileActivity(
            String path,
            int commitCount,
            int uniqueAuthorCount,
            Instant lastChangedAt,
            String lastCommitId
    ) {
    }

    public record ZoneActivity(
            String zone,
            int commitTouches,
            int distinctFileCount,
            Instant lastChangedAt
    ) {
    }

    public record ActivityReport(
            RepositoryView repository,
            ActivityQuery query,
            int scannedCommitCount,
            boolean historyTruncated,
            boolean filesTruncated,
            List<CommitActivity> recentCommits,
            List<FileActivity> files,
            List<ZoneActivity> zones,
            List<String> limitations
    ) {
        public ActivityReport {
            recentCommits = recentCommits == null ? List.of() : List.copyOf(recentCommits);
            files = files == null ? List.of() : List.copyOf(files);
            zones = zones == null ? List.of() : List.copyOf(zones);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }

    private static final class MutableFileActivity {
        private final String path;
        private int commitCount;
        private final Set<String> authors = new HashSet<>();
        private Instant lastChangedAt = Instant.EPOCH;
        private String lastCommitId;

        private MutableFileActivity(String path) {
            this.path = path;
        }

        private void record(String commitId, Instant committedAt, String author) {
            commitCount++;
            if (author != null && !author.isBlank()) {
                authors.add(author);
            }
            if (committedAt.isAfter(lastChangedAt)) {
                lastChangedAt = committedAt;
                lastCommitId = commitId;
            }
        }

        private FileActivity freeze() {
            return new FileActivity(path, commitCount, authors.size(), lastChangedAt, lastCommitId);
        }
    }

    private static final class MutableZoneActivity {
        private final String zone;
        private int commitTouches;
        private final Set<String> paths = new HashSet<>();
        private Instant lastChangedAt = Instant.EPOCH;

        private MutableZoneActivity(String zone) {
            this.zone = zone;
        }

        private void record(String path, Instant committedAt) {
            commitTouches++;
            paths.add(path);
            if (committedAt.isAfter(lastChangedAt)) {
                lastChangedAt = committedAt;
            }
        }

        private ZoneActivity freeze() {
            return new ZoneActivity(zone, commitTouches, paths.size(), lastChangedAt);
        }
    }
}
