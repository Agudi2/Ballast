package dev.ballast.simulation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimulationCliTest {
    @TempDir Path directory;

    @Test
    void freshProcessesProduceByteIdenticalTraces() throws Exception {
        var first = invoke("first", "--seed", "8812934", "--steps", "500", "--runs", "2");
        var second = invoke("second", "--seed", "8812934", "--steps", "500", "--runs", "2");
        assertEquals(0, first.code(), first.output());
        assertEquals(0, second.code(), second.output());
        for (int run = 0; run < 2; run++) {
            String name = "run-" + run + ".trace";
            assertArrayEquals(Files.readAllBytes(directory.resolve("first").resolve(name)),
                    Files.readAllBytes(directory.resolve("second").resolve(name)));
        }
        String trace = Files.readString(directory.resolve("first/run-0.trace"));
        assertTrue(trace.contains("mode=MODEL_ONLY"));
        assertTrue(trace.contains("rootSeed=8812934"));
        assertTrue(trace.contains("sourceHash="));
        assertTrue(trace.contains("workloadSeed="));
        assertTrue(trace.contains("step=499 "));
        assertTrue(first.output().contains("runs=2 steps=1000"));
        assertNotEquals(trace, Files.readString(directory.resolve("first/run-1.trace")));
    }

    @Test
    void changedSeedChangesTrace() throws Exception {
        assertEquals(0, invoke("one", "--seed", "1", "--steps", "20").code());
        assertEquals(0, invoke("two", "--seed", "2", "--steps", "20").code());
        assertNotEquals(Files.readString(directory.resolve("one/run-0.trace")),
                Files.readString(directory.resolve("two/run-0.trace")));
    }

    @Test
    void invalidOptionsFailClearlyBeforeCreatingTraces() throws Exception {
        for (String[] args : new String[][] {
                {"--steps", "0"}, {"--runs", "-1"}, {"--seed", "not-a-number"},
                {"--unknown", "1"}, {"--steps"}, {"--seed", "1", "--seed", "2"}}) {
            var result = invoke("invalid", args);
            assertEquals(2, result.code(), result.output());
            assertTrue(result.output().contains("Usage:"), result.output());
        }
        assertFalse(Files.exists(directory.resolve("invalid")));
    }

    @Test
    void outputFailureExitsWithoutReportingCompletedRuns() throws Exception {
        Files.writeString(directory.resolve("blocked"), "existing file");
        var result = invoke("blocked", "--seed", "42", "--steps", "5");
        assertEquals(1, result.code(), result.output());
        assertTrue(result.output().contains("Simulation failed:"));
        assertTrue(result.output().contains("Replay: ./gradlew simulate --seed 42"));
        assertFalse(result.output().contains("complete runs="));
        assertEquals("existing file", Files.readString(directory.resolve("blocked")));
    }

    private Result invoke(String name, String... args) throws Exception {
        var command = new ArrayList<String>(Arrays.asList(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("ballast.simulation.classpath"),
                "dev.ballast.simulation.SimulationRunner", "--trace-dir", directory.resolve(name).toString()));
        command.addAll(Arrays.asList(args));
        Path output = Files.createTempFile(directory, "process-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "Simulation subprocess timed out");
        return new Result(process.exitValue(), Files.readString(output));
    }

    private record Result(int code, String output) {}
}
