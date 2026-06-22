package org.jetbrains.research.anticopypaster.workflow;

import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.sanitizeCrossFileName;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.logStage;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jetbrains.research.anticopypaster.agents.compilation;

final class CrossFileProposalSupport {
    private CrossFileProposalSupport() {}

    static compilation.CompileResult compileCrossFileProposal(WorkflowJavaBuildSupport javaBuildSupport,
                                                              CrossFileRefactorResult result,
                                                              Consumer<String> viewer) {
        compilation compileAgent = new compilation();
        if (javaBuildSupport == null || result == null || !result.hasChanges()) {
            return new compilation.CompileResult(
                    "compile_unknown",
                    "javac",
                    java.util.Collections.emptyList(),
                    "No proposed cross-file sources were available for compilation."
            );
        }
        javaBuildSupport.clearPatchedClassesDir();

        try {
            java.util.LinkedHashMap<File, String> proposedSourcesByFile = new java.util.LinkedHashMap<>();
            java.util.LinkedHashMap<File, String> baselineSourcesByFile = new java.util.LinkedHashMap<>();
            for (Map.Entry<CrossFileSource, String> entry : result.newSourcesByFile.entrySet()) {
                if (entry == null || entry.getKey() == null) continue;
                proposedSourcesByFile.put(entry.getKey().ioFile, entry.getValue());
                baselineSourcesByFile.put(entry.getKey().ioFile, entry.getKey().source);
            }
            for (CrossFileNewSource source : result.newFilesByPath.values()) {
                if (source == null || source.ioFile == null) continue;
                proposedSourcesByFile.put(source.ioFile, source.source);
            }

            String classpath = javaBuildSupport.buildCompileClasspathWithSourceRoots("");
            WorkflowJavaBuildSupport.CompileAttempt attempt =
                    javaBuildSupport.compileProposedSourcesToTempAttempt(proposedSourcesByFile, classpath);
            String compileLog = attempt.toCompileLog();
            if (compileLog != null && !compileLog.isBlank()) {
                String preview = compileLog.length() > 4000 ? compileLog.substring(0, 4000) + "\n..." : compileLog;
                logStage(viewer, "COMPILE", preview);
            }
            compilation.CompileResult proposalResult = compileAgent.analyze("Cross Files", compileLog);
            if (proposalResult != null && !"compile_ok".equals(proposalResult.status)) {
                compilation.CompileResult baselineResult = compileCrossFileBaseline(
                        javaBuildSupport,
                        compileAgent,
                        baselineSourcesByFile,
                        classpath,
                        viewer
                );
                compilation.CompileResult adjusted =
                        ignoreCrossFileBaselineCompileErrors(result.newSourcesByFile.keySet(), proposalResult, baselineResult);
                if (adjusted != null && adjusted != proposalResult) {
                    logStage(viewer, "COMPILE", adjusted.summary);
                }
                if (adjusted != null && "compile_ok".equals(adjusted.status)) {
                    javaBuildSupport.setPatchedClassesDir(attempt.outputDir);
                } else {
                    javaBuildSupport.clearPatchedClassesDir();
                }
                return adjusted == null ? proposalResult : adjusted;
            }
            if (proposalResult != null && "compile_ok".equals(proposalResult.status)) {
                javaBuildSupport.setPatchedClassesDir(attempt.outputDir);
            } else {
                javaBuildSupport.clearPatchedClassesDir();
            }
            return proposalResult;
        } catch (Exception e) {
            javaBuildSupport.clearPatchedClassesDir();
            String log = "BUILD FAILED\nCompilation failed:\n" + e.getClass().getSimpleName() + ": " + e.getMessage();
            logStage(viewer, "COMPILE", log);
            return compileAgent.analyze("Cross Files", log);
        }
    }

    static compilation.CompileResult compileCrossFileBaseline(WorkflowJavaBuildSupport javaBuildSupport,
                                                              compilation compileAgent,
                                                              Map<File, String> baselineSourcesByFile,
                                                              String classpath,
                                                              Consumer<String> viewer) {
        if (javaBuildSupport == null || compileAgent == null || baselineSourcesByFile == null || baselineSourcesByFile.isEmpty()) {
            return null;
        }
        try {
            WorkflowJavaBuildSupport.CompileAttempt baselineAttempt =
                    javaBuildSupport.compileProposedSourcesToTempAttempt(baselineSourcesByFile, classpath);
            String baselineLog = baselineAttempt.toCompileLog();
            if (baselineLog != null && !baselineLog.isBlank()) {
                String preview = baselineLog.length() > 4000 ? baselineLog.substring(0, 4000) + "\n..." : baselineLog;
                logStage(viewer, "COMPILE", "baseline raw output:\n" + preview);
            }
            compilation.CompileResult baselineResult = compileAgent.analyze("Cross Files baseline", baselineLog);
            logStage(viewer, "COMPILE", "baseline check: "
                    + (baselineResult == null ? "unknown" : baselineResult.summary));
            return baselineResult;
        } catch (Exception e) {
            String log = "BUILD FAILED\nBaseline compilation failed:\n"
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
            logStage(viewer, "COMPILE", log);
            return compileAgent.analyze("Cross Files baseline", log);
        }
    }

