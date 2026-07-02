package org.jetbrains.research.anticopypaster.workflow;

import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.extractJsonObjectSubstring;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.getJsonArray;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.getJsonInt;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.getJsonString;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.stripOptionalJavaFence;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.closeQuietly;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.logStage;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.openLogWriter;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.openViewer;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.showDiffAndConfirmApply;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.showNotification;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.teeViewer;

import com.github.copilot.CopilotClient;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.GetAuthStatusResponse;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.SessionConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jetbrains.research.anticopypaster.Copilot.CopilotPromptBuilder;
import org.jetbrains.research.anticopypaster.agents.PsiFallbackCloneDetector;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.statistics.CloneUsageStatistics;

public final class CopilotSdkRefactorWorkflow {
    private static final int COPILOT_TIMEOUT_SECONDS = 360;

    private CopilotSdkRefactorWorkflow() {
    }

    public static void run(Project project, PsiFile psiFile, String pastedText, List<VirtualFile> targets) {
        if (project == null || project.isDisposed()) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() ->
                runInBackground(project, psiFile, pastedText, targets)
        );
    }

    private static void runInBackground(Project project, PsiFile psiFile, String pastedText, List<VirtualFile> targets) {
        BufferedWriter logWriter = null;
        try {
            VirtualFile currentFile = psiFile == null ? null : psiFile.getVirtualFile();
            List<VirtualFile> stableTargets = normalizeTargets(currentFile, targets);
            if (stableTargets.isEmpty()) {
                showNotification(project, "[Copilot] No Java files selected for SDK refactoring.", NotificationType.WARNING);
                return;
            }

            String displayName = currentFile == null ? "copilot-sdk" : currentFile.getName();
            logWriter = openLogWriter(project, displayName, "copilot-sdk");
            Consumer<String> viewer = teeViewer(
                    openViewer(project, "Copilot SDK Output", null, () -> false),
                    logWriter
            );

            logStage(viewer, "COPILOT", "preparing SDK refactor context");
            List<SourceContext> sources = collectSources(project, stableTargets);
            List<CopilotPromptBuilder.CloneHint> hints = collectSameFileHints(project, currentFile, pastedText);
            String prompt = CopilotPromptBuilder.buildSdkJsonRefactorPrompt(
                    pastedText,
                    toPromptFileContexts(sources),
                    hints
            );

            logStage(viewer, "COPILOT", "calling Copilot SDK (files=" + sources.size() + ", promptChars=" + prompt.length() + ")");
            String cliPath = resolveCopilotCliPath(project);
            ensureCopilotCliAvailable(cliPath);
            String raw = callCopilotSdk(project, prompt);
            logStage(viewer, "COPILOT_RAW", preview(raw, 6000));

            CopilotResult result = parseCopilotResult(raw, sources);
            if (result == null) {
                showNotification(project, "[Copilot] Could not parse Copilot SDK JSON response. See Copilot SDK Output.", NotificationType.ERROR);
                return;
            }

            logStage(viewer, "COPILOT", "status=" + result.status + ", changes=" + result.changedSources.size());
            if (!"CLONES_FOUND".equalsIgnoreCase(result.status) || result.changedSources.isEmpty()) {
                String summary = result.summary == null || result.summary.isBlank()
                        ? "No safe Copilot refactor was proposed."
                        : result.summary;
                showNotification(project, "[Copilot] " + summary, NotificationType.INFORMATION);
                return;
            }

            logStage(viewer, "COPILOT", "showing diff without compile/test gate");
            String beforeBundle = buildDiffBundle(sources, Map.of());
            String afterBundle = buildDiffBundle(sources, result.changedSources);
            boolean apply = showDiffAndConfirmApply(
                    project,
                    result.changedSources.size() == 1 ? result.changedSources.keySet().iterator().next().fileName() : "Copilot SDK",
                    beforeBundle,
                    afterBundle
            );
            if (!apply) {
                CloneUsageStatistics.getInstance(project).refactoringCancelled();
                logStage(viewer, "COPILOT", "diff shown but not applied (user cancelled)");
                return;
            }

            writeChanges(project, result.changedSources);
            CloneUsageStatistics.getInstance(project).refactoringAccepted();
            showNotification(project, "[Copilot] SDK refactor applied.", NotificationType.INFORMATION);
            logStage(viewer, "COPILOT", "applied");
        } catch (Throwable t) {
            String message = rootMessage(t);
            showNotification(project, "[Copilot] SDK refactor failed: " + message, NotificationType.ERROR);
        } finally {
            closeQuietly(logWriter);
        }
    }

    private static String callCopilotSdk(Project project, String prompt) throws Exception {
        CopilotClientOptions options = new CopilotClientOptions()
                .setCliPath(resolveCopilotCliPath(project))
                .setUseLoggedInUser(true);
        try (CopilotClient client = new CopilotClient(options)) {
            client.start().get(60, TimeUnit.SECONDS);
            ensureCopilotAuthenticated(client);
            String basePath = project.getBasePath();
            SessionConfig config = new SessionConfig()
                    .setClientName("AntiCopyPaster")
                    .setModel("auto")
                    .setStreaming(false)
                    .setOnPermissionRequest(PermissionHandler.APPROVE_ALL)
                    .setEnableHostGitOperations(false)
                    .setEnableSessionTelemetry(false);
            if (basePath != null && !basePath.isBlank()) {
                config.setWorkingDirectory(basePath);
            }

            try (var session = client.createSession(config).get(60, TimeUnit.SECONDS)) {
                AssistantMessageEvent event = session.sendAndWait(
                        new MessageOptions().setPrompt(prompt)
                ).get(COPILOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (event == null || event.getData() == null || event.getData().content() == null) {
                    return "";
                }
                return event.getData().content();
            } finally {
                try {
                    client.stop().get(20, TimeUnit.SECONDS);
                } catch (Throwable ignored) {
                    client.forceStop();
                }
            }
        }
    }

    private static void ensureCopilotAuthenticated(CopilotClient client) throws Exception {
        GetAuthStatusResponse auth = client.getAuthStatus().get(20, TimeUnit.SECONDS);
        if (auth != null && auth.isAuthenticated()) {
            return;
        }
        String status = auth == null ? "" : auth.getStatusMessage();
        String suffix = status == null || status.isBlank() ? "" : " Status: " + status;
        throw new IOException("GitHub Copilot CLI is installed but not authenticated. Run `copilot` in a terminal and finish login, or set COPILOT_GITHUB_TOKEN/GH_TOKEN/GITHUB_TOKEN before launching the IDE." + suffix);
    }

    private static void ensureCopilotCliAvailable(String cliPath) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder(cliPath, "--version")
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new IOException("GitHub Copilot CLI was not found at `" + cliPath + "`. Install and authenticate the Copilot CLI before using the SDK workflow.", e);
        }

        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("GitHub Copilot CLI did not respond to `" + cliPath + " --version` within 10 seconds.");
        }
        if (process.exitValue() != 0) {
            String output = "";
            try {
                output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
            }
            throw new IOException("GitHub Copilot CLI is not ready. `" + cliPath + " --version` exited with code "
                    + process.exitValue()
                    + (output.isBlank() ? "" : ": " + preview(output, 1000)));
        }
    }

    public static String resolveCopilotCliPath(Project project) {
        if (project != null && !project.isDisposed()) {
            try {
                ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
                String configured = settings == null ? null : settings.getCopilotCliPath();
                if (configured != null && !configured.isBlank()) {
                    return configured.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        String property = System.getProperty("anticopypaster.copilot.cli");
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String env = System.getenv("ACP_COPILOT_CLI_PATH");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return "copilot";
    }

    private static CopilotResult parseCopilotResult(String raw, List<SourceContext> sources) {
        String json = extractJsonObjectSubstring(raw);
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        CopilotResult result = new CopilotResult();
        result.status = getJsonString(obj, "status");
        result.summary = getJsonString(obj, "summary");

        Map<String, SourceContext> index = buildSourceIndex(sources);
        JsonArray files = getJsonArray(obj, "files", "changed_files", "changes");
        if (files != null) {
            for (JsonElement element : files) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject fileObj = element.getAsJsonObject();
                String path = getJsonString(fileObj, "path", "file", "relativePath");
                String fullSource = getJsonString(fileObj, "full_source", "source", "content");
                SourceContext source = resolveSource(index, path);
                if (source == null || fullSource == null || fullSource.isBlank()) {
                    continue;
                }
                fullSource = stripOptionalJavaFence(fullSource);
                if (!source.source.equals(fullSource)) {
                    result.changedSources.put(source, fullSource);
                }
            }
        }

        JsonArray evidence = getJsonArray(obj, "evidence");
        if (evidence != null) {
            for (JsonElement element : evidence) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject ev = element.getAsJsonObject();
                String path = getJsonString(ev, "path", "file");
                int start = getJsonInt(ev, -1, "startLine", "start_line");
                int end = getJsonInt(ev, -1, "endLine", "end_line");
                if (path != null && !path.isBlank() && start > 0 && end >= start) {
                    result.evidence.add(path + ":" + start + "-" + end);
                }
            }
        }
        return result;
    }

    private static List<SourceContext> collectSources(Project project, List<VirtualFile> targets) {
        ArrayList<SourceContext> out = new ArrayList<>();
        for (VirtualFile vf : targets) {
            if (vf == null || !vf.isValid() || vf.isDirectory() || !vf.getName().endsWith(".java")) {
                continue;
            }
            try {
                String text = readCurrentSource(vf);
                out.add(new SourceContext(vf, new File(vf.getPath()), toProjectRelative(project, vf.getPath()), text));
                logStage("COPILOT", "working file: " + toProjectRelative(project, vf.getPath()));
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    private static String readCurrentSource(VirtualFile vf) throws IOException {
        try {
            String documentText = ReadAction.compute(() -> {
                Document doc = FileDocumentManager.getInstance().getDocument(vf);
                return doc == null ? null : doc.getText();
            });
            if (documentText != null) return documentText;
        } catch (Throwable ignored) {
        }
        return Files.readString(new File(vf.getPath()).toPath(), StandardCharsets.UTF_8);
    }

    private static List<CopilotPromptBuilder.FileContext> toPromptFileContexts(List<SourceContext> sources) {
        ArrayList<CopilotPromptBuilder.FileContext> out = new ArrayList<>();
        for (SourceContext source : sources) {
            out.add(new CopilotPromptBuilder.FileContext(source.relativePath, source.source));
        }
        return out;
    }

    private static List<CopilotPromptBuilder.CloneHint> collectSameFileHints(Project project,
                                                                             VirtualFile currentFile,
                                                                             String pastedText) {
        if (project == null || currentFile == null || pastedText == null || pastedText.isBlank()) {
            return List.of();
        }
        try {
            List<PsiFallbackCloneDetector.CloneCandidate> candidates =
                    PsiFallbackCloneDetector.detectInSameFile(project, currentFile, pastedText);
            ArrayList<CopilotPromptBuilder.CloneHint> hints = new ArrayList<>();
            String path = toProjectRelative(project, currentFile.getPath());
            for (PsiFallbackCloneDetector.CloneCandidate candidate : candidates) {
                if (candidate == null || candidate.startLine <= 0 || candidate.endLine <= 0) {
                    continue;
                }
                hints.add(new CopilotPromptBuilder.CloneHint(
                        path,
                        candidate.startLine,
                        candidate.endLine,
                        candidate.cloneCode
                ));
            }
            return hints;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static List<VirtualFile> normalizeTargets(VirtualFile currentFile, List<VirtualFile> targets) {
        LinkedHashMap<String, VirtualFile> byPath = new LinkedHashMap<>();
        addTarget(byPath, currentFile);
        if (targets != null) {
            for (VirtualFile target : targets) {
                addTarget(byPath, target);
            }
        }
        return new ArrayList<>(byPath.values());
    }

    private static void addTarget(Map<String, VirtualFile> byPath, VirtualFile file) {
        if (file == null || !file.isValid() || file.isDirectory()) return;
        String path = file.getPath();
        if (path == null || path.isBlank() || !path.endsWith(".java")) return;
        byPath.putIfAbsent(path, file);
    }

    private static Map<String, SourceContext> buildSourceIndex(List<SourceContext> sources) {
        LinkedHashMap<String, SourceContext> index = new LinkedHashMap<>();
        for (SourceContext source : sources) {
            index.putIfAbsent(normalizePath(source.relativePath), source);
            index.putIfAbsent(normalizePath(source.ioFile.getAbsolutePath()), source);
            index.putIfAbsent(source.fileName(), source);
        }
        return index;
    }

    private static SourceContext resolveSource(Map<String, SourceContext> index, String path) {
        if (path == null || path.isBlank()) return null;
        SourceContext source = index.get(normalizePath(path));
        if (source != null) return source;
        return index.get(new File(path).getName());
    }

    private static String buildDiffBundle(List<SourceContext> sources, Map<SourceContext, String> overrides) {
        StringBuilder sb = new StringBuilder();
        for (SourceContext source : sources) {
            sb.append("===== FILE: ").append(source.relativePath).append(" =====\n");
            String text = overrides == null ? null : overrides.get(source);
            sb.append(text == null ? source.source : text);
            if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void writeChanges(Project project, Map<SourceContext, String> changedSources) throws IOException {
        IOException[] writeError = new IOException[1];
        Runnable write = () -> ApplicationManager.getApplication().runWriteAction(() -> {
            for (Map.Entry<SourceContext, String> entry : changedSources.entrySet()) {
                SourceContext source = entry.getKey();
                String newSource = entry.getValue() == null ? "" : entry.getValue();
                try {
                    Document doc = FileDocumentManager.getInstance().getDocument(source.vf);
                    if (doc != null) {
                        doc.setText(newSource);
                        FileDocumentManager.getInstance().saveDocument(doc);
                    } else {
                        Files.writeString(source.ioFile.toPath(), newSource, StandardCharsets.UTF_8);
                    }
                    source.vf.refresh(false, false);
                } catch (IOException e) {
                    writeError[0] = e;
                    return;
                }
            }
        });
        if (ApplicationManager.getApplication().isDispatchThread()) {
            write.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(write);
        }
        if (writeError[0] != null) {
            throw writeError[0];
        }
    }

    private static String toProjectRelative(Project project, String absolutePath) {
        if (project == null || absolutePath == null) return absolutePath == null ? "" : absolutePath;
        String base = project.getBasePath();
        if (base == null || base.isBlank()) return absolutePath;
        String normalizedBase = normalizePath(base);
        String normalizedPath = normalizePath(absolutePath);
        if (normalizedPath.startsWith(normalizedBase)) {
            String rel = normalizedPath.substring(normalizedBase.length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            return rel.isBlank() ? absolutePath : rel;
        }
        return absolutePath;
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static String preview(String value, int maxChars) {
        if (value == null) return "";
        if (value.length() <= maxChars) return value;
        return value.substring(0, maxChars) + "\n...<truncated>...";
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        String firstMessage = null;
        while (cur != null && cur.getCause() != null) {
            String message = cur.getMessage();
            if (firstMessage == null && message != null && !message.isBlank()) {
                firstMessage = message;
            }
            if (message != null && message.contains("GitHub Copilot CLI")) {
                return message;
            }
            cur = cur.getCause();
        }
        String message = cur == null ? null : cur.getMessage();
        if ((message == null || message.isBlank()) && firstMessage != null) {
            message = firstMessage;
        }
        if (message == null || message.isBlank()) {
            message = t == null ? "unknown error" : t.getClass().getSimpleName();
        }
        return message;
    }

    private record SourceContext(VirtualFile vf, File ioFile, String relativePath, String source) {
        private String fileName() {
            return ioFile == null ? relativePath : ioFile.getName();
        }
    }

    private static final class CopilotResult {
        String status = "";
        String summary = "";
        final List<String> evidence = new ArrayList<>();
        final LinkedHashMap<SourceContext, String> changedSources = new LinkedHashMap<>();

        Map<File, String> toFileSourceMap() {
            LinkedHashMap<File, String> out = new LinkedHashMap<>();
            for (Map.Entry<SourceContext, String> entry : changedSources.entrySet()) {
                out.put(entry.getKey().ioFile, entry.getValue());
            }
            return out;
        }
    }
}
