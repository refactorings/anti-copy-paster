package org.jetbrains.research.anticopypaster.agents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step 4: Testing Agent (Tool Agent)
 *
 * This agent does NOT execute tests by itself; instead, it:
 *  1) accepts raw test runner output (e.g., Maven Surefire/Failsafe output),
 *  2) parses failures into a structured form,
 *  3) optionally asks an LLM to produce a short JSON-like summary that can be fed back
 *     to the Refactoring Agent for iterative fixes.
 *
 * Design goal: keep the agent framework composable. Execution is injected via a function.
 */
public class testing {

    // -----------------------------
    // Data models
    // -----------------------------

    public static class TestFailure {
        public String test;     // e.g., com.foo.FooTest#testBar
        public String reason;   // one-line reason (best-effort)
        public String stack;    // best-effort stack trace excerpt

        public TestFailure() {}

        public TestFailure(String test, String reason, String stack) {
            this.test = test;
            this.reason = reason;
            this.stack = stack;
        }
    }

    public static class TestResult {
        public String status;           // tests_passed | tests_failed | tests_unknown
        public String target;           // test target (class/method/all)
        public List<TestFailure> failures;
        public String summary;          // optional LLM summary (JSON-like)
        public String raw;              // raw test output (possibly truncated upstream)

        public TestResult() {
            this.failures = new ArrayList<>();
        }
    }

    /**
     * A minimal request object so callers can plug in their own test runner.
     * For Maven this could map to: mvn -q -Dtest=... test
     */
    public static class TestRunRequest {
        public String projectDir;
        public String testClassOrPattern;   // e.g., FooTest or com.foo.FooTest
        public String methodName;           // optional
        public boolean verify;              // if true, caller may run mvn verify instead of mvn test

        public TestRunRequest(String projectDir, String testClassOrPattern, String methodName, boolean verify) {
            this.projectDir = projectDir;
            this.testClassOrPattern = testClassOrPattern;
            this.methodName = methodName;
            this.verify = verify;
        }
    }

    // -----------------------------
    // Public API
    // -----------------------------

    /**
     * Run tests using an injected runner and parse the output.
     *
     * @param req       request describing what to run.
     * @param runner    tool runner that returns raw output (stdout+stderr) as a string.
     * @param llmCaller optional; if provided and tests fail, will be asked to summarize.
     * @param originalCode optional original Java code (for regression context).
     * @param refactoredCode optional refactored Java code (for regression context).
     */
    public TestResult runAndSummarize(
            TestRunRequest req,
            Function<TestRunRequest, String> runner,
            Function<String, String> llmCaller,
            String originalCode,
            String refactoredCode
    ) {
        Objects.requireNonNull(req, "req");
        Objects.requireNonNull(runner, "runner");

        String raw = safeRun(runner, req);
        TestResult parsed = analyze(buildTarget(req), raw);

        // Only ask the LLM if the caller provides it AND failures exist.
        if (llmCaller != null && "tests_failed".equals(parsed.status) && parsed.failures != null && !parsed.failures.isEmpty()) {
            String prompt = buildSummaryPrompt(parsed, originalCode, refactoredCode);
            try {
                parsed.summary = llmCaller.apply(prompt);
            } catch (Exception ignore) {
                // Keep the structured failures; summary is optional.
                parsed.summary = "";
            }
        }

        return parsed;
    }

    /**
     * Parse raw test output into a structured result.
     * This is intentionally tool-agnostic but optimized for Maven Surefire/Failsafe output.
     */
    public TestResult analyze(String target, String rawTestOutput) {
        TestResult r = new TestResult();
        r.target = target;
        r.raw = rawTestOutput == null ? "" : rawTestOutput;

        // Quick pass: detect success
        if (looksLikeTestSuccess(r.raw)) {
            r.status = "tests_passed";
            r.failures = Collections.emptyList();
            r.summary = "";
            return r;
        }

        // Parse failures / errors
        List<TestFailure> failures = parseSurefireFailures(r.raw);
        if (!failures.isEmpty()) {
            r.status = "tests_failed";
            r.failures = failures;
            r.summary = "";
            return r;
        }

        // If we see typical test failure keywords but couldn't parse details
        if (looksLikeTestFailure(r.raw)) {
            r.status = "tests_failed";
            r.failures = fallbackFailures(r.raw);
            r.summary = "";
            return r;
        }

        // Unknown
        r.status = "tests_unknown";
        r.failures = Collections.emptyList();
        r.summary = "";
        return r;
    }

