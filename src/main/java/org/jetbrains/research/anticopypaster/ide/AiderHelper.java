package org.jetbrains.research.anticopypaster.ide;

import org.jetbrains.research.anticopypaster.agents.compile;
import org.jetbrains.research.anticopypaster.agents.detection;
import org.jetbrains.research.anticopypaster.agents.refactor;
import org.jetbrains.research.anticopypaster.agents.testing;
import java.util.function.Function;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.RegisterToolWindowTask;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import java.util.function.Consumer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class AiderHelper {

    // Change this to your CSV location. Supports either a classpath resource (e.g., "clone_database.csv" or "db/clone_database.csv")
    // or a filesystem path (absolute or relative to the project root), e.g., "resources/clone_database.csv". Expected headers include code1/code_1, code2/code_2, and output|response|label.
    private static final String CLONE_DB_PATH = "resources/combined_clone_database_cleaned.csv";
    // Refactoring few-shot database (before/after pairs). Same loading rules as CLONE_DB_PATH.
    private static final String REFACTOR_DB_PATH = "resources/refactor_database.csv";
    // ---- Dual-channel retrieval (sparse + dense via RRF) ----
    private static final boolean ENABLE_DENSE_RETRIEVAL = true;
    private static final String EMBED_PROVIDER = "HF";
    private static final String EMBED_MODEL = "microsoft/codebert-base";
    private static final int DENSE_CANDIDATES_LIMIT = 64;
    private static final int RRF_K = 60;

    private static final Map<String, ConsoleView> CONSOLE_BY_TITLE = new ConcurrentHashMap<>();

    /**
     * Opens or reuses a console tab for streaming output and returns a line consumer that appends on the EDT.
     * Ensures a tool window exists, reuses a same‑titled tab if present, and appends a newline when missing.
     *
     * @param project current IntelliJ project (used to resolve ToolWindow and threading helpers)
     * @param title   console tab title; reused to de‑duplicate tabs
     * @return a thread‑safe line consumer that streams to the console
     */
    public static Consumer<String> openStreamingViewer(Project project, String title) {
        final java.util.concurrent.atomic.AtomicReference<ConsoleView> consoleRef = new java.util.concurrent.atomic.AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            // Ensure tool window exists (Run preferred, else custom)
            ToolWindowManager twm = ToolWindowManager.getInstance(project);
            ToolWindow toolWindow = twm.getToolWindow(ToolWindowId.RUN);
            if (toolWindow == null) {
                toolWindow = twm.getToolWindow("Aider Output");
                if (toolWindow == null) {
                    toolWindow = twm.registerToolWindow(RegisterToolWindowTask.notClosable("Aider Output"));
                }
            }

            com.intellij.ui.content.ContentManager cm = toolWindow.getContentManager();

            // Try to reuse existing console for this title
            ConsoleView console = CONSOLE_BY_TITLE.get(title);
            Content content = cm.findContent(title);

            if (console == null || content == null) {
                // Create new console + content if missing
                console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
                Content newContent = ContentFactory.getInstance().createContent(console.getComponent(), title, true);

                // If a stale tab with the same title exists, remove it first
                if (content != null) {
                    cm.removeContent(content, true);
                }
                cm.addContent(newContent);
                cm.setSelectedContent(newContent);
                CONSOLE_BY_TITLE.put(title, console);
            } else {
                // Reuse tab and clear its console output
                console.clear();
                cm.setSelectedContent(content);
            }

            toolWindow.activate(null);

            consoleRef.set(CONSOLE_BY_TITLE.get(title));
        });

        // Writer that prints to the console on EDT
        return line -> ApplicationManager.getApplication().invokeLater(() -> {
            ConsoleView console = consoleRef.get();
            if (console != null) {
                console.print(line, ConsoleViewContentType.NORMAL_OUTPUT);
                if (!line.endsWith("\n")) {
                    console.print("\n", ConsoleViewContentType.NORMAL_OUTPUT);
                }
            }
        });
    }

    /**
     * Runs a quick Aider‑based clone check for the file and, if clones are indicated, asks the user whether to refactor.
     * Copies the file to a temp path, streams detector output, and prompts for a refactor preview on positive signals.
     *
     * @param project    the IntelliJ project
     * @param file       file to analyze for clones
     * @param provider   model provider identifier (e.g., OpenAI, Gemini, Anthropic, Azure, Deepseek, xAI)
     * @param model      model name (provider‑specific; some are normalized inside)
     * @param apikey     API key to expose via environment variables to the Aider process
     * @param aiderPath  path to the {@code aider} executable
     * @param apiBase    optional API base (used by Azure and some custom deployments)
     * @param apiVersion optional API version (used by Azure)
     */
    public static void checkAndSuggestRefactor(Project project, VirtualFile file, String provider, String model, String apikey, String aiderPath, String apiBase, String apiVersion) {
        String fileName = file.getName();
        notify(project, "Aider is running clone detection on " + fileName + "...");
        String filePath = file.getPath();

        try {
            File originalFile = new File(filePath);
            File tempFile = File.createTempFile("aider_clonecheck_", ".java");
            Files.copy(originalFile.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            String tempFilePath = tempFile.getAbsolutePath();

            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    Consumer<String> viewer = openStreamingViewer(project, "Aider Multi-Agent Workflow");

                    // Read file content once (this is the source the agents reason about)
                    String fileSource = Files.readString(Paths.get(tempFilePath), StandardCharsets.UTF_8);

                    // LLM caller is injected into agents. We keep using Aider as the transport.
                    Function<String, String> llmCaller = (prompt) -> {
                        try {
                            return runAiderWithPromptStreaming(project, aiderPath, tempFilePath,
                                    prompt, provider, model, apikey, apiBase, apiVersion, viewer);
                        } catch (Exception e) {
                            return "[LLM_CALL_EXCEPTION] " + e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
                        }
                    };

                    // 1) Detection Agent
                    detection detAgent = new detection();
                    detection.DetectionResult det = detAgent.detect(fileName, fileSource, "", llmCaller);

                    if (det == null || det.clones == null || det.clones.isEmpty() || "no_clones".equalsIgnoreCase(det.status)) {
                        ApplicationManager.getApplication().invokeLater(() ->
                                notify(project, "No clones detected in " + fileName + ".")
                        );
                        return;
                    }

                    viewer.accept("[DETECTION] status=" + det.status + ", clones=" + det.clones.size());
                    for (detection.DetectedClone c : det.clones) {
                        viewer.accept("  - clone id=" + c.id + ", ranges=" + (c.ranges == null ? 0 : c.ranges.size())
                                + ", refactorType=" + c.refactorType);
                    }

                    // Ask user whether to proceed
                    ApplicationManager.getApplication().invokeLater(() -> {
                        int choice = Messages.showYesNoDialog(
                                project,
                                "Clone(s) detected in " + fileName + ". Do you want to run the multi-agent refactoring workflow (refactor → compile → test)?",
                                "Multi-Agent Refactoring",
                                Messages.getQuestionIcon()
                        );
                        if (choice == Messages.YES) {
                            runMultiAgentRefactorWithPreview(project, fileName, filePath, tempFilePath,
                                    provider, model, apikey, aiderPath, apiBase, apiVersion, det);
                        } else {
                            notify(project, "Multi-agent refactoring canceled for " + fileName + ".");
                        }
                    });

                } catch (Exception e) {
                    notify(project, "Aider Error: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        } catch (IOException e) {
            notify(project, "Aider Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Performs an Extract‑Method style refactor via Aider on a temp copy, shows a side‑by‑side diff, and
     * applies the result to the original file only if the user confirms.
     *
     * @param project    the IntelliJ project
     * @param fileName   display name used in messages
     * @param filePath   absolute path to the source file to refactor
     * @param provider   LLM provider identifier
     * @param model      model name (may be normalized)
     * @param apikey     API key for the provider
     * @param aiderPath  path to the {@code aider} executable
     * @param apiBase    optional API base (e.g., Azure endpoint)
     * @param apiVersion optional API version (for Azure)
     */
    private static void runRefactorWithPreview(Project project, String fileName, String filePath, String provider, String model, String apikey, String aiderPath, String apiBase, String apiVersion) {
        notify(project, "Aider is running code refactoring on " + fileName + "...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                File originalFile = new File(filePath);
                File tempFile = File.createTempFile("aider_refactor_", ".java");
                Files.copy(originalFile.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                Consumer<String> viewer = openStreamingViewer(project, "Aider Refactoring Output");
                // 1) Ask the model to extract two representative clone snippets from this file for retrieval
                String[] cloneSnippets = extractRepresentativeCloneSnippets(project, aiderPath, tempFile.getAbsolutePath(),
                        provider, model, apikey, apiBase, apiVersion, viewer);
                // Heuristic fallback if both A/B are empty
                if ((cloneSnippets == null) ||
                    ((cloneSnippets.length < 2) || (cloneSnippets[0] == null || cloneSnippets[0].isBlank()) && (cloneSnippets[1] == null || cloneSnippets[1].isBlank()))) {
                    // Heuristic fallback: choose two most-similar method bodies by Jaccard over identifiers
                    try {
                        String src = java.nio.file.Files.readString(tempFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                        String[] hs = heuristicClonePairFromSource(src, 60 /*minLenChars*/, 0.18 /*minSim*/);
                        if (hs != null) {
                            cloneSnippets = hs;
                            viewer.accept("[RAG] Heuristic fallback used for clone snippets (LLM extraction empty).");
                        }
                    } catch (Exception ignore) {}
                }
                // Log snippet sizes
                viewer.accept("[RAG] clone snippets extracted: A="
                        + ((cloneSnippets != null && cloneSnippets.length > 0 && cloneSnippets[0] != null) ? cloneSnippets[0].length() : 0)
                        + " chars, B="
                        + ((cloneSnippets != null && cloneSnippets.length > 1 && cloneSnippets[1] != null) ? cloneSnippets[1].length() : 0)
                        + " chars");
                // (Optional helpful) Show pool size for loaded refactor examples (including empty 'after')
                java.util.List<RefactorExample> _poolPreview = loadRefactorExamples(REFACTOR_DB_PATH, project, 700);
                viewer.accept("[RAG] Refactor examples loaded (including empty 'after'): " + _poolPreview.size() + " from " + REFACTOR_DB_PATH);
                // 2) Build refactor prompt with few-shot (BM25-like overlap scoring against REFACTOR_DB_PATH)
                String refactorPrompt = buildRefactorPromptWithFewShot(project, cloneSnippets, 2, 700);
                viewer.accept("[RAG] Fusion: sparse + dense (RRF k=" + RRF_K + "), provider=" + EMBED_PROVIDER
                        + ", model=" + EMBED_MODEL + ", denseEnabled=" + ENABLE_DENSE_RETRIEVAL);
                // Preview the prompt for debugging
                viewer.accept("---- Refactor Prompt (preview) ----");
                viewer.accept(refactorPrompt);
                viewer.accept("---- End Prompt ----");
                // 3) Run aider with the RAG prompt
                String output = runAiderWithPromptStreaming(project, aiderPath, tempFile.getAbsolutePath(),
                        refactorPrompt,
                        provider, model, apikey, apiBase, apiVersion, viewer);
                System.out.println("===> Refactor output:\n" + output);

                if (output != null) {
                    String fenced = extractJavaCodeBlock(output);
                    if (fenced != null && !fenced.isBlank()) {
                        try {
                            Files.writeString(tempFile.toPath(), fenced, StandardCharsets.UTF_8);
                        } catch (IOException ioe) {
                            notify(project, "Failed to write refactored content to temp file: " + ioe.getMessage());
                        }
                    }
                }

                String originalContent = Files.readString(originalFile.toPath());
                String refactoredContent = Files.readString(tempFile.toPath());

                if (!originalContent.equals(refactoredContent)) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        // Show diff window
                        DiffContentFactory contentFactory = DiffContentFactory.getInstance();
                        SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                                "Refactor Preview: Compare Original and Refactored Code",
                                contentFactory.create(originalContent),
                                contentFactory.create(refactoredContent),
                                "Original",
                                "Refactored"
                        );
                        DiffManager.getInstance().showDiff(project, diffRequest);
                    });

                    // Delay confirmation dialog to allow scroll/preview time
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ignored) {
                        }

                        ApplicationManager.getApplication().invokeLater(() -> {
                            int choice = Messages.showYesNoDialog(
                                    project,
                                    "Do you want to apply the refactored code to " + fileName + "?",
                                    "Apply Refactoring",
                                    Messages.getQuestionIcon()
                            );

                            if (choice == Messages.YES) {
                                try {
                                    Files.write(originalFile.toPath(), refactoredContent.getBytes(StandardCharsets.UTF_8));
                                    notify(project, "File " + fileName + " has been updated with refactored version.");
                                } catch (IOException e) {
                                    notify(project, "Failed to overwrite file " + fileName + ": " + e.getMessage());
                                }
                            } else {
                                notify(project, "Refactoring for file " + fileName + "was canceled.");
                            }
                        }, ModalityState.NON_MODAL);
                    });
                } else {
                    notify(project, "No changes in refactored code for file " + fileName + ".");
                }

            } catch (Exception e) {
                notify(project, "Refactor failed for file " + fileName + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Heuristically checks whether Aider's output indicates that clones were found.
     * It requires the phrase "clones found" and rejects "no clones found".
     *
     * @param output raw stdout collected from the Aider process
     * @return {@code true} if the output suggests clones were found; {@code false} otherwise
     */
    private static boolean containsDuplicateHint(String output) {
        String normalized = output.toLowerCase().trim();
        return normalized.contains("clones found") && !normalized.contains("no clones found");
    }

    /**
     * Runs Aider once with the given prompt and file, returning its full (cleaned) stdout.
     * This non‑streaming variant normalizes certain provider/model names (e.g., deepseek/azure).
     *
     * @param project    current project (used for working directory and notifications)
     * @param aiderPath  path to {@code aider}
     * @param filePath   path to a file to include in the Aider context
     * @param prompt     message to send to the model
     * @param provider   provider identifier (OpenAI/Gemini/Anthropic/DeepSeek/Azure/xAI)
     * @param model      model name; may be prefixed for provider as needed
     * @param apikey     API key exposed to the subprocess
     * @param apiBase    optional API base (provider specific)
     * @param apiVersion optional API version (provider specific)
     * @return combined standard output of the Aider subprocess
     * @throws IOException          if launching the process fails
     * @throws InterruptedException if the process is interrupted while waiting
     */
    public static String runAiderWithPrompt(Project project, String aiderPath, String filePath, String prompt,
                                            String provider, String model, String apikey,
                                            String apiBase, String apiVersion)
            throws IOException, InterruptedException {
        if (model.startsWith("deepseek-")) {
            model = "deepseek/" + model;
        }
        if (provider.equals("Azure")) {
            model = "azure/" + model;
        }
        return runCommand(project, provider,
                apikey,
                apiBase,
                apiVersion,
                aiderPath,
                "--model", model,
                "--yes",
                "--message", prompt,
                filePath
        );
    }

    /**
     * Runs Aider in streaming mode, forwarding cleaned stdout lines to the given viewer and returning the full output.
     * Also normalizes provider/model identifiers (e.g., deepseek/, azure/, xai/).
     *
     * @param project    current project (working directory and notifications)
     * @param aiderPath  path to {@code aider}
     * @param filePath   path to the file to include in context
     * @param prompt     instruction to send
     * @param provider   provider identifier
     * @param model      model name (may be normalized with a provider prefix)
     * @param apikey     API key for the provider
     * @param apiBase    optional API base
     * @param apiVersion optional API version
     * @param viewer     consumer that receives each cleaned line as it arrives; may be {@code null}
     * @return the full combined output captured from the Aider subprocess
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the process is interrupted
     */
    public static String runAiderWithPromptStreaming(Project project, String aiderPath, String filePath, String prompt,
                                                     String provider, String model, String apikey,
                                                     String apiBase, String apiVersion, Consumer<String> viewer)
            throws IOException, InterruptedException {
        if (provider.equals("DeepSeek")) {
            model = "deepseek/" + model;
        }
        if (provider.equals("Azure")) {
            model = "azure/" + model;
        }
        if (provider.equals("xAI")) {
            model = "xai/" + model;
        }

        if (provider.equals("Ollama")) {
            model = "ollama_chat/" + model;
        }
        return runCommand(project, provider,
                apikey,
                apiBase,
                apiVersion,
                viewer,
                aiderPath,
                "--model", model,
                "--yes",
                "--message", prompt,
                filePath
        );
    }

    /**
     * Convenience overload of {@link #runCommand(Project, String, String, String, String, java.util.function.Consumer, String...)}
     * with no streaming viewer. Executes the given command and returns combined stdout.
     */
    private static String runCommand(Project project, String provider, String apikey, String apiBase,
                                     String apiVersion, String... command)
            throws IOException, InterruptedException {
        return runCommand(project, provider, apikey, apiBase, apiVersion, null, command);
    }

    /**
     * Executes an external process with provider‑specific environment variables, cleans and optionally streams stdout,
     * and returns the combined output as a string. Sets the project root as the working directory and filters noisy lines.
     *
     * @param project    IntelliJ project (used for working directory and notifications)
     * @param provider   provider identifier (case‑insensitive)
     * @param apikey     API key to export in the environment
     * @param apiBase    optional API base (Azure)
     * @param apiVersion optional API version (Azure)
     * @param viewer     optional streaming sink for cleaned lines (may be {@code null})
     * @param command    full command array to run (executable plus args)
     * @return combined stdout from the process
     * @throws IOException          if process start fails
     * @throws InterruptedException if waiting on the process is interrupted
     * @throws RuntimeException     if the process exits non‑zero
     */
    private static String runCommand(Project project, String provider, String apikey, String apiBase,
                                     String apiVersion, Consumer<String> viewer, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        // Ensure Aider runs in a writable working directory (project root) so it can write .aider.input.history
        String basePath = project.getBasePath();
        if (basePath != null && !basePath.isEmpty()) {
            pb.directory(new File(basePath));
        }
        switch (provider.toUpperCase()) {
            case "OPENAI" -> pb.environment().put("OPENAI_API_KEY", apikey);
            case "GEMINI" -> {
                pb.environment().put("GEMINI_API_KEY", apikey);
                pb.environment().put("AIDER_GEMINI_PROVIDER", "google-ai-studio");
            }
            case "ANTHROPIC" -> pb.environment().put("ANTHROPIC_API_KEY", apikey);
            case "DEEPSEEK" -> pb.environment().put("DEEPSEEK_API_KEY", apikey);
            case "OLLAMA" -> {
                pb.environment().put("OLLAMA_API_BASE", apiBase);
            }
            case "AZURE" -> {
                pb.environment().put("AZURE_API_KEY", apikey);
                pb.environment().put("AZURE_API_VERSION", apiVersion);
                pb.environment().put("AZURE_API_BASE", apiBase);
            }
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        }
        // Hint many CLIs to avoid ANSI color output in non-TTY environments
        pb.environment().put("NO_COLOR", "1");

        Process process = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            // Filter specific known noisy warnings
            if (line.contains("initialize prompt toolkit") || line.contains("cmd.exe")) {
                continue;
            }
            // Strip ANSI codes and non-printable control characters to avoid garbled output
            String cleaned = stripNonPrintable(stripAnsi(line));
            if (cleaned == null || cleaned.trim().isEmpty()) {
                continue;
            }
            if (viewer != null) {
                viewer.accept(cleaned);
            }
            System.out.println("[AIDER] " + cleaned);
            output.append(cleaned).append("\n");
        }

        String lowerOutput = output.toString().toLowerCase();
        if (lowerOutput.contains("token limit") && (lowerOutput.contains("exceed") || lowerOutput.contains("exceeded") || lowerOutput.contains("exceeds"))) {
            notify(project, "Warning: Your request exceeded the model's token limit. Please reduce the file size.");
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", command));
        }

        return output.toString();
    }

    /**
     * Shows a simple informational notification under the "Aider Refactoring" group.
     *
     * @param project IntelliJ project used as the notification context
     * @param content message body to display
     */
    private static void notify(Project project, String content) {
        Notification notification = new Notification(
                "AiderRefactor",
                "Aider Refactoring",
                content,
                NotificationType.INFORMATION
        );
        Notifications.Bus.notify(notification, project);
    }

    /**
     * Closes a single streaming console tab identified by its title, if present. Safe to call from any thread.
     * Disposes tracked consoles and hides the tool window if it becomes empty.
     *
     * @param project IntelliJ project
     * @param title   console tab title to close
     */
    public static void closeViewerByTitle(Project project, String title) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                ToolWindowManager twm = ToolWindowManager.getInstance(project);
                ToolWindow toolWindow = twm.getToolWindow(ToolWindowId.RUN);
                if (toolWindow == null) {
                    toolWindow = twm.getToolWindow("Aider Output");
                }
                if (toolWindow == null) {
                    return; // Nothing to close
                }

                com.intellij.ui.content.ContentManager cm = toolWindow.getContentManager();
                com.intellij.ui.content.Content content = cm.findContent(title);

                ConsoleView console = CONSOLE_BY_TITLE.remove(title);

                if (content != null) {
                    cm.removeContent(content, true);
                }
                if (console != null) {
                    console.clear();
                    if (console instanceof ConsoleViewImpl cvi) {
                        cvi.dispose();
                    }
                }

                if (cm.getContentCount() == 0) {
                    toolWindow.hide(null);
                }
            } catch (Throwable t) {
                System.err.println("Failed to close viewer '" + title + "': " + t.getMessage());
            }
        });
    }

    /**
     * Closes all Aider‑related viewer tabs.
     * First closes any consoles tracked internally, then sweeps the Run/Aider Output tool windows
     * to remove any tabs whose title starts with "Aider ".
     *
     * @param project IntelliJ project
     */
    public static void closeAllViewers(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                // First close everything we explicitly tracked
                java.util.List<String> titles = new java.util.ArrayList<>(CONSOLE_BY_TITLE.keySet());
                for (String title : titles) {
                    closeViewerByTitle(project, title);
                }

                // Then sweep tool windows to catch any stray tabs not tracked in the map
                ToolWindowManager twm = ToolWindowManager.getInstance(project);
                ToolWindow[] tws = new ToolWindow[] {
                        twm.getToolWindow(ToolWindowId.RUN),
                        twm.getToolWindow("Aider Output")
                };
                for (ToolWindow tw : tws) {
                    if (tw == null) continue;
                    com.intellij.ui.content.ContentManager cm = tw.getContentManager();
                    // Take a snapshot to avoid concurrent modification while removing
                    java.util.List<Content> list = new java.util.ArrayList<>();
                    for (int i = 0; i < cm.getContentCount(); i++) {
                        list.add(cm.getContent(i));
                    }
                    for (Content c : list) {
                        if (c != null) {
                            String title = c.getDisplayName();
                            if (title != null && title.startsWith("Aider ")) {
                                cm.removeContent(c, true);
                                // Clean any console we may have tracked under this title
                                ConsoleView console = CONSOLE_BY_TITLE.remove(title);
                                if (console != null) {
                                    console.clear();
                                    if (console instanceof ConsoleViewImpl cvi) {
                                        cvi.dispose();
                                    }
                                }
                            }
                        }
                    }
                    if (cm.getContentCount() == 0) {
                        tw.hide(null);
                    }
                }
            } catch (Throwable t) {
                System.err.println("Failed to close all Aider viewers: " + t.getMessage());
            }
        });
    }

    /**
     * Removes ANSI escape sequences from a line, which prevents garbled output in the UI.
     *
     * @param s input string possibly containing ANSI codes
     * @return the input without ANSI escape sequences (or {@code null} if input is null)
     */
    private static String stripAnsi(String s) {
        if (s == null) return null;
        return s.replaceAll("\u001B\\[[;?0-9]*[ -/]*[@-~]", "");
    }

    /**
     * Filters out non‑printable control characters (except tab/newline/CR) and surrogate code points
     * that tend to appear as broken glyphs in console output.
     *
     * @param s input string
     * @return sanitized string with only printable characters and standard whitespace
     */
    private static String stripNonPrintable(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t' || ch >= 0x20) {
                // Filter out surrogate code points that often appear as garbled remnants
                if (!Character.isSurrogate(ch)) {
                    sb.append(ch);
                }
            }
        }
        return sb.toString();
    }
    /**
     * Extracts the first fenced code block from {@code text} whose language is either empty or starts with "java".
     *
     * @param text full Aider response text, possibly containing fenced code blocks
     * @return the inner content of the first matching fenced block, or {@code null} if none is found
     */
    private static String extractJavaCodeBlock(String text) {
        if (text == null) return null;
        int i = 0;
        while (i < text.length()) {
            int fenceStart = text.indexOf("```", i);
            if (fenceStart == -1) return null;
            int lineEnd = text.indexOf('\n', fenceStart + 3);
            if (lineEnd == -1) return null;
            String lang = text.substring(fenceStart + 3, lineEnd).trim().toLowerCase();
            // accept "java" or empty language tag
            if (lang.isEmpty() || lang.startsWith("java")) {
                int fenceEnd = text.indexOf("```", lineEnd + 1);
                if (fenceEnd == -1) return null;
                return text.substring(lineEnd + 1, fenceEnd).trim();
            }
            i = lineEnd + 1;
        }
        return null;
    }

    /**
     * Ask the LLM to output exactly two short representative clone snippets (A and B) as fenced ```java blocks.
     * Returns an array of length 2 with the code contents; missing ones are empty strings.
     */
    private static String[] extractRepresentativeCloneSnippets(Project project, String aiderPath, String filePath,
                                                               String provider, String model, String apikey,
                                                               String apiBase, String apiVersion, Consumer<String> viewer)
            throws IOException, InterruptedException {
        String instruction =
                "Identify the most prominent duplicated logic (clone pair) in this file. " +
                "Return EXACTLY two fenced code blocks with language 'java' in this order: " +
                "first block is snippet A, second block is snippet B. " +
                "Do NOT include any prose, lists, JSON, or explanations—only the two fenced blocks.";
        String stdout = runAiderWithPrompt(project, aiderPath, filePath, instruction,
                provider, model, apikey, apiBase, apiVersion);
        String[] two = extractFirstTwoJavaBlocks(stdout);
        if (two[0].isEmpty() && two[1].isEmpty()) {
            // Fallback: try a less strict instruction
            String softer =
                    "Find a representative clone pair (two similar code snippets) in this file. " +
                    "Output two fenced ```java blocks only: the first is snippet A, the second is snippet B.";
            stdout = runAiderWithPrompt(project, aiderPath, filePath, softer,
                    provider, model, apikey, apiBase, apiVersion);
            two = extractFirstTwoJavaBlocks(stdout);
        }
        return two;
    }

    /**
     * Extracts the first two ```java fenced blocks from text. Returns array of size 2 (missing entries are "").
     */
    private static String[] extractFirstTwoJavaBlocks(String text) {
        String[] result = new String[] {"", ""};
        if (text == null) return result;
        int i = 0, found = 0;
        while (i < text.length() && found < 2) {
            int fenceStart = text.indexOf("```", i);
            if (fenceStart == -1) break;
            int langEnd = text.indexOf('\n', fenceStart + 3);
            if (langEnd == -1) break;
            String lang = text.substring(fenceStart + 3, langEnd).trim().toLowerCase();
            if (lang.startsWith("java")) {
                int fenceEnd = text.indexOf("```", langEnd + 1);
                if (fenceEnd == -1) break;
                String block = text.substring(langEnd + 1, fenceEnd).trim();
                result[found++] = block;
                i = fenceEnd + 3;
            } else {
                i = langEnd + 1;
            }
        }
        return result;
    }

    // REFACTOR_DB_PATH expected schema (preferred): code_1, code_2, refactor  |  (legacy fallback) before/after
    /**
     * Builds a refactor prompt using few-shot before/after examples retrieved from REFACTOR_DB_PATH.
     * Retrieval uses a simple token-overlap score (BM25-like) between the current clone snippets and candidate 'before' code.
     * Expected refactor database headers (preferred): code_1, code_2, refactor. (Also accepts: code1|code2 for code fields, and refactored_code|after|code_after|dest|target for refactor.)
     * The 'before' text is synthesized as: code_1 + "\n/* --- second snippet --- *\n" + code_2.
     */
    private static String buildRefactorPromptWithFewShot(Project project, String[] cloneSnippets, int k, int maxChars) {
        String a = cloneSnippets != null && cloneSnippets.length > 0 ? cloneSnippets[0] : "";
        String b = cloneSnippets != null && cloneSnippets.length > 1 ? cloneSnippets[1] : "";
        java.util.List<RefactorExample> pool = loadRefactorExamples(REFACTOR_DB_PATH, project, maxChars);
        java.util.List<Scored<RefactorExample>> sparseRank =
                rankByOverlap(a + "\n" + b, pool, Math.min(pool.size(), DENSE_CANDIDATES_LIMIT));
        java.util.List<Scored<RefactorExample>> denseRank =
                ENABLE_DENSE_RETRIEVAL ? rankByEmbedding(a + "\n" + b, takeItems(sparseRank, pool), project)
                        : java.util.Collections.emptyList();
        java.util.List<Scored<RefactorExample>> tops = reciprocalRankFusion(sparseRank, denseRank, k);
        // If retrieval returns nothing (e.g., zero token overlap), fall back to k random examples
        if ((tops == null || tops.isEmpty()) && !pool.isEmpty()) {
            java.util.Collections.shuffle(pool, new java.util.Random());
            tops = new java.util.ArrayList<>();
            for (int i = 0; i < Math.min(k, pool.size()); i++) {
                tops.add(new Scored<>(pool.get(i), 0.0));
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("You are a senior Java engineer. Your task is to refactor the *entire* Java file by Extract Method (or similar clone-removal patterns) to eliminate duplicated logic.\n")
          .append("Hard requirements:\n")
          .append("1) Preserve behavior.\n")
          .append("2) Keep all existing public method signatures exactly the same.\n")
          .append("3) Do NOT remove or omit any existing code that is not directly related to the clones.\n")
          .append("4) Follow standard Java style.\n")
          .append("5) You MUST return the full content of the file, including package declaration, imports, class declaration(s), fields, and all methods.\n")
          .append("6) The answer must consist of exactly one fenced code block tagged as ```java, with no prose before or after.\n")
          .append("7) Do NOT output diffs, patches, SEARCH/REPLACE markers, comments about changes, or partial snippets.\n\n");
        sb.append("[RAG] examples_used=").append((tops == null) ? 0 : tops.size()).append("\n\n");
        if (!a.isBlank() || !b.isBlank()) {
            sb.append("The following two snippets illustrate the duplicate logic found in this file:\n")
              .append("Snippet A:\n```\n").append(safeTruncate(a, maxChars)).append("\n```\n")
              .append("Snippet B:\n```\n").append(safeTruncate(b, maxChars)).append("\n```\n\n");
        }
        if (tops != null && !tops.isEmpty()) {
            sb.append("Here are ").append(tops.size()).append(" reference examples of successful clone-removal refactorings (BEFORE -> AFTER):\n\n");
            int idx = 1;
            for (Scored<RefactorExample> s : tops) {
                RefactorExample ex = s.item;
                sb.append("Example ").append(idx++).append(" (score ").append(String.format(java.util.Locale.US, "%.3f", s.score)).append("):\n");
                sb.append("Before:\n```\n").append(ex.before).append("\n```\n");
                if (ex.after != null && !ex.after.isBlank()) {
                    sb.append("After:\n```\n").append(ex.after).append("\n```\n");
                } else {
                    sb.append("After: (empty)\n");
                }
                if (!ex.rationale.isBlank()) {
                    sb.append("Note: ").append(ex.rationale).append("\n");
                }
                sb.append("\n");
            }
        }
        sb.append("Now return the fully refactored file as ONE single ```java fenced block containing the entire file content (package, imports, class declaration, fields, and all methods), and nothing else.");
        return sb.toString();
    }

    /**
     * Heuristic clone pair from a single Java source: pick two most-similar top-level method bodies
     * using Jaccard over identifier tokens. Returns array [A, B] or null if none.
     */
    private static String[] heuristicClonePairFromSource(String src, int minLenChars, double minSim) {
        if (src == null || src.isBlank()) return null;
        java.util.List<int[]> spans = new java.util.ArrayList<>();
        java.util.regex.Pattern meth = java.util.regex.Pattern.compile("(?m)^\\s*(public|private|protected)?\\s*[\\w<>\\[\\],\\s]+?\\s+[A-Za-z_][A-Za-z_0-9]*\\s*\\([^;{]*\\)\\s*\\{");
        java.util.regex.Matcher m = meth.matcher(src);
        while (m.find()) {
            int bodyStart = src.indexOf('{', m.start());
            if (bodyStart < 0) continue;
            int depth = 1, i = bodyStart;
            while (++i < src.length() && depth > 0) {
                char ch = src.charAt(i);
                if (ch == '{') depth++;
                else if (ch == '}') depth--;
            }
            if (depth == 0) {
                spans.add(new int[]{m.start(), i});
            }
        }
        java.util.List<String> bodies = new java.util.ArrayList<>();
        for (int[] s : spans) {
            String b = src.substring(s[0], s[1]).trim();
            if (b.length() >= minLenChars) bodies.add(b);
        }
        if (bodies.size() < 2) return null;
        java.util.List<java.util.List<String>> toks = new java.util.ArrayList<>();
        java.util.regex.Pattern ident = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");
        for (String b : bodies) {
            java.util.List<String> t = new java.util.ArrayList<>();
            java.util.regex.Matcher im = ident.matcher(b);
            while (im.find()) t.add(im.group().toLowerCase());
            toks.add(t);
        }
        double best = -1.0; int bi = -1, bj = -1;
        for (int i = 0; i < bodies.size(); i++) {
            java.util.Set<String> si = new java.util.HashSet<>(toks.get(i));
            for (int j = i + 1; j < bodies.size(); j++) {
                java.util.Set<String> sj = new java.util.HashSet<>(toks.get(j));
                java.util.Set<String> uni = new java.util.HashSet<>(si); uni.addAll(sj);
                java.util.Set<String> inter = new java.util.HashSet<>(si); inter.retainAll(sj);
                double sim = uni.isEmpty() ? 0.0 : (double) inter.size() / (double) uni.size();
                if (sim > best) { best = sim; bi = i; bj = j; }
            }
        }
        if (best < minSim || bi < 0 || bj < 0) {
            // pick two longest methods as last resort
            bodies.sort((x, y) -> Integer.compare(y.length(), x.length()));
            return new String[]{ bodies.get(0), bodies.get(1) };
        }
        return new String[]{ bodies.get(bi), bodies.get(bj) };
    }

    private static class RefactorExample {
        final String before;
        final String after;
        final String rationale;
        RefactorExample(String b, String a, String r) { this.before = b; this.after = a; this.rationale = r == null ? "" : r; }
    }
    private static class Scored<T> {
        final T item; final double score;
        Scored(T i, double s) { item = i; score = s; }
    }

    /**
     * Loads refactoring before/after examples from a CSV (classpath or filesystem handled by loadFewShotExamplesFrom* helpers).
     * Preferred headers: code_1, code_2, refactor. Also accepts code1|code2 for the code fields, and refactored_code|after|code_after|dest|target for the refactor field. Optional: rationale|note|comment.
     * The loader will synthesize BEFORE = code_1 + "\n/* --- second snippet --- *n" + code_2 and AFTER = refactor.
     * If no (code_1, code_2, refactor) are found, it falls back to the legacy headers before|code_before|src|original|code1 and after|refactored_code|code_after|dest|target.
     */
    private static java.util.List<RefactorExample> loadRefactorExamples(String pathOrResource, Project project, int maxChars) {
        java.util.List<RefactorExample> out = new java.util.ArrayList<>();
        // Try as resources
        try (java.io.InputStream is = AiderHelper.class.getClassLoader().getResourceAsStream(pathOrResource)) {
            if (is != null) {
                java.util.List<RefactorExample> r = readRefactorCsv(new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8)), maxChars);
                if (!r.isEmpty()) return r;
            }
        } catch (Throwable ignored) {}
        // Try tail name as resource
        int slash = pathOrResource.lastIndexOf('/');
        if (slash >= 0) {
            String tail = pathOrResource.substring(slash + 1);
            try (java.io.InputStream is2 = AiderHelper.class.getClassLoader().getResourceAsStream(tail)) {
                if (is2 != null) {
                    java.util.List<RefactorExample> r = readRefactorCsv(new java.io.BufferedReader(new java.io.InputStreamReader(is2, java.nio.charset.StandardCharsets.UTF_8)), maxChars);
                    if (!r.isEmpty()) return r;
                }
            } catch (Throwable ignored) {}
        }
        // Try filesystem
        java.io.File f = new java.io.File(pathOrResource);
        if (!f.isAbsolute()) {
            String base = project != null ? project.getBasePath() : null;
            if (base != null && !base.isBlank()) {
                f = new java.io.File(base, pathOrResource);
            }
        }
        if (f.exists() && f.isFile()) {
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
                return readRefactorCsv(br, maxChars);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private static java.util.List<RefactorExample> readRefactorCsv(java.io.BufferedReader br, int maxChars) throws java.io.IOException {
        java.util.List<RefactorExample> out = new java.util.ArrayList<>();
        String header = readNextCsvRecord(br);
        if (header == null) return out;
        if (header.length() > 0 && header.charAt(0) == '\uFEFF') {
            header = header.substring(1);
        }
        String[] hdr = splitCsvLine(header);
        if (hdr == null || hdr.length == 0) return out;

        // Detect columns (new schema first)
        int idxC1 = -1, idxC2 = -1, idxRef = -1;
        int idxBefore = -1, idxAfter = -1, idxRat = -1;

        for (int i = 0; i < hdr.length; i++) {
            String h = hdr[i].trim().toLowerCase();
            // New triplet schema
            if (h.equals("code_1") || h.equals("code1")) idxC1 = i;
            else if (h.equals("code_2") || h.equals("code2")) idxC2 = i;
            else if (h.equals("refactor") || h.equals("refactoring") || h.equals("refactored_code")
                    || h.equals("after") || h.equals("code_after") || h.equals("dest") || h.equals("target")) {
                idxRef = i;
            }

            // Optional rationale (applies to both schemas)
            if (h.equals("rationale") || h.equals("note") || h.equals("comment")) idxRat = i;

            // Legacy before/after schema
            if (h.equals("before") || h.equals("code_before") || h.equals("src") || h.equals("original") || h.equals("code1")) idxBefore = (idxBefore == -1 ? i : idxBefore);
            if (h.equals("after") || h.equals("refactored_code") || h.equals("code_after") || h.equals("dest") || h.equals("target")) idxAfter = (idxAfter == -1 ? i : idxAfter);
        }

        boolean useTriplet = (idxC1 >= 0 && idxC2 >= 0 && idxRef >= 0);

        String line;
        while ((line = readNextCsvRecord(br)) != null) {
            if (line.isBlank()) continue;
            String[] cols = splitCsvLine(line);

            if (useTriplet) {
                if (cols.length <= Math.max(idxRef, Math.max(idxC1, idxC2))) continue;
                String c1 = safeTruncate(cols[idxC1], maxChars);
                String c2 = safeTruncate(cols[idxC2], maxChars);
                String ref = safeTruncate(cols[idxRef], maxChars);
                String rat = (idxRat >= 0 && idxRat < cols.length) ? cols[idxRat] : "";
                // Require both code snippets; allow empty refactor (we'll still use BEFORE as guidance)
                if (!c1.isBlank() && !c2.isBlank()) {
                    String before = c1 + "\n/* --- second snippet --- */\n" + c2;
                    String after  = ref == null ? "" : ref.trim();
                    out.add(new RefactorExample(before, after, rat));
                }
            } else {
                // Legacy: expect explicit BEFORE/AFTER columns
                if (idxBefore < 0 || idxAfter < 0) continue;
                if (cols.length <= Math.max(idxAfter, idxBefore)) continue;
                String before = safeTruncate(cols[idxBefore], maxChars);
                String after  = safeTruncate(cols[idxAfter], maxChars);
                String rat    = (idxRat >= 0 && idxRat < cols.length) ? cols[idxRat] : "";
                if (!before.isBlank()) {
                    // Allow empty 'after' to keep the row; we'll display "After: (empty)" in the prompt.
                    out.add(new RefactorExample(before, after == null ? "" : after, rat));
                }
            }
        }
        return out;
    }

    /**
     * Ranks refactor examples by token overlap against the query text. Returns top-k scored items.
     */
    private static java.util.List<Scored<RefactorExample>> rankByOverlap(String query, java.util.List<RefactorExample> pool, int k) {
        java.util.Map<String, Integer> qtf = tf(toTokens(query));
        java.util.List<Scored<RefactorExample>> scored = new java.util.ArrayList<>();
        for (RefactorExample ex : pool) {
            String text = ex.before; // match against BEFORE side
            java.util.Map<String, Integer> dtf = tf(toTokens(text));
            double score = 0.0;
            for (String t : qtf.keySet()) {
                Integer f = dtf.get(t);
                if (f != null) score += Math.min(qtf.get(t), f);
            }
            if (score > 0) scored.add(new Scored<>(ex, score));
        }
        scored.sort((x, y) -> Double.compare(y.score, x.score));
        if (scored.size() > k) return scored.subList(0, k);
        return scored;
    }
    private static java.util.List<String> toTokens(String s) {
        java.util.List<String> toks = new java.util.ArrayList<>();
        if (s == null) return toks;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z_0-9]*").matcher(s);
        while (m.find()) {
            toks.add(m.group().toLowerCase());
        }
        return toks;
    }
    private static java.util.Map<String, Integer> tf(java.util.List<String> toks) {
        java.util.Map<String, Integer> map = new java.util.HashMap<>();
        for (String t : toks) map.put(t, map.getOrDefault(t, 0) + 1);
        return map;
    }

    private static java.util.List<RefactorExample> takeItems(
            java.util.List<Scored<RefactorExample>> scored,
            java.util.List<RefactorExample> fallback) {
        if (scored == null || scored.isEmpty()) return fallback;
        java.util.List<RefactorExample> out = new java.util.ArrayList<>(scored.size());
        for (Scored<RefactorExample> s : scored) out.add(s.item);
        return out;
    }

    private static java.util.List<Scored<RefactorExample>> rankByEmbedding(
            String query, java.util.List<RefactorExample> candidates, Project project) {
        try {
            if (candidates == null || candidates.isEmpty()) return java.util.Collections.emptyList();
            double[] q = embedText(query, project);
            if (q == null) return java.util.Collections.emptyList();
            java.util.List<Scored<RefactorExample>> out = new java.util.ArrayList<>();
            for (RefactorExample ex : candidates) {
                double[] v = embedText(ex.before, project);
                if (v == null) continue;
                out.add(new Scored<>(ex, cosine(q, v)));
            }
            out.sort((x, y) -> Double.compare(y.score, x.score));
            return out;
        } catch (Throwable t) {
            System.err.println("[RAG] rankByEmbedding failed: " + t.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    private static java.util.List<Scored<RefactorExample>> reciprocalRankFusion(
            java.util.List<Scored<RefactorExample>> sparse,
            java.util.List<Scored<RefactorExample>> dense,
            int k) {
        java.util.Map<RefactorExample, Double> rrf = new java.util.HashMap<>();
        addRrf(rrf, sparse);
        addRrf(rrf, dense);
        java.util.List<Scored<RefactorExample>> fused = new java.util.ArrayList<>();
        for (java.util.Map.Entry<RefactorExample, Double> e : rrf.entrySet()) {
            fused.add(new Scored<>(e.getKey(), e.getValue()));
        }
        fused.sort((x, y) -> Double.compare(y.score, x.score));
        if (fused.size() > k) return fused.subList(0, k);
        return fused;
    }

    private static void addRrf(java.util.Map<RefactorExample, Double> map,
                               java.util.List<Scored<RefactorExample>> ranking) {
        if (ranking == null) return;
        for (int i = 0; i < ranking.size(); i++) {
            int rank = i + 1; // 1-based
            double inc = 1.0 / (RRF_K + rank);
            RefactorExample ex = ranking.get(i).item;
            map.put(ex, map.getOrDefault(ex, 0.0) + inc);
        }
    }

    // ---- Embedding client (HF Feature Extraction / Ollama / OpenAI / Azure) ----
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static double[] embedText(String text, Project project) {
        try {
            if (text == null || text.isBlank()) return null;
            String provider = EMBED_PROVIDER;
            if ("HF".equalsIgnoreCase(provider)) {
                return embedHuggingFace(text);
            } else if ("Ollama".equalsIgnoreCase(provider)) {
                return embedOllama(text);
            } else if ("OpenAI".equalsIgnoreCase(provider)) {
                return embedOpenAI(text);
            } else if ("Azure".equalsIgnoreCase(provider)) {
                return embedAzure(text);
            } else {
                System.err.println("[RAG] Unknown EMBED_PROVIDER: " + provider);
                return null;
            }
        } catch (Throwable t) {
            System.err.println("[RAG] embedText failed: " + t.getMessage());
            return null;
        }
    }

    /** Hugging Face Inference API（feature-extraction）。*/
    private static double[] embedHuggingFace(String text) throws Exception {
        String token = System.getenv("HUGGINGFACE_API_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("[RAG] HUGGINGFACE_API_TOKEN is not set; dense retrieval disabled.");
            return null;
        }
        String url = "https://api-inference.huggingface.co/pipeline/feature-extraction/" + EMBED_MODEL;
        String body = "{\"inputs\":" + jsonString(text) + ",\"options\":{\"wait_for_model\":true}}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return parseHfFeatureExtraction(resp.body());
    }

    private static double[] embedOpenAI(String text) throws Exception {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) return null;
        String base = System.getenv().getOrDefault("OPENAI_API_BASE", "https://api.openai.com");
        String url = (base.endsWith("/") ? base + "v1/embeddings" : base + "/v1/embeddings");
        String body = "{\"model\":\"text-embedding-3-small\",\"input\":" + jsonString(text) + "}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return parseOpenAIEmbedding(resp.body());
    }

    private static double[] embedAzure(String text) throws Exception {
        String key = System.getenv("AZURE_API_KEY");
        String base = System.getenv("AZURE_API_BASE");
        String ver  = System.getenv("AZURE_API_VERSION");
        if (key == null || base == null || ver == null || key.isBlank() || base.isBlank() || ver.isBlank()) return null;
        String url = base + "/openai/deployments/text-embedding-3-small/embeddings?api-version=" + ver;
        String body = "{\"input\":" + jsonString(text) + "}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("api-key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return parseOpenAIEmbedding(resp.body());
    }

    private static double[] embedOllama(String text) throws Exception {
        String base = System.getenv("OLLAMA_API_BASE");
        if (base == null || base.isBlank()) base = "http://localhost:11434";
        String url = base + "/api/embeddings";
        String body = "{\"model\":\"nomic-embed-text\",\"prompt\":" + jsonString(text) + "}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return parseOllamaEmbedding(resp.body());
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private static double[] parseOpenAIEmbedding(String json) {
        if (json == null) return null;
        int idx = json.indexOf("\"embedding\"");
        if (idx < 0) return null;
        int lb = json.indexOf('[', idx);
        int rb = json.indexOf(']', lb);
        if (lb < 0 || rb < 0) return null;
        String[] parts = json.substring(lb + 1, rb).split(",");
        double[] v = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { v[i] = Double.parseDouble(parts[i].trim()); } catch (Exception e) { v[i] = 0; }
        }
        return v;
    }
    private static double[] parseOllamaEmbedding(String json) {
        if (json == null) return null;
        int idx = json.indexOf("\"embedding\"");
        if (idx < 0) return null;
        int lb = json.indexOf('[', idx);
        int rb = json.indexOf(']', lb);
        if (lb < 0 || rb < 0) return null;
        String[] parts = json.substring(lb + 1, rb).split(",");
        double[] v = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { v[i] = Double.parseDouble(parts[i].trim()); } catch (Exception e) { v[i] = 0; }
        }
        return v;
    }

    private static double[] parseHfFeatureExtraction(String json) {
        if (json == null) return null;
        int start = json.indexOf("[[");
        int end = json.indexOf("]]", start + 2);
        if (start < 0 || end < 0) {
            int s1 = json.indexOf('[');
            int e1 = json.lastIndexOf(']');
            if (s1 >= 0 && e1 > s1) {
                String[] parts = json.substring(s1 + 1, e1).split(",");
                double[] v = new double[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    try { v[i] = Double.parseDouble(parts[i].trim()); } catch (Exception e) { v[i] = 0; }
                }
                return v;
            }
            return null;
        }
        String core = json.substring(start + 2, end);
        java.util.List<double[]> rows = new java.util.ArrayList<>();
        int pos = 0;
        while (pos < core.length()) {
            int next = core.indexOf("],[", pos);
            String row = (next == -1) ? core.substring(pos) : core.substring(pos, next);
            String[] parts = row.split(",");
            double[] vec = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try { vec[i] = Double.parseDouble(parts[i].trim()); } catch (Exception e) { vec[i] = 0; }
            }
            rows.add(vec);
            if (next == -1) break;
            pos = next + 3;
        }
        if (rows.isEmpty()) return null;
        int dim = rows.get(0).length;
        double[] mean = new double[dim];
        for (double[] r : rows) {
            for (int i = 0; i < Math.min(dim, r.length); i++) mean[i] += r[i];
        }
        for (int i = 0; i < dim; i++) mean[i] /= rows.size();
        return mean;
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /**
     * Builds the detection prompt and injects few-shot examples sampled from a CSV in resources or filesystem.
     * The CSV can be specified via CLONE_DB_PATH and supports classpath or filesystem loading.
     *
     * @param project IntelliJ project (used for project-relative paths)
     * @param maxChars maximum characters to keep for each code snippet (to control token usage)
     * @return final detection prompt string (falls back to the default if CSV missing)
     *
     * For each example, two code snippets (A/B) are provided along with the correct label (from the 'output' column).
     */
    private static String buildDetectionPromptWithFewShot(Project project, int maxChars) {
        String base = "Please detect any clones in this file. Respond with either 'clones found' or 'no clones found'.";
        java.util.List<FewShotExample> examples = loadFewShotExamples(CLONE_DB_PATH, project, maxChars);
        if (examples.isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(base).append("\n\n");
        sb.append("Here are ").append(examples.size()).append(" labeled examples.\n")
          .append("For each example, two code snippets (A/B) are provided along with the correct label (from the 'output' column).\n")
          .append("Use these as few-shot guidance; DO NOT copy the code. Your final answer must be only 'clones found' or 'no clones found'.\n\n");
        int idx = 1;
        for (FewShotExample ex : examples) {
            sb.append("Example ").append(idx++).append(":\n");
            sb.append("Code A:\n```\n").append(ex.codeA).append("\n```\n");
            sb.append("Code B:\n```\n").append(ex.codeB).append("\n```\n");
            sb.append("Label: ").append(ex.label).append("\n\n");
        }
        sb.append("Now, based on the file content provided, answer with exactly one of: 'clones found' or 'no clones found'.");
        return sb.toString();
    }
    /**
     * Flexible loader that accepts either a classpath resource name (e.g., "clone_database.csv" or "db/clone_database.csv")
     * or a filesystem path (absolute or relative to the project root, e.g., "resources/clone_database.csv").
     */
    private static java.util.List<FewShotExample> loadFewShotExamples(String pathOrResource, Project project, int maxChars) {
        java.util.List<FewShotExample> pool = java.util.Collections.emptyList();

        // 1) Try classpath as given
        java.util.List<FewShotExample> ex = loadFewShotExamplesFromResources(pathOrResource, maxChars);
        if (!ex.isEmpty()) {
            pool = ex;
        } else {
            // 2) If "resources/..." was provided, also try just the tail as a classpath resource (common Gradle/Maven layout)
            int slash = pathOrResource.lastIndexOf('/');
            if (slash >= 0) {
                String tail = pathOrResource.substring(slash + 1);
                ex = loadFewShotExamplesFromResources(tail, maxChars);
                if (!ex.isEmpty()) {
                    pool = ex;
                }
            }

            // 3) Try as filesystem path (absolute or relative to project root)
            if (pool.isEmpty()) {
                java.io.File f = new java.io.File(pathOrResource);
                if (!f.isAbsolute()) {
                    String base = project != null ? project.getBasePath() : null;
                    if (base != null && !base.isBlank()) {
                        f = new java.io.File(base, pathOrResource);
                    }
                }
                if (f.exists() && f.isFile()) {
                    pool = loadFewShotExamplesFromFile(f, maxChars);
                }
            }

            // 4) Last resort: try user.dir as base
            if (pool.isEmpty()) {
                String userDir = System.getProperty("user.dir");
                if (userDir != null) {
                    java.io.File f2 = new java.io.File(userDir, pathOrResource);
                    if (f2.exists() && f2.isFile()) {
                        pool = loadFewShotExamplesFromFile(f2, maxChars);
                    }
                }
            }
        }

        if (pool.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        // Prefer a fixed mix of 8 examples: 4 clone types (Type 1..4) + 4 "No clone" examples when available.
        java.util.Map<String, java.util.List<FewShotExample>> byType = new java.util.HashMap<>();
        for (FewShotExample e : pool) {
            String raw = (e.cloneType == null ? "" : e.cloneType.trim());
            String typeKey = raw.isEmpty() ? "__NO_TYPE__" : raw;
            byType.computeIfAbsent(typeKey, k2 -> new java.util.ArrayList<>()).add(e);
        }

        java.util.List<FewShotExample> selected = new java.util.ArrayList<>();
        java.util.Random rnd = new java.util.Random();

        // 1) One example for each clone type 1..4 (if present)
        String[] cloneTypes = {"Type 1", "Type 2", "Type 3", "Type 4"};
        for (String t : cloneTypes) {
            java.util.List<FewShotExample> bucket = byType.get(t);
            if (bucket != null && !bucket.isEmpty()) {
                int idx = rnd.nextInt(bucket.size());
                selected.add(bucket.get(idx));
            }
        }

        // 2) Up to four distinct "No clone" examples (if present)
        java.util.List<FewShotExample> noCloneBucket = byType.get("No clone");
        if (noCloneBucket != null && !noCloneBucket.isEmpty()) {
            java.util.List<FewShotExample> copy = new java.util.ArrayList<>(noCloneBucket);
            java.util.Collections.shuffle(copy, rnd);
            int n = Math.min(4, copy.size());
            for (int i = 0; i < n; i++) {
                selected.add(copy.get(i));
            }
        }

        return selected;
    }

    private static class FewShotExample {
        final String codeA;
        final String codeB;
        final String label;
        final String cloneType;

        FewShotExample(String a, String b, String l, String t) {
            this.codeA = a;
            this.codeB = b;
            this.label = l;
            this.cloneType = (t == null ? "" : t);
        }
    }

    /**
     * Reads the next logical CSV record from a BufferedReader, allowing newlines inside quoted fields.
     * Returns {@code null} on EOF. Implements simple RFC4180-style quote handling with double-quote escapes.
     */
    private static String readNextCsvRecord(java.io.BufferedReader br) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        boolean inQuotes = false;
        while (true) {
            line = br.readLine();
            if (line == null) {
                // EOF
                if (sb.length() == 0) return null;
                break;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(line);
            // Toggle quote state accounting for escaped quotes ("")
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '\"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                        i++; // skip escaped quote
                    } else {
                        inQuotes = !inQuotes;
                    }
                }
            }
            if (!inQuotes) {
                break; // a complete logical record
            }
        }
        return sb.toString();
    }

    /**
     * Loads up to k random few-shot examples from a CSV bundled in resources.
     * The CSV is expected to have a header with columns: code1, code2, output (also accepts response/label).
     * Extra columns are ignored. Quoted fields and commas within quotes are supported.
     */
    private static java.util.List<FewShotExample> loadFewShotExamplesFromResources(String resourceName, int maxChars) {
        java.util.List<FewShotExample> all = new java.util.ArrayList<>();
        try (java.io.InputStream is = AiderHelper.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                return all; // No resource available; fall back to no few-shot
            }
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                String header = readNextCsvRecord(br);
                if (header == null) return all;
                String[] hdr = splitCsvLine(header);
                if (hdr == null || hdr.length == 0) return all;
                int idxCode1 = -1, idxCode2 = -1, idxResp = -1, idxType = -1;
                for (int i = 0; i < hdr.length; i++) {
                    String h = hdr[i].trim().toLowerCase();
                    if (h.equals("code1") || h.equals("code_1")) idxCode1 = i;
                    else if (h.equals("code2") || h.equals("code_2")) idxCode2 = i;
                    else if (h.equals("output") || h.equals("response") || h.equals("label")) idxResp = i;
                    else if (h.equals("clone_type") || h.equals("type")) idxType = i;
                }
                if (idxCode1 < 0 || idxCode2 < 0 || idxResp < 0) {
                    return all; // header doesn't match; bail out
                }
                String line;
                while ((line = readNextCsvRecord(br)) != null) {
                    if (line.isBlank()) continue;
                    String[] cols = splitCsvLine(line);
                    if (cols.length <= Math.max(idxResp, Math.max(idxCode1, idxCode2))) continue;
                    String c1 = safeTruncate(cols[idxCode1], maxChars);
                    String c2 = safeTruncate(cols[idxCode2], maxChars);
                    String resp = cols[idxResp] == null ? "" : cols[idxResp].trim();
                    String type = (idxType >= 0 && idxType < cols.length) ? cols[idxType].trim() : "";
                    if (!c1.isBlank() && !c2.isBlank() && !resp.isBlank()) {
                        all.add(new FewShotExample(c1, c2, resp, type));
                    }
                }
            }
        } catch (Throwable ignored) {
            // On any error, just return empty and fall back to base prompt
        }
        return all;
    }

    /**
     * Minimal CSV splitter that supports quotes and commas inside quotes.
     */
    private static String[] splitCsvLine(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                    // Escaped quote
                    cur.append('\"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if ((ch == ',' || ch == '\t') && !inQuotes) {
                // Treat both comma and tab as field separators (supports CSV and TSV headers).
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }


    private static String safeTruncate(String s, int maxChars) {
        if (s == null) return "";
        String t = s.trim();
        if (maxChars <= 0) return t;
        if (t.length() <= maxChars) return t;
        return t.substring(0, maxChars) + "\n...<truncated>...";
    }

    /**
     * Runs the full workflow on a temp copy: Detect (already done) -> Refactor -> Compile -> Test.
     * If successful, shows a diff and optionally applies to the original file.
     */
    private static void runMultiAgentRefactorWithPreview(Project project,
                                                         String fileName,
                                                         String originalFilePath,
                                                         String tempFilePath,
                                                         String provider,
                                                         String model,
                                                         String apikey,
                                                         String aiderPath,
                                                         String apiBase,
                                                         String apiVersion,
                                                         detection.DetectionResult det) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Consumer<String> viewer = openStreamingViewer(project, "Aider Multi-Agent Workflow");

                File originalFile = new File(originalFilePath);
                File tempFile = new File(tempFilePath);

                String originalContent = Files.readString(originalFile.toPath(), StandardCharsets.UTF_8);
                String current = Files.readString(tempFile.toPath(), StandardCharsets.UTF_8);

                // LLM caller injected into agents. Keep using Aider as the transport.
                Function<String, String> llmCaller = (prompt) -> {
                    try {
                        return runAiderWithPromptStreaming(project, aiderPath, tempFilePath,
                                prompt, provider, model, apikey, apiBase, apiVersion, viewer);
                    } catch (Exception e) {
                        return "[LLM_CALL_EXCEPTION] " + e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
                    }
                };

                // Choose the first detected clone as the target for the first implementation pass.
                detection.DetectedClone first = det != null && det.clones != null && !det.clones.isEmpty() ? det.clones.get(0) : null;
                if (first == null) {
                    notify(project, "No clone available to refactor.");
                    return;
                }

                // Prepare a small RAG example bundle using the existing refactor example DB.
                String[] cloneSnippets = extractRepresentativeCloneSnippets(project, aiderPath, tempFilePath,
                        provider, model, apikey, apiBase, apiVersion, viewer);
                String ragExamples = buildRagExamplesForRefactor(project, cloneSnippets, 2, 700);

                // Agents
                refactor refAgent = new refactor();
                compile compAgent = new compile();
                testing testAgent = new testing();

                String feedback = "";
                String successRefactored = null;

                // A small repair loop: refactor -> compile -> test, up to N attempts.
                for (int attempt = 1; attempt <= 3; attempt++) {
                    viewer.accept("\n=== [WORKFLOW] Attempt " + attempt + " / 3 ===");

                    // Merge prior feedback into the RAG guidance (so the next refactor can repair)
                    String guidance = ragExamples;
                    if (feedback != null && !feedback.isBlank()) {
                        guidance = (guidance == null ? "" : guidance) + "\n\n[FEEDBACK_FROM_COMPILE_OR_TEST]\n" + feedback;
                    }

                    // 2) Refactoring Agent
                    refactor.DetectedClone rc = convertClone(first);
                    refactor.RefactorResult rr = refAgent.refactorFile(fileName, current, rc, guidance, llmCaller);
                    if (rr == null || rr.newSource == null || rr.newSource.isBlank()) {
                        viewer.accept("[REFACTOR] failed: " + (rr == null ? "null result" : rr.message));
                        feedback = "Refactor agent did not produce a valid full file.";
                        continue;
                    }

                    // Write candidate to temp file for compile/test steps
                    Files.writeString(tempFile.toPath(), rr.newSource, StandardCharsets.UTF_8);

                    // 3) Compile (tool)
                    String projectDir = project.getBasePath() != null ? project.getBasePath() : ".";
                    String compileLog = runMavenCapture(projectDir, viewer,
                            "mvn", "-q", "-DskipTests", "compile");
                    compile.CompileResult cr = compAgent.analyze(fileName, compileLog);

                    if (cr == null || !"compile_ok".equals(cr.status)) {
                        viewer.accept("[COMPILE] status=" + (cr == null ? "null" : cr.status));
                        if (cr != null) viewer.accept("[COMPILE] summary=" + cr.summary);
                        // keep refactored as the new baseline, but feed errors back
                        current = rr.newSource;
                        feedback = "Compile failed. " + (cr == null ? "" : cr.summary) + "\n\n" + safeTruncate(compileLog, 4000);
                        continue;
                    }
                    viewer.accept("[COMPILE] OK");

                    // 4) Test (tool)
                    testing.TestRunRequest treq = new testing.TestRunRequest(projectDir, "all", null, false);
                    Function<testing.TestRunRequest, String> testRunner = (req) ->
                            runMavenCapture(projectDir, viewer, "mvn", "-q", "test");

                    testing.TestResult tr = testAgent.runAndSummarize(treq, testRunner, llmCaller, originalContent, rr.newSource);

                    if (tr == null) {
                        current = rr.newSource;
                        feedback = "Test agent returned null.";
                        continue;
                    }

                    viewer.accept("[TEST] status=" + tr.status);
                    if ("tests_passed".equals(tr.status)) {
                        successRefactored = rr.newSource;
                        break;
                    }

                    // tests failed/unknown: feed back summary when available
                    current = rr.newSource;
                    if (tr.summary != null && !tr.summary.isBlank()) {
                        feedback = "Tests failed. Summary:\n" + tr.summary;
                    } else {
                        feedback = "Tests failed. Raw:\n" + safeTruncate(tr.raw, 4000);
                    }
                }

                if (successRefactored == null || successRefactored.isBlank()) {
                    notify(project, "Multi-agent workflow could not produce a passing refactor for " + fileName + ".");
                    return;
                }

                // Show diff
                String refactoredContent = successRefactored;
                if (!originalContent.equals(refactoredContent)) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        DiffContentFactory contentFactory = DiffContentFactory.getInstance();
                        SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                                "Multi-Agent Refactor Preview",
                                contentFactory.create(originalContent),
                                contentFactory.create(refactoredContent),
                                "Original",
                                "Refactored"
                        );
                        DiffManager.getInstance().showDiff(project, diffRequest);
                    });

                    // Confirm apply
                    ApplicationManager.getApplication().invokeLater(() -> {
                        int choice = Messages.showYesNoDialog(
                                project,
                                "Apply the refactored code (compile+test passed) to " + fileName + "?",
                                "Apply Refactoring",
                                Messages.getQuestionIcon()
                        );
                        if (choice == Messages.YES) {
                            try {
                                Files.writeString(originalFile.toPath(), refactoredContent, StandardCharsets.UTF_8);
                                notify(project, "File updated: " + fileName);
                            } catch (IOException e) {
                                notify(project, "Failed to overwrite file: " + e.getMessage());
                            }
                        } else {
                            notify(project, "Refactoring not applied.");
                        }
                    });
                } else {
                    notify(project, "Refactor produced no textual changes for " + fileName + ".");
                }

            } catch (Exception e) {
                notify(project, "Multi-agent refactor failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /** Convert detection agent clone type into refactor agent clone type. */
    private static refactor.DetectedClone convertClone(detection.DetectedClone c) {
        if (c == null) return null;
        java.util.List<refactor.CloneRange> ranges = new java.util.ArrayList<>();
        if (c.ranges != null) {
            for (detection.CloneRange r : c.ranges) {
                if (r == null) continue;
                ranges.add(new refactor.CloneRange(r.startLine, r.endLine));
            }
        }
        return new refactor.DetectedClone(
                c.id == null ? "clone" : c.id,
                ranges,
                c.refactorType,
                c.reason
        );
    }

    /**
     * Runs a Maven command in `projectDir`, captures stdout+stderr as a single string, and optionally streams to viewer.
     */
    private static String runMavenCapture(String projectDir, Consumer<String> viewer, String... command) {
        StringBuilder out = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            if (projectDir != null && !projectDir.isBlank()) {
                pb.directory(new File(projectDir));
            }
            // Avoid ANSI noise in logs
            pb.environment().put("NO_COLOR", "1");

            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String cleaned = stripNonPrintable(stripAnsi(line));
                    if (cleaned == null) continue;
                    out.append(cleaned).append("\n");
                    if (viewer != null && !cleaned.trim().isEmpty()) {
                        viewer.accept(cleaned);
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            out.append("[MAVEN_EXCEPTION] ").append(e.getClass().getSimpleName()).append(": ")
                    .append(e.getMessage() == null ? "" : e.getMessage()).append("\n");
        }
        return out.toString();
    }

    /**
     * Build a compact RAG example bundle (BEFORE/AFTER) to pass into the refactor agent.
     * Uses the existing REFACTOR_DB_PATH retrieval already implemented in this class.
     */
    private static String buildRagExamplesForRefactor(Project project, String[] cloneSnippets, int k, int maxChars) {
        String a = cloneSnippets != null && cloneSnippets.length > 0 ? cloneSnippets[0] : "";
        String b = cloneSnippets != null && cloneSnippets.length > 1 ? cloneSnippets[1] : "";

        java.util.List<RefactorExample> pool = loadRefactorExamples(REFACTOR_DB_PATH, project, maxChars);
        if (pool == null || pool.isEmpty()) return "";

        java.util.List<Scored<RefactorExample>> sparseRank =
                rankByOverlap(a + "\n" + b, pool, Math.min(pool.size(), DENSE_CANDIDATES_LIMIT));
        java.util.List<Scored<RefactorExample>> denseRank =
                ENABLE_DENSE_RETRIEVAL ? rankByEmbedding(a + "\n" + b, takeItems(sparseRank, pool), project)
                        : java.util.Collections.emptyList();
        java.util.List<Scored<RefactorExample>> tops = reciprocalRankFusion(sparseRank, denseRank, k);

        if (tops == null || tops.isEmpty()) {
            java.util.Collections.shuffle(pool, new java.util.Random());
            tops = new java.util.ArrayList<>();
            for (int i = 0; i < Math.min(k, pool.size()); i++) {
                tops.add(new Scored<>(pool.get(i), 0.0));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[RAG_EXAMPLES]\n");
        int idx = 1;
        for (Scored<RefactorExample> s : tops) {
            RefactorExample ex = s.item;
            sb.append("Example ").append(idx++).append(" (score ")
                    .append(String.format(java.util.Locale.US, "%.3f", s.score)).append("):\n");
            sb.append("BEFORE:\n``\n").append(ex.before).append("\n``\n");
            if (ex.after != null && !ex.after.isBlank()) {
                sb.append("AFTER:\n``\n").append(ex.after).append("\n``\n");
            } else {
                sb.append("AFTER: (empty)\n");
            }
            if (ex.rationale != null && !ex.rationale.isBlank()) {
                sb.append("NOTE: ").append(ex.rationale).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static java.util.List<FewShotExample> loadFewShotExamplesFromFile(java.io.File file, int maxChars) {
        java.util.List<FewShotExample> all = new java.util.ArrayList<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            String header = readNextCsvRecord(br);
            if (header == null) return all;
            String[] hdr = splitCsvLine(header);
            if (hdr == null || hdr.length == 0) return all;
            int idxCode1 = -1, idxCode2 = -1, idxResp = -1, idxType = -1;
            for (int i = 0; i < hdr.length; i++) {
                String h = hdr[i].trim().toLowerCase();
                if (h.equals("code1") || h.equals("code_1")) idxCode1 = i;
                else if (h.equals("code2") || h.equals("code_2")) idxCode2 = i;
                else if (h.equals("output") || h.equals("response") || h.equals("label")) idxResp = i;
                else if (h.equals("clone_type") || h.equals("type")) idxType = i;
            }
            if (idxCode1 < 0 || idxCode2 < 0 || idxResp < 0) {
                return all;
            }
            String line;
            while ((line = readNextCsvRecord(br)) != null) {
                if (line.isBlank()) continue;
                String[] cols = splitCsvLine(line);
                if (cols.length <= Math.max(idxResp, Math.max(idxCode1, idxCode2))) continue;
                String c1 = safeTruncate(cols[idxCode1], maxChars);
                String c2 = safeTruncate(cols[idxCode2], maxChars);
                String resp = cols[idxResp] == null ? "" : cols[idxResp].trim();
                String type = (idxType >= 0 && idxType < cols.length) ? cols[idxType].trim() : "";
                if (!c1.isBlank() && !c2.isBlank() && !resp.isBlank()) {
                    all.add(new FewShotExample(c1, c2, resp, type));
                }
            }
        } catch (Throwable ignored) {
        }
        return all;
    }
}