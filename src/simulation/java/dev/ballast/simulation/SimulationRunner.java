package dev.ballast.simulation;

import dev.ballast.api.EnqueueRequest;
import dev.ballast.api.Lease;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/** Seeded, model-only Stage 1 runner. No production queue or storage fault campaign is exercised. */
public final class SimulationRunner {
    public static final String VERSION = "ballast-model-v1";
    private static final int WORKERS = 4;
    private static final int LIVE_LIMIT = 128;
    private static final int TOKEN_HISTORY = 128;
    private static final Set<String> OPTIONS = Set.of("--seed", "--steps", "--runs", "--trace-dir");

    private SimulationRunner() {}

    public static void main(String[] args) {
        Config config;
        try {
            config = parse(args);
        } catch (IllegalArgumentException invalid) {
            System.err.println("Invalid simulation options: " + invalid.getMessage());
            System.err.println("Usage: simulate [--seed LONG] [--steps POSITIVE_INT] [--runs POSITIVE_INT] [--trace-dir PATH]");
            System.exit(2);
            return;
        }
        try {
            Provenance provenance = provenance();
            Files.createDirectories(config.traceDirectory());
            System.out.println("MODEL_ONLY: reference-model exercise; production queue and fault checker are not implemented.");
            long totalSteps = 0;
            for (int run = 0; run < config.runs(); run++) {
                String checksum = execute(config, run, provenance);
                totalSteps += config.steps();
                System.out.println("run=" + run + " rootSeed=" + config.seed() + " derivedSeed="
                        + derivedSeed(config.seed(), run) + " steps=" + config.steps() + " traceSha256=" + checksum);
            }
            System.out.println("MODEL_ONLY complete runs=" + config.runs() + " steps=" + totalSteps);
        } catch (Exception failure) {
            System.err.println("Simulation failed: " + failure.getMessage());
            System.err.println("Replay: ./gradlew simulate --seed " + config.seed() + " --steps "
                    + config.steps() + " --runs " + config.runs());
            System.exit(1);
        }
    }