    static compilation.CompileResult ignoreCrossFileBaselineCompileErrors(java.util.Set<CrossFileSource> changedSources,
                                                                         compilation.CompileResult proposal,
                                                                         compilation.CompileResult baseline) {
        if (proposal == null || "compile_ok".equals(proposal.status)) return proposal;
        if (proposal.errors == null || proposal.errors.isEmpty()) return proposal;
        if (baseline == null || baseline.errors == null || baseline.errors.isEmpty()) return proposal;

        java.util.Set<String> changedFileBasenames = new java.util.LinkedHashSet<>();
        if (changedSources != null) {
            for (CrossFileSource source : changedSources) {
                if (source == null || source.ioFile == null) continue;
                String name = source.ioFile.getName();
                if (name != null && !name.isBlank()) changedFileBasenames.add(name);
            }
        }

        java.util.Set<String> baselineKeys = new java.util.LinkedHashSet<>();
        for (compilation.CompileError error : baseline.errors) {
            String key = crossFileCompileErrorKey(changedFileBasenames, error);
            if (key != null && !key.isBlank()) baselineKeys.add(key);
        }
        if (baselineKeys.isEmpty()) return proposal;

        java.util.ArrayList<compilation.CompileError> newErrors = new java.util.ArrayList<>();
        for (compilation.CompileError error : proposal.errors) {
            String key = crossFileCompileErrorKey(changedFileBasenames, error);
            if (key == null || key.isBlank() || !baselineKeys.contains(key)) {
                newErrors.add(error);
            }
        }

        if (newErrors.isEmpty()) {
            String summary = "Compilation produced only " + proposal.errors.size()
                    + " pre-existing baseline error(s); ignoring them for this proposal.";
            return new compilation.CompileResult("compile_ok", proposal.buildTool, proposal.errors, summary);
        }

        String first = newErrors.get(0).message != null ? newErrors.get(0).message : "(no message)";
        String summary = "Compilation failed with " + newErrors.size() + " new error(s). First: " + first;
        return new compilation.CompileResult(proposal.status, proposal.buildTool, newErrors, summary);
    }

    static String crossFileCompileErrorKey(java.util.Set<String> changedFileBasenames,
                                           compilation.CompileError error) {
        if (error == null) return "";
        String file = normalizeCrossFileCompileErrorFile(changedFileBasenames, error.file);
        String message = error.message != null ? error.message : error.raw;
        message = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        if (file.isBlank() && message.isBlank()) return "";
        String line = "";
        if (!isChangedCrossFileCompileError(changedFileBasenames, error.file) && error.line != null) {
            line = ":" + error.line;
        }
        return file + line + "|" + message;
    }

    static String normalizeCrossFileCompileErrorFile(java.util.Set<String> changedFileBasenames, String file) {
        if (file == null || file.isBlank()) return "";
        String normalized = file.replace('\\', '/').trim();
        int slash = normalized.lastIndexOf('/');
        String basename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (changedFileBasenames != null && changedFileBasenames.contains(basename)) {
            return basename;
        }
        return normalized;
    }

    static boolean isChangedCrossFileCompileError(java.util.Set<String> changedFileBasenames, String file) {
        if (file == null || file.isBlank() || changedFileBasenames == null || changedFileBasenames.isEmpty()) {
            return false;
        }
        String normalized = file.replace('\\', '/').trim();
        int slash = normalized.lastIndexOf('/');
        String basename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return changedFileBasenames.contains(basename);
    }

    static void saveCrossFileProposals(Project project,
                                       CrossFileRefactorResult result,
                                       Consumer<String> viewer) {
        try {
            String basePath = project == null ? null : project.getBasePath();
            if (basePath == null || basePath.isBlank() || result == null || !result.hasChanges()) return;
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            File outDir = new File(basePath, ".anticopypaster" + File.separator + "proposals" + File.separator + "cross-files-" + ts);
            if (!outDir.exists()) outDir.mkdirs();
            for (Map.Entry<CrossFileSource, String> entry : result.newSourcesByFile.entrySet()) {
                CrossFileSource source = entry.getKey();
                if (source == null) continue;
                File outFile = new File(outDir, sanitizeCrossFileName(source.relativePath) + ".proposed.java");
                Files.writeString(outFile.toPath(), entry.getValue() == null ? "" : entry.getValue(), StandardCharsets.UTF_8);
            }
            for (CrossFileNewSource source : result.newFilesByPath.values()) {
                if (source == null) continue;
                File outFile = new File(outDir, sanitizeCrossFileName(source.relativePath) + ".new.java");
                Files.writeString(outFile.toPath(), source.source, StandardCharsets.UTF_8);
            }
            logStage(viewer, "REFACTOR_CODE", "cross-file proposed sources saved to: " + outDir.getAbsolutePath());
        } catch (Throwable t) {
            logStage(viewer, "REFACTOR_CODE", "failed to save cross-file proposals: " + t.getMessage());
        }
    }

