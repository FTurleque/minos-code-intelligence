package com.minos.integration.git;

import com.minos.domain.git.ActivityQuery;
import com.minos.domain.git.ActivityReport;
import com.minos.domain.git.CommitActivity;
import com.minos.domain.git.FileActivity;
import com.minos.domain.git.RepositoryView;
import com.minos.domain.git.ZoneActivity;
import com.minos.git.GitIntelligence;
import com.minos.io.PrivateLocalStorage;
import com.minos.io.Sha256;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static com.minos.diagnostics.PublicErrorMessages.leafName;

/**
 * M12 factual Git intelligence over a local repository.
 *
 * <p>The service reads Git data with JGit and deliberately reports activity facts only.
 * Commit frequency is never promoted to architectural or business importance.</p>
 */
public final class GitIntelligenceService implements GitIntelligence {

    /** Commits visited, kept or skipped, before the walk gives up; a visit without a diff is cheap. */
    static final int MAX_SCANNED_COMMITS = 200_000;
    /** Consecutive commits older than {@code since} after which the rest of the history is taken as older too. */
    static final int OLDER_COMMIT_RUN_LIMIT = 1_000;

    private static final String FILTER_SECTION = "filter";
    private static final String OWNER_PROBE_DIRECTORY = "owner-probe";

    private final ActivityBudget budget;
    private final Supplier<UserPrincipal> expectedOwner;

    public GitIntelligenceService() {
        this(ActivityBudget.DEFAULT);
    }

    /** Explicit budget, so a caller (and an adversarial test) can bound the analysis frontier itself. */
    public GitIntelligenceService(ActivityBudget budget) {
        this(budget, null);
    }

