package org.jetbrains.research.anticopypaster.agents;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.*;

public class compile {

    public static class CompileError {
        public String file;
        public Integer line;
        public String message;
        public String raw;

        public CompileError(String file, Integer line, String message, String raw) {
            this.file = file;
            this.line = line;
            this.message = message;
            this.raw = raw;
        }
    }

    public static class CompileResult {
        public String status;
        public String buildTool;
        public List<CompileError> errors;
        public String summary;

        public CompileResult(String status, String buildTool, List<CompileError> errors, String summary) {
            this.status = status;
            this.buildTool = buildTool;
            this.errors = errors;
            this.summary = summary;
        }
    }

    public CompileResult analyze(String fileName, String rawCompileLog) {
        if (rawCompileLog == null || rawCompileLog.trim().isEmpty()) {
            return new CompileResult("compile_unknown", "unknown", Collections.emptyList(), "No compile log available.");
        }
        String log = rawCompileLog;
        String status;
        String lower = log.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "build success", "build successful", "build successful in")) {
            status = "compile_ok";
        } else if (containsAny(lower, "error:", "compilation error", "compilation failed", "failure: build failed")) {
            status = "compile_failed";
        } else {
            status = "compile_unknown";
        }
        String buildTool = detectBuildTool(log);
        List<CompileError> errors = extractErrors(log);
        if (errors.isEmpty() && "compile_failed".equals(status)) {
            // Fallback: try to find lines with "error" or "[ERROR]"
            List<CompileError> fallback = new ArrayList<>();
            String[] lines = log.split("\\r?\\n");
            for (String line : lines) {
                if (fallback.size() >= 5) break;
                String l = line.toLowerCase(Locale.ROOT);
                if (l.contains("error") || l.contains("[error]")) {
                    fallback.add(new CompileError(null, null, line.trim(), line));
                }
            }
            errors = fallback;
        }
        String summary;
        if ("compile_ok".equals(status)) {
            summary = "Compilation succeeded.";
        } else if ("compile_failed".equals(status)) {
            if (!errors.isEmpty()) {
                summary = String.format("Compilation failed with %d error(s). First: %s",
                        errors.size(), errors.get(0).message != null ? errors.get(0).message : "(no message)");
            } else {
                summary = "Compilation failed.";
            }
        } else {
            summary = "Could not determine compilation result.";
        }
        return new CompileResult(status, buildTool, errors, summary);
    }

    public CompileResult runAndAnalyze(String fileName, Function<Void, String> compileRunner) {
        if (compileRunner == null) {
            return new CompileResult("compile_unknown", "unknown", Collections.emptyList(), "No compile runner provided.");
        }
        String log = compileRunner.apply(null);
        return analyze(fileName, log);
    }

    private String detectBuildTool(String log) {
        if (log == null) return "unknown";
        String l = log.toLowerCase(Locale.ROOT);
        if (l.contains("gradle") || l.contains("task :")) return "gradle";
        if (l.contains("mvn") || l.contains("[info]")) return "maven";
        if (l.contains("[javac]")) return "ant/javac";
        return "unknown";
    }

    private List<CompileError> extractErrors(String log) {
        if (log == null) return Collections.emptyList();
        List<CompileError> errors = new ArrayList<>();
        // Pattern A: javac
        Pattern patA = Pattern.compile("^(.*\\.java):(\\d+):\\s*error:\\s*(.*)$", Pattern.MULTILINE);
        Matcher mA = patA.matcher(log);
        while (mA.find()) {
            String file = mA.group(1);
            Integer line;
            try { line = Integer.parseInt(mA.group(2)); } catch (Exception e) { line = null; }
            String msg = mA.group(3);
            errors.add(new CompileError(file, line, msg, mA.group(0)));
        }
        // Pattern B: Maven
        Pattern patB = Pattern.compile("\\[ERROR\\]\\s+(.*\\.java):\\[(\\d+),(\\d+)\\]\\s+(.*)");
        Matcher mB = patB.matcher(log);
        while (mB.find()) {
            String file = mB.group(1);
            Integer line;
            try { line = Integer.parseInt(mB.group(2)); } catch (Exception e) { line = null; }
            String msg = mB.group(4);
            errors.add(new CompileError(file, line, msg, mB.group(0)));
        }
        // Pattern C: Ant/javac
        Pattern patC = Pattern.compile("\\s*\\[javac\\]\\s+(.*\\.java):(\\d+):\\s*(.*)");
        Matcher mC = patC.matcher(log);
        while (mC.find()) {
            String file = mC.group(1);
            Integer line;
            try { line = Integer.parseInt(mC.group(2)); } catch (Exception e) { line = null; }
            String msg = mC.group(3);
            errors.add(new CompileError(file, line, msg, mC.group(0)));
        }
        return errors;
    }

    private boolean containsAny(String haystack, String... needles) {
        if (haystack == null) return false;
        for (String n : needles) {
            if (n == null) continue;
            if (haystack.contains(n.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