    static String buildCrossFileDiffBundle(List<CrossFileSource> sources, Map<CrossFileSource, String> overrides) {
        StringBuilder sb = new StringBuilder();
        if (sources == null) return "";
        for (CrossFileSource source : sources) {
            if (source == null) continue;
            sb.append("===== FILE: ").append(source.relativePath).append(" =====\n");
            String text = overrides == null ? null : overrides.get(source);
            sb.append(text == null ? source.source : text);
            if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }

    static String buildCrossFileDiffBundle(List<CrossFileSource> sources, CrossFileRefactorResult result) {
        StringBuilder sb = new StringBuilder(buildCrossFileDiffBundle(sources, result == null ? null : result.newSourcesByFile));
        if (result != null) {
            java.util.LinkedHashSet<String> listedExistingPaths = new java.util.LinkedHashSet<>();
            if (sources != null) {
                for (CrossFileSource source : sources) {
                    if (source != null) listedExistingPaths.add(source.relativePath);
                }
            }
            for (Map.Entry<CrossFileSource, String> entry : result.newSourcesByFile.entrySet()) {
                CrossFileSource source = entry == null ? null : entry.getKey();
                if (source == null || listedExistingPaths.contains(source.relativePath)) continue;
                sb.append("===== EXTRA FILE: ").append(source.relativePath).append(" =====\n");
                String text = entry.getValue();
                sb.append(text == null ? source.source : text);
                if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                sb.append('\n');
            }
            for (CrossFileNewSource source : result.newFilesByPath.values()) {
                if (source == null) continue;
                sb.append("===== NEW FILE: ").append(source.relativePath).append(" =====\n");
                sb.append(source.source == null ? "" : source.source);
                if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    static String buildCrossFileBeforeDiffBundle(List<CrossFileSource> sources, CrossFileRefactorResult result) {
        StringBuilder sb = new StringBuilder(buildCrossFileDiffBundle(sources, Map.of()));
        if (result != null) {
            java.util.LinkedHashSet<String> listedExistingPaths = new java.util.LinkedHashSet<>();
            if (sources != null) {
                for (CrossFileSource source : sources) {
                    if (source != null) listedExistingPaths.add(source.relativePath);
                }
            }
            for (CrossFileSource source : result.newSourcesByFile.keySet()) {
                if (source == null || listedExistingPaths.contains(source.relativePath)) continue;
                sb.append("===== EXTRA FILE: ").append(source.relativePath).append(" =====\n");
                sb.append(source.source == null ? "" : source.source);
                if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                sb.append('\n');
            }
            for (CrossFileNewSource source : result.newFilesByPath.values()) {
                if (source == null) continue;
                sb.append("===== NEW FILE: ").append(source.relativePath).append(" =====\n");
                sb.append("(new file)\n\n");
            }
        }
        return sb.toString();
    }

    static void writeCrossFileChanges(Project project, CrossFileRefactorResult result) throws IOException {
        if (result == null || !result.hasChanges()) return;
        IOException[] writeError = new IOException[1];
        Runnable writeAction = () -> ApplicationManager.getApplication().runWriteAction(() -> {
            for (Map.Entry<CrossFileSource, String> entry : result.newSourcesByFile.entrySet()) {
                if (entry == null || entry.getKey() == null) continue;
                CrossFileSource source = entry.getKey();
                String newSource = entry.getValue() == null ? "" : entry.getValue();
                try {
                    Document doc = source.vf == null ? null : FileDocumentManager.getInstance().getDocument(source.vf);
                    if (doc != null) {
                        doc.setText(newSource);
                        FileDocumentManager.getInstance().saveDocument(doc);
                    } else {
                        Files.writeString(source.ioFile.toPath(), newSource, StandardCharsets.UTF_8);
                    }
                    if (source.vf != null) {
                        source.vf.refresh(false, false);
                    }
                } catch (IOException e) {
                    writeError[0] = e;
                    return;
                }
            }
            for (CrossFileNewSource source : result.newFilesByPath.values()) {
                if (source == null || source.ioFile == null) continue;
                try {
                    File parent = source.ioFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        Files.createDirectories(parent.toPath());
                    }
                    Files.writeString(source.ioFile.toPath(), source.source, StandardCharsets.UTF_8);
                    if (project != null && project.getBaseDir() != null) {
                        project.getBaseDir().refresh(false, true);
                    }
                } catch (IOException e) {
                    writeError[0] = e;
                    return;
                }
            }
        });

        if (ApplicationManager.getApplication().isDispatchThread()) {
            writeAction.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(writeAction);
        }
        if (writeError[0] != null) {
            throw writeError[0];
        }
    }
}
