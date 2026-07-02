package org.jetbrains.research.anticopypaster.workflow;

import com.intellij.openapi.vfs.VirtualFile;
import java.io.File;

final class CrossFileSource {
    final VirtualFile vf;
    final File ioFile;
    final String absolutePath;
    final String relativePath;
    final String source;

    CrossFileSource(VirtualFile vf, File ioFile, String absolutePath, String relativePath, String source) {
        this.vf = vf;
        this.ioFile = ioFile;
        this.absolutePath = absolutePath == null ? "" : absolutePath;
        this.relativePath = relativePath == null || relativePath.isBlank() ? this.absolutePath : relativePath;
        this.source = source == null ? "" : source;
    }
}

final class CrossFileRefactorResult {
    boolean parsed;
    String status = "";
    String summary = "";
    String message = "";
    String rawPlanJson = "";
    String selectedPanelistId = "";
    String curatorSummary = "";
    String curatorFeedback = "";
    double curatorConfidence;
    final java.util.LinkedHashMap<CrossFileSource, String> newSourcesByFile = new java.util.LinkedHashMap<>();
    final java.util.LinkedHashMap<String, CrossFileNewSource> newFilesByPath = new java.util.LinkedHashMap<>();
    final java.util.ArrayList<String> warnings = new java.util.ArrayList<>();
    final java.util.ArrayList<String> curatorMatchedCategories = new java.util.ArrayList<>();

    boolean hasChanges() {
        return !newSourcesByFile.isEmpty() || !newFilesByPath.isEmpty();
    }

    int changedFileCount() {
        return newSourcesByFile.size() + newFilesByPath.size();
    }
}

final class CrossFileNewSource {
    final String relativePath;
    final File ioFile;
    final String source;

    CrossFileNewSource(String relativePath, File ioFile, String source) {
        this.relativePath = relativePath == null || relativePath.isBlank() ? "CrossFileCloneHelper.java" : relativePath;
        this.ioFile = ioFile;
        this.source = source == null ? "" : source;
    }
}

final class CrossFileDetectionResult {
    boolean parsed;
    String status = "";
    String summary = "";
    String message = "";
    final java.util.ArrayList<CrossFileClone> clones = new java.util.ArrayList<>();
    final java.util.ArrayList<String> warnings = new java.util.ArrayList<>();
}

final class CrossFileClone {
    String id = "";
    String refactorType = "";
    String reason = "";
    final java.util.ArrayList<CrossFileOccurrence> occurrences = new java.util.ArrayList<>();

    String displayId() {
        return id == null || id.isBlank() ? "cross_clone" : id;
    }

    java.util.LinkedHashSet<CrossFileSource> affectedSources() {
        java.util.LinkedHashSet<CrossFileSource> out = new java.util.LinkedHashSet<>();
        for (CrossFileOccurrence occurrence : occurrences) {
            if (occurrence != null && occurrence.source != null) {
                out.add(occurrence.source);
            }
        }
        return out;
    }

    String affectedPathSummary() {
        java.util.ArrayList<String> paths = new java.util.ArrayList<>();
        for (CrossFileSource source : affectedSources()) {
            if (source != null) paths.add(source.relativePath);
        }
        return String.join(", ", paths);
    }
}

final class CrossFileOccurrence {
    final CrossFileSource source;
    final int startLine;
    final int endLine;
    final String snippet;

    CrossFileOccurrence(CrossFileSource source, int startLine, int endLine, String snippet) {
        this.source = source;
        this.startLine = startLine;
        this.endLine = endLine;
        this.snippet = snippet == null ? "" : snippet;
    }
}

final class CrossFileUsefulnessResult {
    boolean parsed;
    boolean useful = true;
    String summary = "";
    String feedback = "";
    String message = "";
}

final class CrossFilePanelistSpec {
    final String id;
    final String title;

    CrossFilePanelistSpec(String id, String title) {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
    }
}

final class CrossFileDetectionPanelistOutcome {
    final String panelistId;
    final String rawResponse;
    final CrossFileDetectionResult result;
    final boolean parsed;
    final String error;

    CrossFileDetectionPanelistOutcome(String panelistId,
                                      String rawResponse,
                                      CrossFileDetectionResult result,
                                      boolean parsed,
                                      String error) {
        this.panelistId = panelistId == null ? "" : panelistId;
        this.rawResponse = rawResponse == null ? "" : rawResponse;
        this.result = result;
        this.parsed = parsed;
        this.error = error == null ? "" : error;
    }
}