    private static Config parse(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String key = args[i];
            if (!OPTIONS.contains(key)) throw new IllegalArgumentException("unknown option " + key);
            if (i + 1 == args.length) throw new IllegalArgumentException("missing value for " + key);
            if (values.putIfAbsent(key, args[i + 1]) != null) throw new IllegalArgumentException("duplicate option " + key);
        }
        long seed = Long.parseLong(values.getOrDefault("--seed", "1"));
        int steps = Integer.parseInt(values.getOrDefault("--steps", "1000"));
        int runs = Integer.parseInt(values.getOrDefault("--runs", "1"));
        if (steps <= 0 || runs <= 0) throw new IllegalArgumentException("steps and runs must be positive");
        String directory = values.getOrDefault("--trace-dir", "build/simulation");
        if (directory.isBlank()) throw new IllegalArgumentException("trace directory must not be blank");
        return new Config(seed, steps, runs, Path.of(directory));
    }

    private static String execute(Config config, int run, Provenance provenance) throws IOException {
        long seed = derivedSeed(config.seed(), run);
        long workloadSeed = mix(seed ^ 0x574F524B4C4F4144L);
        long scheduleSeed = mix(seed ^ 0x5343484544554C45L);
        var workload = new Random(workloadSeed);
        var scheduling = new Random(scheduleSeed);
        var clock = new VirtualClock(1_700_000_000_000L);
        var model = new ReferenceQueue(new UUID(mix(seed), mix(~seed)), clock);
        List<List<Lease>> deliveries = new ArrayList<>();
        for (int worker = 0; worker < WORKERS; worker++) deliveries.add(new ArrayList<>());
        MessageDigest digest = sha256();
        Path path = config.traceDirectory().resolve("run-" + run + ".trace");
        int[] step = {0};
        try (var scheduler = new DeterministicScheduler(clock);
             var writer = new BufferedWriter(new OutputStreamWriter(
                     new DigestOutputStream(Files.newOutputStream(path), digest), StandardCharsets.UTF_8))) {
            line(writer, "version=" + VERSION + " mode=MODEL_ONLY sourceRevision=" + provenance.revision()
                    + " sourceHash=" + provenance.sourceHash() + " java=" + Runtime.version());
            line(writer, "rootSeed=" + config.seed() + " run=" + run + " derivedSeed=" + seed
                    + " steps=" + config.steps() + " workers=" + WORKERS + " liveLimit=" + LIVE_LIMIT
                    + " tokenHistory=" + TOKEN_HISTORY + " workloadSeed=" + workloadSeed + " scheduleSeed=" + scheduleSeed);
            try {
                while (step[0] < config.steps()) {
                    int batch = Math.min(WORKERS, config.steps() - step[0]);
                    long end = Math.addExact(clock.elapsedNanos(), 4_000_000);
                    for (int worker = 0; worker < batch; worker++) {
                        int actor = worker;
                        scheduler.schedule(Duration.ofMillis(scheduling.nextInt(5)), () -> {
                            String action = operation(model, clock, deliveries.get(actor), workload);
                            model.assertInvariants();
                            var stateHash = new CRC32();
                            stateHash.update(model.stateSignature().getBytes(StandardCharsets.UTF_8));
                            try {
                                line(writer, "step=" + step[0] + " worker=" + actor + " elapsed=" + clock.elapsedNanos()
                                        + " wall=" + clock.wallTimeMillis() + " " + action + " live=" + model.liveCount()
                                        + " stateCrc=" + Long.toUnsignedString(stateHash.getValue(), 16));
                            } catch (IOException error) {
                                throw new UncheckedIOException(error);
                            }
                            step[0]++;
                        });
                    }
                    scheduler.advanceTo(end);
                    model.refresh();
                }
                line(writer, "complete steps=" + step[0] + " live=" + model.liveCount());
            } catch (RuntimeException | AssertionError failure) {
                line(writer, "FAILED step=" + step[0] + " type=" + failure.getClass().getSimpleName());
                throw new IOException("rootSeed=" + config.seed() + " run=" + run + " derivedSeed=" + seed
                        + " step=" + step[0] + " trace=" + path + ": " + failure, failure);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String operation(ReferenceQueue model, VirtualClock clock, List<Lease> history, Random random) {
        int choice = random.nextInt(100);
        if (choice < 30 && model.liveCount() < LIVE_LIMIT) {
            byte[] payload = new byte[random.nextInt(33)];
            random.nextBytes(payload);
            int priority = random.nextInt(11) - 5;
            long readyAt = clock.wallTimeMillis() + random.nextInt(101) - 25;
            var id = model.enqueue(new EnqueueRequest(payload, priority, Instant.ofEpochMilli(readyAt)));
            return "enqueue id=" + id.sequence() + " priority=" + priority + " readyAt=" + readyAt
                    + " payload=" + HexFormat.of().formatHex(payload);
        }
        if (choice < 60 || (choice < 85 && history.isEmpty())) {
            long timeout = random.nextInt(100) + 1L;
            var result = model.lease(Duration.ofMillis(timeout));
            result.ifPresent(lease -> {
                if (history.size() == TOKEN_HISTORY) history.remove(0);
                history.add(lease);
            });
            return "lease timeout=" + timeout + " result=" + result.map(lease ->
                    lease.jobId().sequence() + "/" + lease.token().startupEpoch() + "/" + lease.token().sequence()).orElse("EMPTY");
        }
        if (choice < 85) {
            Lease lease = history.get(random.nextInt(history.size()));
            return "ack id=" + lease.jobId().sequence() + " token=" + lease.token().sequence()
                    + " result=" + model.acknowledge(lease.jobId(), lease.token());
        }
        if (choice < 95) {
            long jump = random.nextInt(101) - 50L;
            clock.jumpWallMillis(jump);
            return "wallJump millis=" + jump;
        }
        long advance = random.nextInt(51);
        clock.advanceElapsedNanos(advance * 1_000_000);
        return "elapsedAdvance millis=" + advance;
    }

    private static long derivedSeed(long root, int run) { return mix(root + 0x9E3779B97F4A7C15L * run); }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static void line(BufferedWriter writer, String text) throws IOException {
        writer.write(text);
        writer.write('\n');
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static Provenance provenance() throws IOException, InterruptedException {
        Path root = Path.of("").toAbsolutePath();
        String revision = "unknown";
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD").directory(root.toFile())
                    .redirectErrorStream(true).start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            } else if (process.exitValue() == 0) {
                revision = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            }
        } catch (IOException noGit) {
            // Exported source trees still carry a content fingerprint.
        }
        MessageDigest digest = sha256();
        var sources = new ArrayList<Path>();
        if (Files.isDirectory(root.resolve("src"))) {
            try (var files = Files.walk(root.resolve("src"))) {
                sources.addAll(files.filter(Files::isRegularFile).toList());
            }
        }
        for (String name : List.of("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle.lockfile")) {
            if (Files.isRegularFile(root.resolve(name))) sources.add(root.resolve(name));
        }
        sources.sort(java.util.Comparator.comparing(path -> root.relativize(path).toString()));
        for (Path source : sources) {
            digest.update(root.relativize(source).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(source));
            digest.update((byte) 0);
        }
        return new Provenance(revision, HexFormat.of().formatHex(digest.digest()));
    }

    private record Config(long seed, int steps, int runs, Path traceDirectory) {}
    private record Provenance(String revision, String sourceHash) {}
}
