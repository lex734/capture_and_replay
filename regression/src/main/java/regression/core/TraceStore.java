package regression.core;

import regression.annotations.OutcomeExpectation;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Manages persisted traces under {@code <storeRoot>/<simpleClassName>/<sha256>/}. */
public final class TraceStore {

    private static final String[] TRACE_FILES = {
        "trace.bin", "trace-semantic.tsv", "trace-reduced.tsv"
    };
    private static final String OUTCOME_FILE = "outcome.properties";

    private final Path classRoot;

    public TraceStore(Path storeRoot, String simpleClassName) throws IOException {
        this.classRoot = storeRoot.resolve(simpleClassName);
        Files.createDirectories(classRoot);
    }

    /** Deletes all stored traces for this class and recreates the empty directory. */
    public void clear() throws IOException {
        ProcessRunner.deleteRecursivelyIfExists(classRoot);
        Files.createDirectories(classRoot);
    }

    /**
     * Returns true if a trace with this hash is already stored.
     */
    public boolean contains(String sha256) {
        return Files.isDirectory(classRoot.resolve(sha256));
    }

    /**
     * Saves the three trace files from {@code workDir} under a directory named by
     * {@code sha256}, writing outcome metadata alongside them.
     */
    public void save(String sha256, Path workDir, OutcomeExpectation expect,
                     String observedOutput, String desc) throws IOException {
        Path dest = classRoot.resolve(sha256);
        Files.createDirectories(dest);

        for (String name : TRACE_FILES) {
            Path src = workDir.resolve(name);
            if (Files.exists(src)) {
                Files.copy(src, dest.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Properties p = new Properties();
        p.setProperty("sha256", sha256);
        p.setProperty("expect", expect.name());
        p.setProperty("output", observedOutput == null ? "" : observedOutput);
        p.setProperty("desc",   desc == null ? "" : desc);
        p.setProperty("capturedAt", Instant.now().toString());
        try (Writer w = Files.newBufferedWriter(dest.resolve(OUTCOME_FILE))) {
            p.store(w, "Regression Trace Outcome");
        }
    }

    /**
     * Loads all stored traces, FORBIDDEN ones first, then INTERESTING.
     */
    public List<StoredTrace> loadAll() throws IOException {
        List<StoredTrace> forbidden    = new ArrayList<>();
        List<StoredTrace> interesting  = new ArrayList<>();

        if (!Files.isDirectory(classRoot)) return Collections.emptyList();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(classRoot)) {
            for (Path entry : ds) {
                if (!Files.isDirectory(entry)) continue;
                Path propsFile = entry.resolve(OUTCOME_FILE);
                if (!Files.exists(propsFile)) continue;

                Properties p = new Properties();
                try (Reader r = Files.newBufferedReader(propsFile)) { p.load(r); }

                String sha256 = p.getProperty("sha256", entry.getFileName().toString());
                String expectName = p.getProperty("expect", "INTERESTING");
                OutcomeExpectation expect;
                try {
                    expect = OutcomeExpectation.valueOf(expectName);
                } catch (IllegalArgumentException e) {
                    expect = OutcomeExpectation.INTERESTING;
                }
                String output = p.getProperty("output", "");
                String desc   = p.getProperty("desc",   "");

                StoredTrace st = new StoredTrace(sha256, entry, expect, output, desc);
                if (expect == OutcomeExpectation.FORBIDDEN) {
                    forbidden.add(st);
                } else {
                    interesting.add(st);
                }
            }
        }

        List<StoredTrace> result = new ArrayList<>(forbidden);
        result.addAll(interesting);
        return result;
    }

    /** Computes SHA-256 of the file at {@code path} and returns the hex string. */
    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Files.readAllBytes(path));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("SHA-256 computation failed: " + e.getMessage(), e);
        }
    }
}
