package org.jetbrains.research.anticopypaster.ide;

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

public class AiderHelper {

    // Change this to your CSV location. Supports either a classpath resource (e.g., "clone_database.csv" or "db/clone_database.csv")
    // or a filesystem path (absolute or relative to the project root), e.g., "resources/clone_database.csv". Expected headers include code1/code_1, code2/code_2, and output|response|label.
    private static final String CLONE_DB_PATH = "resources/clone_database.csv";

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
                    Consumer<String> viewer = openStreamingViewer(project, "Aider Detection Output");
                    String detectionPrompt = buildDetectionPromptWithFewShot(project, 3, 400);
                    int _fewShotCount = loadFewShotExamples(CLONE_DB_PATH, project, 3, 400).size();
                    viewer.accept("[RAG] Few-shot examples loaded: " + _fewShotCount + " from " + CLONE_DB_PATH);
                    if (_fewShotCount == 0) {
                        viewer.accept("[RAG] No examples loaded. Check path and headers. Expected headers include code1/code_1, code2/code_2, and output|response|label.");
                    }
                    // Preview the prompt so users can verify few-shot examples are injected
                    viewer.accept("---- Detection Prompt (preview) ----");
                    viewer.accept(detectionPrompt);
                    viewer.accept("---- End Prompt ----");
                    String output = runAiderWithPromptStreaming(project, aiderPath, tempFilePath,
                            detectionPrompt, provider, model, apikey, apiBase, apiVersion, viewer);

                    if (output != null && containsDuplicateHint(output)) {
                        System.out.println("===> Aider Output:\n" + output);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            int choice = Messages.showYesNoDialog(
                                    project,
                                    "Aider found clones in " + fileName + ". Do you want to refactor it?",
                                    "Code Refactoring",
                                    Messages.getQuestionIcon()
                            );
                            if (choice == Messages.YES) {
                                runRefactorWithPreview(project, fileName, filePath, provider, model, apikey, aiderPath, apiBase, apiVersion);
                            }
                        });
                    } else {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            notify(project, "Aider did not detect any clones in the file " + fileName + ".");
                        });
                    }

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
                String output = runAiderWithPromptStreaming(project, aiderPath, tempFile.getAbsolutePath(),
                        "Refactor this file by Extract Method to eliminate clones. Output the COMPLETE final Java file ONLY, inside a single ```java code block. Do NOT output patches, SEARCH/REPLACE markers, or explanations.",
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
     * Builds the detection prompt and injects few-shot examples sampled from a CSV in resources or filesystem.
     * The CSV can be specified via CLONE_DB_PATH and supports classpath or filesystem loading.
     *
     * @param project IntelliJ project (used for project-relative paths)
     * @param k number of examples to include
     * @param maxChars maximum characters to keep for each code snippet (to control token usage)
     * @return final detection prompt string (falls back to the default if CSV missing)
     *
     * For each example, two code snippets (A/B) are provided along with the correct label (from the 'output' column).
     */
    private static String buildDetectionPromptWithFewShot(Project project, int k, int maxChars) {
        String base = "Please detect any clones in this file. Respond with either 'clones found' or 'no clones found'.";
        java.util.List<FewShotExample> examples = loadFewShotExamples(CLONE_DB_PATH, project, k, maxChars);
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
    private static java.util.List<FewShotExample> loadFewShotExamples(String pathOrResource, Project project, int k, int maxChars) {
        // 1) Try classpath as given
        java.util.List<FewShotExample> ex = loadFewShotExamplesFromResources(pathOrResource, k, maxChars);
        if (!ex.isEmpty()) return ex;

        // 2) If "resources/..." was provided, also try just the tail as a classpath resource (common Gradle/Maven layout)
        int slash = pathOrResource.lastIndexOf('/');
        if (slash >= 0) {
            String tail = pathOrResource.substring(slash + 1);
            ex = loadFewShotExamplesFromResources(tail, k, maxChars);
            if (!ex.isEmpty()) return ex;
        }

        // 3) Try as filesystem path (absolute or relative to project root)
        java.io.File f = new java.io.File(pathOrResource);
        if (!f.isAbsolute()) {
            String base = project != null ? project.getBasePath() : null;
            if (base != null && !base.isBlank()) {
                f = new java.io.File(base, pathOrResource);
            }
        }
        if (f.exists() && f.isFile()) {
            return loadFewShotExamplesFromFile(f, k, maxChars);
        }

        // 4) Last resort: try user.dir as base
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            java.io.File f2 = new java.io.File(userDir, pathOrResource);
            if (f2.exists() && f2.isFile()) {
                return loadFewShotExamplesFromFile(f2, k, maxChars);
            }
        }
        return java.util.Collections.emptyList();
    }

    private static class FewShotExample {
        final String codeA;
        final String codeB;
        final String label;
        FewShotExample(String a, String b, String l) {
            this.codeA = a;
            this.codeB = b;
            this.label = l;
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
    private static java.util.List<FewShotExample> loadFewShotExamplesFromResources(String resourceName, int k, int maxChars) {
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
                int idxCode1 = -1, idxCode2 = -1, idxResp = -1;
                for (int i = 0; i < hdr.length; i++) {
                    String h = hdr[i].trim().toLowerCase();
                    if (h.equals("code1") || h.equals("code_1")) idxCode1 = i;
                    else if (h.equals("code2") || h.equals("code_2")) idxCode2 = i;
                    else if (h.equals("output") || h.equals("response") || h.equals("label")) idxResp = i;
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
                    if (!c1.isBlank() && !c2.isBlank() && !resp.isBlank()) {
                        all.add(new FewShotExample(c1, c2, resp));
                    }
                }
            }
        } catch (Throwable ignored) {
            // On any error, just return empty and fall back to base prompt
        }
        if (all.size() <= k) {
            return all;
        }
        // Sample k uniformly at random
        java.util.Collections.shuffle(all, new java.util.Random());
        return all.subList(0, k);
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
            } else if (ch == ',' && !inQuotes) {
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
        s = s.trim();
        if (maxChars > 0 && s.length() > maxChars) {
            return s.substring(0, maxChars) + "\n/* …truncated… */";
        }
        return s;
    }

    private static java.util.List<FewShotExample> loadFewShotExamplesFromFile(java.io.File file, int k, int maxChars) {
        java.util.List<FewShotExample> all = new java.util.ArrayList<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            String header = readNextCsvRecord(br);
            if (header == null) return all;
            String[] hdr = splitCsvLine(header);
            if (hdr == null || hdr.length == 0) return all;
            int idxCode1 = -1, idxCode2 = -1, idxResp = -1;
            for (int i = 0; i < hdr.length; i++) {
                String h = hdr[i].trim().toLowerCase();
                if (h.equals("code1") || h.equals("code_1")) idxCode1 = i;
                else if (h.equals("code2") || h.equals("code_2")) idxCode2 = i;
                else if (h.equals("output") || h.equals("response") || h.equals("label")) idxResp = i;
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
                if (!c1.isBlank() && !c2.isBlank() && !resp.isBlank()) {
                    all.add(new FewShotExample(c1, c2, resp));
                }
            }
        } catch (Throwable ignored) {
        }
        if (all.size() <= k) return all;
        java.util.Collections.shuffle(all, new java.util.Random());
        return all.subList(0, k);
    }
}