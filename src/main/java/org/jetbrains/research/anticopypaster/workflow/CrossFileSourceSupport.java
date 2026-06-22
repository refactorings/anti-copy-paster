package org.jetbrains.research.anticopypaster.workflow;

import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.logStage;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class CrossFileSourceSupport {
    private CrossFileSourceSupport() {}

    static List<CrossFileSource> readCrossFileSources(Project project,
                                                      List<VirtualFile> targets,
                                                      Consumer<String> viewer) throws IOException {
        java.util.ArrayList<CrossFileSource> sources = new java.util.ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (VirtualFile vf : targets) {
            if (vf == null || vf.isDirectory()) continue;
            if (!vf.getName().endsWith(".java")) {
                logStage(viewer, "WORKING_SET", "skipping non-Java file: " + vf.getPath());
                continue;
            }
            String absolutePath = vf.getPath();
            if (!seen.add(absolutePath)) continue;
            File ioFile = new File(absolutePath);
            if (!ioFile.isFile()) {
                logStage(viewer, "WORKING_SET", "skipping missing file: " + absolutePath);
                continue;
            }
            String source = readCurrentSource(vf, ioFile);
            CrossFileSource item = new CrossFileSource(vf, ioFile, absolutePath, toProjectRelativePath(project, absolutePath), source);
            sources.add(item);
            logStage(viewer, "WORKING_SET", item.relativePath + " (chars=" + source.length() + ")");
        }
        return sources;
    }

    static String readCurrentSource(VirtualFile vf, File ioFile) throws IOException {
        try {
            if (vf == null) {
                return Files.readString(ioFile.toPath(), StandardCharsets.UTF_8);
            }
            String documentText = ReadAction.compute(() -> {
                Document doc = FileDocumentManager.getInstance().getDocument(vf);
                return doc == null ? null : doc.getText();
            });
            if (documentText != null) {
                return documentText;
            }
        } catch (Throwable ignored) {}

        return Files.readString(ioFile.toPath(), StandardCharsets.UTF_8);
    }

    static String toProjectRelativePath(Project project, String absolutePath) {
        if (project == null || absolutePath == null) return absolutePath == null ? "" : absolutePath;
        String base = project.getBasePath();
        if (base == null || base.isBlank()) return absolutePath;
        String normalizedBase = base.replace("\\", "/");
        String normalizedPath = absolutePath.replace("\\", "/");
        if (normalizedPath.startsWith(normalizedBase)) {
            String rel = normalizedPath.substring(normalizedBase.length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            return rel.isBlank() ? absolutePath : rel;
        }
        return absolutePath;
    }

    static Map<String, CrossFileSource> buildCrossFileSourceIndex(List<CrossFileSource> sources) {
        java.util.LinkedHashMap<String, CrossFileSource> index = new java.util.LinkedHashMap<>();
        if (sources == null) return index;
        for (CrossFileSource source : sources) {
            if (source == null) continue;
            putCrossFileKey(index, source.absolutePath, source);
            putCrossFileKey(index, source.relativePath, source);
            putCrossFileKey(index, source.vf == null ? "" : source.vf.getName(), source);
        }
        return index;
    }

    static void putCrossFileKey(Map<String, CrossFileSource> index, String key, CrossFileSource source) {
        if (index == null || key == null || key.isBlank() || source == null) return;
        index.putIfAbsent(key, source);
        index.putIfAbsent(key.replace("\\", "/"), source);
    }

    static CrossFileSource resolveCrossFileSource(String path, Map<String, CrossFileSource> index) {
        if (path == null || index == null) return null;
        String trimmed = path.trim();
        CrossFileSource direct = index.get(trimmed);
        if (direct != null) return direct;
        direct = index.get(trimmed.replace("\\", "/"));
        if (direct != null) return direct;
        try {
            String normalized = new File(trimmed).getAbsolutePath();
            direct = index.get(normalized);
            if (direct != null) return direct;
        } catch (Throwable ignored) {}
        return index.get(new File(trimmed).getName());
    }

    static CrossFileOccurrence resolveVerifiedOccurrence(CrossFileSource source,
                                                         int startLine,
                                                         int endLine,
                                                         String snippet) {
        if (source == null || source.source == null || source.source.isBlank()) return null;

        boolean hasSnippet = snippet != null && !snippet.isBlank();
        if (hasSnippet && isValidLineRange(source.source, startLine, endLine)) {
            String rangeText = sliceLines(source.source, startLine, endLine);
            if (snippetsEquivalent(rangeText, snippet)) {
                return new CrossFileOccurrence(source, startLine, endLine, rangeText);
            }
        }

        if (hasSnippet) {
            int[] foundRange = findSnippetLineRange(source.source, snippet);
            if (foundRange != null) {
                String verifiedSnippet = sliceLines(source.source, foundRange[0], foundRange[1]);
                return new CrossFileOccurrence(source, foundRange[0], foundRange[1], verifiedSnippet);
            }
            return null;
        }

        if (isValidLineRange(source.source, startLine, endLine)) {
            return new CrossFileOccurrence(source, startLine, endLine, sliceLines(source.source, startLine, endLine));
        }
        return null;
    }

    static java.util.List<CrossFileOccurrence> resolvePastedSnippetAnchors(List<CrossFileSource> sources,
                                                                           String pastedSnippet) {
        java.util.ArrayList<CrossFileOccurrence> anchors = new java.util.ArrayList<>();
        if (sources == null || pastedSnippet == null || pastedSnippet.isBlank()) return anchors;
        for (CrossFileSource source : sources) {
            CrossFileOccurrence anchor = resolveVerifiedOccurrence(source, -1, -1, pastedSnippet);
            if (anchor != null) anchors.add(anchor);
        }
        return anchors;
    }

    static boolean cloneContainsPastedAnchor(CrossFileClone clone,
                                             java.util.List<CrossFileOccurrence> pastedAnchors) {
        if (clone == null || pastedAnchors == null || pastedAnchors.isEmpty()) return false;
        for (CrossFileOccurrence occurrence : clone.occurrences) {
            if (occurrence == null || occurrence.source == null) continue;
            for (CrossFileOccurrence anchor : pastedAnchors) {
                if (anchor == null || anchor.source != occurrence.source) continue;
                if (lineRangesOverlap(
                        occurrence.startLine,
                        occurrence.endLine,
                        anchor.startLine,
                        anchor.endLine
                )) {
                    return true;
                }
                if (containsSnippetIgnoringWhitespace(occurrence.snippet, anchor.snippet)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean lineRangesOverlap(int startA, int endA, int startB, int endB) {
        if (startA <= 0 || endA < startA || startB <= 0 || endB < startB) return false;
        return startA <= endB && startB <= endA;
    }

    static boolean containsSnippetIgnoringWhitespace(String container, String snippet) {
        String left = collapseWhitespace(container);
        String right = collapseWhitespace(snippet);
        return !left.isBlank() && !right.isBlank() && left.contains(right);
    }

    static boolean isValidLineRange(String source, int startLine, int endLine) {
        if (source == null || startLine <= 0 || endLine < startLine) return false;
        int lineCount = countLines(source);
        return startLine <= lineCount && endLine <= lineCount;
    }

    static int countLines(String source) {
        if (source == null || source.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') count++;
        }
        return count;
    }

    static boolean snippetsEquivalent(String a, String b) {
        String left = collapseWhitespace(a);
        String right = collapseWhitespace(b);
        return !left.isBlank() && left.equals(right);
    }

    static int[] findSnippetLineRange(String source, String snippet) {
        if (source == null || snippet == null || snippet.isBlank()) return null;
        String normalizedSource = source.replace("\r\n", "\n").replace("\r", "\n");
        String normalizedSnippet = snippet.replace("\r\n", "\n").replace("\r", "\n").trim();
        if (normalizedSnippet.isBlank()) return null;

        int exact = normalizedSource.indexOf(normalizedSnippet);
        if (exact >= 0) {
            return toLineRangeByIndex(normalizedSource, exact, exact + normalizedSnippet.length());
        }

        String[] snippetLines = normalizedSnippet.split("\n", -1);
        java.util.ArrayList<String> wanted = new java.util.ArrayList<>();
        for (String line : snippetLines) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) wanted.add(trimmed);
        }
        if (wanted.isEmpty()) return null;

        String[] sourceLines = normalizedSource.split("\n", -1);
        for (int start = 0; start < sourceLines.length; start++) {
            int sourceIndex = start;
            int wantedIndex = 0;
            int firstMatched = -1;
            int lastMatched = -1;
            while (sourceIndex < sourceLines.length && wantedIndex < wanted.size()) {
                String sourceLine = sourceLines[sourceIndex] == null ? "" : sourceLines[sourceIndex].trim();
                if (sourceLine.isEmpty()) {
                    sourceIndex++;
                    continue;
                }
                if (!sourceLine.equals(wanted.get(wantedIndex))) {
                    break;
                }
                if (firstMatched < 0) firstMatched = sourceIndex;
                lastMatched = sourceIndex;
                sourceIndex++;
                wantedIndex++;
            }
            if (wantedIndex == wanted.size() && firstMatched >= 0 && lastMatched >= firstMatched) {
                return new int[]{firstMatched + 1, lastMatched + 1};
            }
        }
        return null;
    }

    static int[] toLineRangeByIndex(String text, int startIdx, int endIdxExclusive) {
        int startLine = 1;
        for (int i = 0; i < startIdx && i < text.length(); i++) {
            if (text.charAt(i) == '\n') startLine++;
        }
        int endLine = startLine;
        for (int i = startIdx; i < endIdxExclusive && i < text.length(); i++) {
            if (text.charAt(i) == '\n') endLine++;
        }
        return new int[]{startLine, endLine};
    }

    static String collapseWhitespace(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", " ").trim();
    }

    static String sliceLines(String source, int startLine, int endLine) {
        if (source == null || startLine <= 0 || endLine < startLine) return "";
        String[] lines = source.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        int start = Math.max(0, Math.min(lines.length, startLine) - 1);
        int end = Math.max(start, Math.min(lines.length, endLine));
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}
