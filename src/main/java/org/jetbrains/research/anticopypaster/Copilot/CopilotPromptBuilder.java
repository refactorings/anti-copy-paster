package org.jetbrains.research.anticopypaster.Copilot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CopilotPromptBuilder {
    private static final int MAX_PROMPT_CHARS = 60_000;
    private static final int MAX_SDK_PROMPT_CHARS = 140_000;
    private static final int MAX_PASTED_SNIPPET_CHARS = 8_000;
    private static final int MAX_FILE_SECTION_CHARS = 18_000;
    private static final int EXCERPT_CONTEXT_LINES = 24;

    private CopilotPromptBuilder() {
    }

    public record FileContext(String path, String source) {
        public FileContext {
            path = path == null || path.isBlank() ? "unknown.java" : path;
            source = source == null ? "" : source;
        }
    }

    public record CloneHint(String path, int startLine, int endLine, String code) {
        public CloneHint {
            path = path == null || path.isBlank() ? "unknown.java" : path;
            if (endLine < startLine) {
                endLine = startLine;
            }
            code = code == null ? "" : code;
        }
    }

    public static String buildPrompt(String pastedSnippet,
                                     List<FileContext> files,
                                     List<CloneHint> cloneHints) {
        List<FileContext> safeFiles = files == null ? List.of() : files;
        List<CloneHint> safeHints = cloneHints == null ? List.of() : cloneHints;

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are assisting AntiCopyPaster inside IntelliJ IDEA.\n")
                .append("A developer just pasted Java code. Your job is to decide whether the paste introduced duplicated code and, if it did, propose a safe Extract Method refactoring.\n\n")
                .append("Important constraints:\n")
                .append("- Preserve behavior exactly.\n")
                .append("- Prefer the smallest Extract Method refactoring that removes the duplicated fragment.\n")
                .append("- Update every affected call site.\n")
                .append("- Keep imports, visibility, checked exceptions, generics, and class ownership valid.\n")
                .append("- If the same helper cannot legally live in one owner for cross-file clones, explain the safest alternative.\n")
                .append("- Do not invent APIs or project files that are not present in the context.\n\n")
                .append("Return this structure:\n")
                .append("1. Verdict: CLONES_FOUND, NO_CLONES, or NO_SAFE_REFACTOR.\n")
                .append("2. Evidence: list file paths and line ranges for each duplicate occurrence.\n")
                .append("3. Refactoring plan: method name, parameters, return value, owner class, and call-site changes.\n")
                .append("4. Patch: a unified diff when possible. If a diff is not possible, provide only the changed methods/classes, not unrelated full files.\n")
                .append("5. Validation notes: compile/test risks and anything the developer should verify.\n\n");

        if (pastedSnippet != null && !pastedSnippet.isBlank()) {
            prompt.append("Pasted snippet:\n```java\n")
                    .append(truncate(pastedSnippet.strip(), MAX_PASTED_SNIPPET_CHARS))
                    .append("\n```\n\n");
        } else {
            prompt.append("Pasted snippet: unavailable.\n\n");
        }

        appendCloneHints(prompt, safeHints);
        appendFileContexts(prompt, safeFiles, safeHints, pastedSnippet);

        return truncate(prompt.toString(), MAX_PROMPT_CHARS);
    }

    public static String buildSdkJsonRefactorPrompt(String pastedSnippet,
                                                    List<FileContext> files,
                                                    List<CloneHint> cloneHints) {
        List<FileContext> safeFiles = files == null ? List.of() : files;
        List<CloneHint> safeHints = cloneHints == null ? List.of() : cloneHints;

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are AntiCopyPaster's automated Copilot refactoring engine.\n")
                .append("Analyze the pasted Java snippet and the provided Java files. If the paste introduced duplicated code, perform the smallest safe Extract Method refactoring.\n\n")
                .append("Hard requirements:\n")
                .append("- Preserve behavior exactly.\n")
                .append("- Only change files listed below.\n")
                .append("- Return full replacement source for every changed file.\n")
                .append("- Do not omit imports, package declarations, comments, or unrelated unchanged code from changed files.\n")
                .append("- Do not use markdown outside the JSON response.\n")
                .append("- If you cannot safely refactor, return NO_SAFE_REFACTOR and leave files empty.\n\n")
                .append("Return ONLY one JSON object with this exact shape:\n")
                .append("{\n")
                .append("  \"status\": \"CLONES_FOUND\" | \"NO_CLONES\" | \"NO_SAFE_REFACTOR\",\n")
                .append("  \"summary\": \"short explanation\",\n")
                .append("  \"evidence\": [{\"path\":\"relative path\",\"startLine\":1,\"endLine\":2,\"reason\":\"why this is a clone\"}],\n")
                .append("  \"files\": [{\"path\":\"relative path\",\"full_source\":\"complete replacement Java source\"}],\n")
                .append("  \"validation_notes\": [\"compile/test risks or assumptions\"]\n")
                .append("}\n\n");

        if (pastedSnippet != null && !pastedSnippet.isBlank()) {
            prompt.append("Pasted snippet:\n```java\n")
                    .append(pastedSnippet.strip())
                    .append("\n```\n\n");
        }

        appendCloneHints(prompt, safeHints);

        prompt.append("Editable files:\n");
        for (FileContext file : safeFiles) {
            prompt.append("### ")
                    .append(file.path())
                    .append("\n```java\n")
                    .append(file.source())
                    .append("\n```\n\n");
        }

        if (prompt.length() > MAX_SDK_PROMPT_CHARS) {
            throw new IllegalArgumentException(
                    "Copilot SDK prompt would be too large for safe automatic apply (chars="
                            + prompt.length()
                            + "). Select fewer files for Copilot SDK refactoring."
            );
        }

        return prompt.toString();
    }

    private static void appendCloneHints(StringBuilder prompt, List<CloneHint> cloneHints) {
        if (cloneHints.isEmpty()) {
            prompt.append("AntiCopyPaster candidate clone ranges: none precomputed. Search the working-set files yourself.\n\n");
            return;
        }

        prompt.append("AntiCopyPaster candidate clone ranges:\n");
        for (CloneHint hint : cloneHints) {
            prompt.append("- ")
                    .append(hint.path())
                    .append(": lines ")
                    .append(hint.startLine())
                    .append("-")
                    .append(hint.endLine());
            if (!hint.code().isBlank()) {
                prompt.append(" (preview: ")
                        .append(oneLine(truncate(hint.code(), 220)))
                        .append(")");
            }
            prompt.append("\n");
        }
        prompt.append("\n");
    }

    private static void appendFileContexts(StringBuilder prompt,
                                           List<FileContext> files,
                                           List<CloneHint> cloneHints,
                                           String pastedSnippet) {
        if (files.isEmpty()) {
            prompt.append("Working-set files: unavailable. Use the currently open editor context if Copilot can access it.\n");
            return;
        }

        prompt.append("Working-set file context");
        if (!cloneHints.isEmpty()) {
            prompt.append(" (focused around candidate ranges when possible)");
        }
        prompt.append(":\n\n");

        for (FileContext file : files) {
            String section = buildFileSection(file, cloneHintsForPath(cloneHints, file.path()), pastedSnippet);
            prompt.append(section).append("\n");
        }
    }

    private static String buildFileSection(FileContext file, List<CloneHint> cloneHints, String pastedSnippet) {
        String source = file.source();
        NumberedSource numberedSource = buildNumberedSource(source, cloneHints, pastedSnippet);

        StringBuilder section = new StringBuilder();
        section.append("### ")
                .append(file.path())
                .append(" (chars=")
                .append(source.length());
        if (numberedSource.excerpted()) {
            section.append(", excerpted");
        }
        section.append(")\n```java\n")
                .append(numberedSource.text())
                .append("\n```\n");
        return section.toString();
    }

    private record NumberedSource(String text, boolean excerpted) {
    }

    private static NumberedSource buildNumberedSource(String source, List<CloneHint> cloneHints, String pastedSnippet) {
        if (source == null || source.isBlank()) {
            return new NumberedSource("", false);
        }
        if (source.length() <= MAX_FILE_SECTION_CHARS) {
            return new NumberedSource(addLineNumbers(source), false);
        }

        List<int[]> ranges = new ArrayList<>();
        for (CloneHint hint : cloneHints) {
            if (hint.startLine() <= 0) continue;
            ranges.add(new int[]{
                    Math.max(1, hint.startLine() - EXCERPT_CONTEXT_LINES),
                    Math.max(hint.startLine(), hint.endLine() + EXCERPT_CONTEXT_LINES)
            });
        }

        if (ranges.isEmpty() && pastedSnippet != null && !pastedSnippet.isBlank()) {
            int[] pastedRange = findSnippetLineRange(source, pastedSnippet);
            if (pastedRange != null) {
                ranges.add(new int[]{
                        Math.max(1, pastedRange[0] - EXCERPT_CONTEXT_LINES),
                        Math.max(pastedRange[0], pastedRange[1] + EXCERPT_CONTEXT_LINES)
                });
            }
        }

        if (ranges.isEmpty()) {
            return new NumberedSource(addLineNumbers(truncate(source, MAX_FILE_SECTION_CHARS)), true);
        }

        List<int[]> merged = mergeRanges(ranges);
        String[] lines = normalizeNewlines(source).split("\n", -1);
        StringBuilder out = new StringBuilder();
        int previousEnd = 0;
        for (int[] range : merged) {
            int start = Math.max(1, range[0]);
            int end = Math.min(lines.length, range[1]);
            if (start > end) continue;
            if (out.length() > 0 && start > previousEnd + 1) {
                out.append("\n     | // ... lines ")
                        .append(previousEnd + 1)
                        .append("-")
                        .append(start - 1)
                        .append(" omitted ...\n");
            }
            for (int line = start; line <= end; line++) {
                out.append(formatLine(line, lines[line - 1]));
                if (line < end) out.append('\n');
            }
            previousEnd = end;
            if (out.length() >= MAX_FILE_SECTION_CHARS) {
                break;
            }
        }
        return new NumberedSource(truncate(out.toString(), MAX_FILE_SECTION_CHARS), true);
    }

    private static List<int[]> mergeRanges(List<int[]> ranges) {
        List<int[]> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(range -> range[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] range : sorted) {
            if (merged.isEmpty()) {
                merged.add(new int[]{range[0], range[1]});
                continue;
            }
            int[] last = merged.get(merged.size() - 1);
            if (range[0] <= last[1] + 1) {
                last[1] = Math.max(last[1], range[1]);
            } else {
                merged.add(new int[]{range[0], range[1]});
            }
        }
        return merged;
    }

    private static List<CloneHint> cloneHintsForPath(List<CloneHint> cloneHints, String path) {
        if (cloneHints == null || cloneHints.isEmpty() || path == null) {
            return List.of();
        }
        String normalizedPath = normalizePath(path);
        String fileName = fileName(normalizedPath);
        List<CloneHint> out = new ArrayList<>();
        for (CloneHint hint : cloneHints) {
            String hintPath = normalizePath(hint.path());
            if (hintPath.equals(normalizedPath) || fileName(hintPath).equals(fileName)) {
                out.add(hint);
            }
        }
        return out;
    }

    private static int[] findSnippetLineRange(String source, String snippet) {
        String normalizedSource = normalizeNewlines(source);
        String normalizedSnippet = normalizeNewlines(snippet).trim();
        if (normalizedSource.isBlank() || normalizedSnippet.isBlank()) {
            return null;
        }
        int index = normalizedSource.indexOf(normalizedSnippet);
        if (index < 0) {
            return null;
        }
        int startLine = 1;
        for (int i = 0; i < index; i++) {
            if (normalizedSource.charAt(i) == '\n') startLine++;
        }
        int endLine = startLine;
        for (int i = index; i < index + normalizedSnippet.length(); i++) {
            if (normalizedSource.charAt(i) == '\n') endLine++;
        }
        return new int[]{startLine, endLine};
    }

    private static String addLineNumbers(String source) {
        String[] lines = normalizeNewlines(source).split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            out.append(formatLine(i + 1, lines[i]));
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String formatLine(int lineNumber, String line) {
        return String.format("%4d | %s", lineNumber, line == null ? "" : line);
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) return "";
        if (maxChars <= 0 || value.length() <= maxChars) return value;
        return value.substring(0, Math.max(0, maxChars)) + "\n...<truncated>...";
    }

    private static String oneLine(String value) {
        if (value == null) return "";
        return value.replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizeNewlines(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static String fileName(String path) {
        if (path == null || path.isBlank()) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
