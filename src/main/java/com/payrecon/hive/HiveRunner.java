package com.payrecon.hive;

import com.payrecon.config.Config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Runs HiveQL scripts inside the Hive container with beeline (Hive's command-line SQL client),
 * the same "run the engine's own CLI through ProcessBuilder" approach as the NASA project.
 * Scripts live in ./hive, mounted read-only into the container at /opt/payrecon/hive.
 */
public final class HiveRunner {

    public record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    private final String container;

    public HiveRunner(Config config) {
        this.container = config.hiveContainer();
    }

    public Result run(List<String> beelineArgs) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", container, "beeline",
                "-u", "jdbc:hive2://localhost:10000/", "--silent=true", "--showHeader=true",
                "--outputformat=table", "--showWarnings=false"));
        cmd.addAll(beelineArgs);
        Process process = new ProcessBuilder(cmd).start();
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
        String stdout = readAll(process.getInputStream());
        int code = process.waitFor();
        return new Result(code, stdout, stderr.join());
    }

    /** Runs hive/&lt;file&gt;, optionally after an init script (-i), with ${hivevar:...} variables. */
    public Result script(String initFile, String file, Map<String, String> vars) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        vars.forEach((k, v) -> {
            args.add("--hivevar");
            args.add(k + "=" + v);
        });
        if (initFile != null) {
            args.add("-i");
            args.add("/opt/payrecon/hive/" + initFile);
        }
        args.add("-f");
        args.add("/opt/payrecon/hive/" + file);
        return run(args);
    }

    public boolean isReady() {
        try {
            return run(List.of("-e", "SELECT 1")).ok();
        } catch (Exception e) {
            return false;
        }
    }

    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