    /**
     * Test seam: the owner a repository must have. {@code null} means the owner the system gives a file
     * this process creates.
     */
    GitIntelligenceService(ActivityBudget budget, Supplier<UserPrincipal> expectedOwner) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.expectedOwner = expectedOwner;
    }

    /** The resource frontier this service enforces while it walks history. */
    public ActivityBudget budget() {
        return budget;
    }

    @Override
    public RepositoryView inspect(Path projectRoot) throws IOException, GitAPIException {
        OpenedRepository opened = open(projectRoot);
        try (Repository repository = opened.repository(); Git git = new Git(repository)) {
            return repositoryView(repository, git, opened);
        }
    }

    @Override
    public ActivityReport analyze(Path projectRoot, ActivityQuery query) throws IOException, GitAPIException {
        Objects.requireNonNull(query, "query");
        OpenedRepository opened = open(projectRoot);
        try (Repository repository = opened.repository(); Git git = new Git(repository)) {
            RepositoryView repositoryView = repositoryView(repository, git, opened);
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
                    history = git.log().setMaxCount(MAX_SCANNED_COMMITS + 1).call();
                } catch (NoHeadException exception) {
                    limitations.add("UNBORN_HEAD");
                    history = List.of();
                }

                int retainedPathCount = 0;
                int scanned = 0;
                int consecutiveOlder = 0;
                for (RevCommit commit : history) {
                    // Committer dates are not monotone along parent links (clock skew, rebases,
                    // imported history): one old commit does not prove everything after it is old.
                    // Old commits are skipped, and the walk only stops on a long run of them, or at a
                    // hard scan cap, so a hostile history stays bounded without hiding recent commits.
                    if (++scanned > MAX_SCANNED_COMMITS) {
                        historyTruncated = true;
                        limitations.add("HISTORY_TRUNCATED");
                        limitations.add("HISTORY_SCAN_LIMIT");
                        break;
                    }
                    Instant committedAt = commit.getCommitterIdent().getWhenAsInstant();
                    if (committedAt.isBefore(query.since())) {
                        if (++consecutiveOlder >= OLDER_COMMIT_RUN_LIMIT) break;
                        continue;
                    }
                    consecutiveOlder = 0;
                    if (processed >= query.maxCommits()) {
                        historyTruncated = true;
                        limitations.add("HISTORY_TRUNCATED");
                        break;
                    }

                    List<String> changedPaths = changedPaths(commit, formatter, revWalk, repository, limitations);
                    recordActivity(commit, committedAt, changedPaths, query, files, zones, limitations);
                    List<String> retained = retainedPaths(
                            changedPaths, budget.maxRetainedChangedPaths() - retainedPathCount, limitations);
                    retainedPathCount += retained.size();
                    commits.add(new CommitActivity(
                            commit.getName(),
                            committedAt,
                            commit.getAuthorIdent().getName(),
                            query.includeAuthorEmails() ? commit.getAuthorIdent().getEmailAddress() : MASKED_AUTHOR_EMAIL,
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

    /** The repository found for a project root, and what had to be set aside to open it safely. */
    private record OpenedRepository(Repository repository, Path projectRoot, boolean filtersNotApplied) {
    }

    private OpenedRepository open(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path root = projectRoot.toRealPath();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("projectRoot must be an existing directory: " + leafName(projectRoot));
        }
        // No readEnvironment(): GIT_DIR, GIT_WORK_TREE and GIT_CEILING_DIRECTORIES must not redirect
        // the analysis to a repository other than the one under the requested root.
        FileRepositoryBuilder builder = new FileRepositoryBuilder()
                .findGitDir(root.toFile());
        if (builder.getGitDir() == null) {
            throw new IllegalArgumentException("projectRoot is not inside a Git repository: " + leafName(root));
        }
        Repository repository = builder.build();
        try {
            // A linked worktree keeps its own git dir but shares configuration, objects and hooks with the
            // main repository (the common directory): both are written by whoever owns them.
            requireExpectedOwner(repository.getDirectory().toPath());
            requireExpectedOwner(repository.getCommonDirectory().toPath());
            return new OpenedRepository(repository, root, redirectRepositoryFiltersToPassThrough(repository));
        } catch (IOException | RuntimeException failure) {
            repository.close();
            throw failure;
        }
    }

    /**
     * A repository found on disk is not trusted: the directories the analysis opens, directly or by
     * walking up from the project root, must belong to the account this process runs as, otherwise
     * their configuration and attributes were written by someone else. The path is resolved first, so a
     * link owned by the user cannot stand for a directory owned by another account.
     */
    private void requireExpectedOwner(Path gitDirectory) throws IOException {
        UserPrincipal found;
        UserPrincipal expected;
        try {
            found = Files.getOwner(gitDirectory.toRealPath());
            expected = expectedOwner != null ? expectedOwner.get() : processOwner();
        } catch (IOException | UncheckedIOException | UnsupportedOperationException _) {
            throw new IOException(
                    "The owner of the Git repository cannot be read; the analysis is refused (fail-closed)");
        }
        if (!found.equals(expected)) {
            throw new IOException("The Git repository is owned by '" + found.getName()
                    + "', not by the account running this process ('" + expected.getName()
                    + "'); change the repository owner before analysing it");
        }
    }

    private static final AtomicReference<UserPrincipal> PROCESS_OWNER = new AtomicReference<>();

    /**
     * The owner the system gives a file this process creates, read once from a throw-away file in the
     * private MINOS directory (never in the shared system temporary directory, and never inside the
     * analysed repository: an analysis writes nothing there and may not be allowed to). The owner of a new
     * file does not depend on the directory that holds it. The directory follows the rule of
     * {@code MinosHome} ({@code minos.home}, {@code MINOS_HOME}, then {@code ~/.minos}), which an adapter
     * cannot import from the application layer.
     */
    private static UserPrincipal processOwner() {
        UserPrincipal known = PROCESS_OWNER.get();
        if (known != null) {
            return known;
        }
        try {
            Path probe = PrivateLocalStorage.createPrivateTempFile(probeDirectory(), "minos-owner-", ".probe");
            try {
                PROCESS_OWNER.compareAndSet(null, Files.getOwner(probe));
                return PROCESS_OWNER.get();
            } finally {
                Files.deleteIfExists(probe);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Path probeDirectory() {
        String property = System.getProperty("minos.home");
        if (property != null && !property.isBlank()) {
            return Path.of(property).resolve(OWNER_PROBE_DIRECTORY);
        }
        String environment = System.getenv("MINOS_HOME");
        if (environment != null && !environment.isBlank()) {
            return Path.of(environment).resolve(OWNER_PROBE_DIRECTORY);
        }
        return Path.of(System.getProperty("user.home")).resolve(".minos").resolve(OWNER_PROBE_DIRECTORY);
    }

    /**
     * Neutralises, in memory only, every {@code filter.*} driver declared by the repository's own
     * configuration (included files comprised): a {@code clean} filter is a shell command, and the
     * repository is not trusted to run one. Each such driver is pointed at a built-in pass-through, so
     * the working-tree comparison is still computed, on the raw content. {@code JGit} does not drop
     * lines that come from an included file, so overriding the value is what covers them. The
     * configuration file is never written, and drivers known only to the user or system configuration
     * are left alone. Answers whether anything was set aside.
     */
    private static boolean redirectRepositoryFiltersToPassThrough(Repository repository) throws IOException {
        FileBasedConfig declared = new FileBasedConfig(
                new File(repository.getCommonDirectory(), "config"), repository.getFS());
        try {
            declared.load();
        } catch (ConfigInvalidException _) {
            throw new IOException("The Git repository configuration is invalid");
        }
        Set<String> drivers = declared.getSubsections(FILTER_SECTION);
        if (drivers.isEmpty()) {
            return false;
        }
        PassThroughFilter.register();
        for (String driver : drivers) {
            repository.getConfig().setString(FILTER_SECTION, driver, "clean", PassThroughFilter.COMMAND);
            repository.getConfig().setString(FILTER_SECTION, driver, "smudge", PassThroughFilter.COMMAND);
        }
        return true;
    }

    private static RepositoryView repositoryView(Repository repository, Git git, OpenedRepository opened)
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
        if (!workTree.equals(opened.projectRoot())) {
            limitations.add("REPOSITORY_ABOVE_PROJECT_ROOT");
        }
        if (opened.filtersNotApplied()) {
            limitations.add("REPOSITORY_FILTERS_NOT_APPLIED");
        }

        String identityBasis = remote == null ? "path:" + workTree : "remote:" + remote;
        return new RepositoryView(
                Sha256.hex(identityBasis),
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
     * Folds one commit's changed paths into the tracked file and zone activity, within budget.
     *
     * <p>An entry that already exists keeps being updated once a map is full: the budget bounds how
     * many distinct files and zones are <em>tracked</em>, not how accurately the tracked ones are
     * counted. Refusing an update would understate a file MINOS is already reporting on.</p>
     */
    private void recordActivity(
            RevCommit commit,
            Instant committedAt,
            List<String> changedPaths,
            ActivityQuery query,
            Map<String, MutableFileActivity> files,
            Map<String, MutableZoneActivity> zones,
            Set<String> limitations
    ) {
        String author = authorKey(commit);
        for (String path : changedPaths) {
            if (!files.containsKey(path) && files.size() >= budget.maxTrackedFiles()) {
                limitations.add("FILE_TRACKING_TRUNCATED");
            } else {
                files.computeIfAbsent(path, MutableFileActivity::new)
                        .record(commit.getName(), committedAt, author);
            }
            String zone = zone(path, query.zoneDepth());
            if (!zones.containsKey(zone) && zones.size() >= budget.maxTrackedZones()) {
                limitations.add("ZONE_TRACKING_TRUNCATED");
            } else {
                zones.computeIfAbsent(zone, MutableZoneActivity::new).record(path, committedAt);
            }
        }
    }

    /** The prefix of {@code changedPaths} this report is still allowed to retain, whole or none. */
    private static List<String> retainedPaths(List<String> changedPaths, int remaining, Set<String> limitations) {
        if (changedPaths.size() <= remaining) {
            return changedPaths;
        }
        limitations.add("PATHS_TRUNCATED");
        return changedPaths.subList(0, Math.max(0, remaining));
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