final class CrossFileRefactorPanelistOutcome {
    final String panelistId;
    final String rawResponse;
    final CrossFileRefactorResult result;
    final boolean parsed;
    final String error;

    CrossFileRefactorPanelistOutcome(String panelistId,
                                     String rawResponse,
                                     CrossFileRefactorResult result,
                                     boolean parsed,
                                     String error) {
        this.panelistId = panelistId == null ? "" : panelistId;
        this.rawResponse = rawResponse == null ? "" : rawResponse;
        this.result = result;
        this.parsed = parsed;
        this.error = error == null ? "" : error;
    }
}

final class CrossFilePanelistSelection {
    boolean parsed;
    String selectedPanelistId = "";
    String summary = "";
    String feedback = "";
    double confidence;
    String rawResponse = "";
    String error = "";
    final java.util.ArrayList<String> matchedCategories = new java.util.ArrayList<>();
}

final class CrossFileOccurrenceSpec {
    final String occurrenceId;
    final CrossFileOccurrence occurrence;

    CrossFileOccurrenceSpec(String occurrenceId, CrossFileOccurrence occurrence) {
        this.occurrenceId = occurrenceId == null ? "" : occurrenceId;
        this.occurrence = occurrence;
    }
}

final class CrossFileOccurrenceRewrite {
    String occurrenceId = "";
    String replacementCode = "";
}

final class CrossFileSharedHelperPlan {
    String strategy = "";
    String path = "";
    String relativePath = "";
    String packageName = "";
    String className = "";
    String helperMethod = "";
    String justification = "";
    File ioFile;
    CrossFileSource existingTarget;
    boolean publicClass;
    boolean newHelperPathAlreadyExists;
    final java.util.ArrayList<String> imports = new java.util.ArrayList<>();

    boolean isCentralizedStrategy() {
        return isExistingFileStrategy()
                || "new_helper_class".equalsIgnoreCase(strategy);
    }

    boolean isExistingFileStrategy() {
        return "existing_selected_file".equalsIgnoreCase(strategy)
                || "existing_project_file".equalsIgnoreCase(strategy);
    }
}

final class CrossFileRefactorContext {
    final java.util.ArrayList<CrossFileOccurrenceTypeFact> occurrenceFacts = new java.util.ArrayList<>();
    final java.util.ArrayList<CrossFileAllowedHelperTarget> allowedTargets = new java.util.ArrayList<>();
    final java.util.ArrayList<String> warnings = new java.util.ArrayList<>();

    java.util.List<CrossFileAllowedHelperTarget> targetsByStrategy(String strategy) {
        java.util.ArrayList<CrossFileAllowedHelperTarget> out = new java.util.ArrayList<>();
        if (strategy == null) return out;
        for (CrossFileAllowedHelperTarget target : allowedTargets) {
            if (target != null && strategy.equalsIgnoreCase(target.strategy)) out.add(target);
        }
        return out;
    }

    boolean allowsExistingTarget(String strategy, String path) {
        if (strategy == null || path == null || path.isBlank()) return false;
        String normalizedPath = normalizePath(path);
        for (CrossFileAllowedHelperTarget target : allowedTargets) {
            if (target == null || !strategy.equalsIgnoreCase(target.strategy)) continue;
            if (target.matchesPath(normalizedPath)) return true;
        }
        return false;
    }

    static String normalizePath(String value) {
        if (value == null) return "";
        return value.trim().replace('\\', '/');
    }
}

final class CrossFileOccurrenceTypeFact {
    String occurrenceId = "";
    String path = "";
    String enclosingClassFqn = "";
    String simpleName = "";
    final java.util.ArrayList<String> superClassFqns = new java.util.ArrayList<>();
}

final class CrossFileAllowedHelperTarget {
    String strategy = "";
    String path = "";
    String absolutePath = "";
    String classFqn = "";
    String reason = "";
    CrossFileSource source;

    boolean matchesPath(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isBlank()) return false;
        String rel = CrossFileRefactorContext.normalizePath(path);
        String abs = CrossFileRefactorContext.normalizePath(absolutePath);
        String name = rel;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        return normalizedPath.equals(rel)
                || normalizedPath.equals(abs)
                || (!name.isBlank() && normalizedPath.equals(name))
                || (!rel.isBlank() && normalizedPath.endsWith("/" + rel));
    }
}