    /**
     * Combine multiple per-test summaries (or stderr snippets) into one LLM prompt.
     * Useful when your orchestrator runs several tests and wants one combined diagnosis.
     */
    public String combineSummaries(
            List<String> summaries,
            Function<String, String> llmCaller,
            String originalCode,
            String refactoredCode
    ) {
        if (llmCaller == null) return "";
        if (summaries == null || summaries.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("Multiple test failure reports:\n");
        for (String s : summaries) {
            if (s == null || s.trim().isEmpty()) continue;
            sb.append("---\n").append(s).append("\n");
        }

        if (originalCode != null && !originalCode.isEmpty()) {
            sb.append("\nOriginal Java code:\n").append(originalCode).append("\n");
        }
        if (refactoredCode != null && !refactoredCode.isEmpty()) {
            sb.append("\nRefactored Java code:\n").append(refactoredCode).append("\n");
        }

        sb.append("\nRespond in JSON with keys: root_cause, likely_regression, failing_tests, suggested_fix_steps. ");
        return llmCaller.apply(sb.toString());
    }

    // -----------------------------
    // Internals
    // -----------------------------

    private static String safeRun(Function<TestRunRequest, String> runner, TestRunRequest req) {
        try {
            String out = runner.apply(req);
            return out == null ? "" : out;
        } catch (Exception e) {
            return "[TEST_RUNNER_EXCEPTION] " + e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private static String buildTarget(TestRunRequest req) {
        if (req == null) return "";
        String base = req.testClassOrPattern == null ? "" : req.testClassOrPattern;
        if (req.methodName != null && !req.methodName.trim().isEmpty()) {
            return base + "#" + req.methodName.trim();
        }
        return base.isEmpty() ? "all" : base;
    }

    private static boolean looksLikeTestSuccess(String raw) {
        if (raw == null) return false;
        String t = raw;
        // Maven success markers
        if (t.contains("BUILD SUCCESS")) return true;
        if (t.contains("BUILD SUCCESSFUL")) return true;
        // Surefire summary markers where Failures/Errors are 0
        // Example: Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
        Pattern p = Pattern.compile("Tests run:\\s*\\d+\\s*,\\s*Failures:\\s*0\\s*,\\s*Errors:\\s*0", Pattern.CASE_INSENSITIVE);
        return p.matcher(t).find() && !looksLikeTestFailure(t);
    }

    private static boolean looksLikeTestFailure(String raw) {
        if (raw == null) return false;
        String t = raw;
        // Common markers
        return t.contains("BUILD FAILURE")
                || t.contains("There are test failures")
                || t.contains("<<< FAILURE!")
                || t.contains("<<< ERROR!")
                || t.contains("Failed tests:")
                || t.contains("Tests in error:")
                || t.contains("[ERROR] Tests run:");
    }

    /**
     * Parse Maven Surefire/Failsafe style failures.
     * This parser is intentionally conservative; it tries to pull test names and a short reason.
     */
    private static List<TestFailure> parseSurefireFailures(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();

        List<TestFailure> out = new ArrayList<>();

        // Pattern A: "com.foo.FooTest.testBar  Time elapsed ...  <<< FAILURE!"
        Pattern header = Pattern.compile(
                "(^|\\n)([\\w.$]+)\\.([\\w$<>]+)\\s+Time elapsed[^\\n]*<<<\\s+(FAILURE|ERROR)!\\s*\\n",
                Pattern.MULTILINE);

        Matcher m = header.matcher(raw);
        while (m.find()) {
            String cls = m.group(2);
            String method = m.group(3);
            String test = cls + "#" + method;

            int start = m.end();
            int end = findNextFailureHeaderOrSection(raw, start);
            String block = raw.substring(start, Math.min(end, raw.length()));

            String reason = firstNonEmptyLine(block);
            String stack = takeStack(block, 20);

            out.add(new TestFailure(test, reason, stack));
        }

        // Pattern B: "Failed tests:  testBar(com.foo.FooTest): message"
        if (out.isEmpty()) {
            Pattern failedTests = Pattern.compile("Failed tests:\\s*(.+)", Pattern.CASE_INSENSITIVE);
            Matcher fm = failedTests.matcher(raw);
            if (fm.find()) {
                String tail = raw.substring(fm.end());
                // Grab a few lines after "Failed tests:" section
                String[] lines = tail.split("\\R");
                for (int i = 0; i < lines.length && i < 30; i++) {
                    String line = lines[i].trim();
                    if (line.isEmpty()) continue;
                    if (line.startsWith("[INFO]") || line.startsWith("[ERROR]")) {
                        // stop when Maven moves on
                        if (line.contains("Tests run:") || line.contains("BUILD")) break;
                    }
                    // Example: testBar(com.foo.FooTest): expected:<...> but was:<...>
                    Matcher tm = Pattern.compile("([\\w$<>]+)\\(([^)]+)\\)\\s*:?\\s*(.*)").matcher(line);
                    if (tm.find()) {
                        String method = tm.group(1);
                        String cls = tm.group(2);
                        String reason = tm.group(3);
                        out.add(new TestFailure(cls + "#" + method, reason, ""));
                    }
                }
            }
        }

        return out;
    }

    private static int findNextFailureHeaderOrSection(String raw, int fromIdx) {
        if (raw == null) return fromIdx;
        int next = raw.length();

        int a = indexOf(raw, "<<< FAILURE!", fromIdx);
        int b = indexOf(raw, "<<< ERROR!", fromIdx);
        int c = indexOf(raw, "Failed tests:", fromIdx);
        int d = indexOf(raw, "Tests in error:", fromIdx);
        int e = indexOf(raw, "[INFO] BUILD", fromIdx);
        int f = indexOf(raw, "[ERROR] Failed to execute goal", fromIdx);

        for (int idx : new int[]{a, b, c, d, e, f}) {
            if (idx >= 0) next = Math.min(next, idx);
        }
        return next;
    }

    private static int indexOf(String s, String needle, int from) {
        if (s == null || needle == null) return -1;
        return s.indexOf(needle, Math.max(0, from));
    }

    private static String firstNonEmptyLine(String block) {
        if (block == null) return "";
        String[] lines = block.split("\\R");
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            // skip Maven noise
            if (t.startsWith("[INFO]")) continue;
            if (t.startsWith("[ERROR]")) {
                // keep content but strip prefix
                return t.replaceFirst("\\[ERROR\\]\\s*", "");
            }
            return t;
        }
        return "";
    }

    private static String takeStack(String block, int maxLines) {
        if (block == null || block.isEmpty()) return "";
        String[] lines = block.split("\\R");
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (String line : lines) {
            if (kept >= maxLines) break;
            String t = line.trim();
            if (t.isEmpty()) continue;
            // Prefer stack-ish lines
            if (t.startsWith("at ") || t.contains("Exception") || t.contains("Error") || t.contains("Caused by")) {
                sb.append(line).append("\n");
                kept++;
            }
        }
        return sb.toString().trim();
    }

    private static List<TestFailure> fallbackFailures(String raw) {
        List<TestFailure> out = new ArrayList<>();
        if (raw == null) return out;

        // best-effort: collect a few lines containing keywords
        String[] lines = raw.split("\\R");
        int kept = 0;
        StringBuilder snippet = new StringBuilder();
        for (String line : lines) {
            String t = line.toLowerCase();
            if (t.contains("failure") || t.contains("error") || t.contains("assert") || t.contains("exception")) {
                snippet.append(line).append("\n");
                kept++;
                if (kept >= 12) break;
            }
        }
        out.add(new TestFailure("unknown", "test failures detected", snippet.toString().trim()));
        return out;
    }

    private static String buildSummaryPrompt(TestResult parsed, String originalCode, String refactoredCode) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a testing failure summarizer for a Java refactoring tool.\n");
        sb.append("Given test failures, produce a short JSON diagnosis that helps fix the refactoring.\n\n");

        sb.append("Target: ").append(parsed.target == null ? "" : parsed.target).append("\n\n");

        sb.append("Failures (structured):\n");
        for (TestFailure f : parsed.failures) {
            if (f == null) continue;
            sb.append("- test: ").append(nullToEmpty(f.test)).append("\n");
            if (f.reason != null && !f.reason.isEmpty()) sb.append("  reason: ").append(f.reason).append("\n");
            if (f.stack != null && !f.stack.isEmpty()) sb.append("  stack:\n").append(f.stack).append("\n");
        }

        // Include code context only when provided.
        if (originalCode != null && !originalCode.isEmpty()) {
            sb.append("\nOriginal Java code:\n").append(originalCode).append("\n");
        }
        if (refactoredCode != null && !refactoredCode.isEmpty()) {
            sb.append("\nRefactored Java code:\n").append(refactoredCode).append("\n");
        }

        // Minimal JSON contract for the refactoring loop.
        sb.append("\nRespond in JSON with keys: root_cause, failing_tests, suspicious_change, suggested_fix_steps.\n");
        sb.append("Avoid natural language paragraphs; keep values short.\n");

        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
