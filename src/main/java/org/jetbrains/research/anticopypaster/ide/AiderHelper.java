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
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class AiderHelper {

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
                toolWindow = twm.getToolWindow("Clone Output");
                if (toolWindow == null) {
                    toolWindow = twm.registerToolWindow(RegisterToolWindowTask.notClosable("Clone Output"));
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
        notify(project, "Clone is running clone detection on " + fileName + "...");
        String filePath = file.getPath();

        try {
            File originalFile = new File(filePath);
            File tempFile = File.createTempFile("aider_clonecheck_", ".java");
            Files.copy(originalFile.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            String tempFilePath = tempFile.getAbsolutePath();

            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    Consumer<String> viewer = openStreamingViewer(project, "Clone Detection Output");
                    String output = runAiderWithPromptStreaming(project, aiderPath, tempFilePath,
                            "Please detect any clones in this file. Response with either 'clones found' or 'no clones found'", provider, model, apikey, apiBase, apiVersion, viewer);

                    if (output != null && containsDuplicateHint(output)) {
                        System.out.println("===> Clone Output:\n" + output);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            int choice = Messages.showYesNoDialog(
                                    project,
                                    "Clone found clones in " + fileName + ". Do you want to refactor it?",
                                    "Code Refactoring",
                                    Messages.getQuestionIcon()
                            );
                            if (choice == Messages.YES) {
                                runRefactorWithPreview(project, fileName, filePath, provider, model, apikey, aiderPath, apiBase, apiVersion);
                            }
                        });
                    } else {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            notify(project, "Clone did not detect any clones in the file " + fileName + ".");
                        });
                    }

                } catch (Exception e) {
                    notify(project, "Clone Error: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        } catch (IOException e) {
            notify(project, "Clone Error: " + e.getMessage());
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
        notify(project, "Clone is running code refactoring on " + fileName + "...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                File originalFile = new File(filePath);
                File tempFile = File.createTempFile("aider_refactor_", ".java");
                Files.copy(originalFile.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                Consumer<String> viewer = openStreamingViewer(project, "Clone Refactoring Output");
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
                                notify(project, "Refactoring for file " + fileName + " was canceled.");
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
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        args.add(aiderPath);
        args.add("--model");
        args.add(model);
        args.add("--yes");
        args.add("--message");
        args.add(prompt);
        args.add(filePath);
        if (model != null && model.toLowerCase().contains("gpt-5")) {
            args.add("--no-stream");
            args.add("--check-model-accepts-settings");
            try {
                java.nio.file.Path emptySettings = java.nio.file.Files.createTempFile("aider_model_settings_", ".json");
                java.nio.file.Files.writeString(emptySettings, "{}", java.nio.charset.StandardCharsets.UTF_8);
                args.add("--model-settings-file");
                args.add(emptySettings.toString());
            } catch (IOException ioe) {
                System.err.println("[Clone] Failed to create empty model settings file: " + ioe.getMessage());
            }
        }
        return runCommand(project, provider,
                apikey,
                apiBase,
                apiVersion,
                viewer,
                args.toArray(new String[0])
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
            case "OPENAI" -> {
                pb.environment().put("OPENAI_API_KEY", apikey);
                // Ensure no conflicting providers leak into this run
                pb.environment().remove("AZURE_API_KEY");
                pb.environment().remove("AZURE_API_VERSION");
                pb.environment().remove("AZURE_API_BASE");
                pb.environment().remove("OLLAMA_API_BASE");
                // Remove any temperature-related env vars that might inject unsupported sampling
                pb.environment().remove("OPENAI_TEMPERATURE");
                pb.environment().remove("AIDER_TEMPERATURE");
                // Also remove common LiteLLM extra/default params envs if present
                pb.environment().remove("LITELLM_PARAMS");
                pb.environment().remove("LITELLM_DEFAULT_PARAMS");
                // Drop any env var whose key contains "TEMPERATURE"
                for (String k : new java.util.HashSet<>(pb.environment().keySet())) {
                    if (k != null && k.toUpperCase().contains("TEMPERATURE")) {
                        pb.environment().remove(k);
                    }
                }
            }
            case "GEMINI" -> {
                pb.environment().put("GEMINI_API_KEY", apikey);
                pb.environment().put("AIDER_GEMINI_PROVIDER", "google-ai-studio");
            }
            case "ANTHROPIC" -> pb.environment().put("ANTHROPIC_API_KEY", apikey);
            case "DEEPSEEK" -> pb.environment().put("DEEPSEEK_API_KEY", apikey);
            case "OLLAMA" -> {
                if (apiBase == null || apiBase.isBlank()) {
                    apiBase = "http://127.0.0.1:11434";
                }
                pb.environment().put("OLLAMA_API_BASE", apiBase);

                pb.environment().remove("AZURE_API_KEY");
                pb.environment().remove("AZURE_API_VERSION");
                pb.environment().remove("AZURE_API_BASE");
                pb.environment().remove("OPENAI_API_KEY");
            }
            case "AZURE" -> {
                if (apiBase == null || apiBase.isBlank()) {
                    throw new IllegalArgumentException("Azure provider selected but API base is empty");
                }
                if (apiBase.contains("11434")) {
                    System.err.println("[AIDER] Warning: Azure API base points to 11434 (Ollama). This will 404.");
                }
                pb.environment().put("AZURE_API_KEY", apikey);
                pb.environment().put("AZURE_API_VERSION", apiVersion);
                pb.environment().put("AZURE_API_BASE", apiBase);

                pb.environment().remove("OLLAMA_API_BASE");
                pb.environment().remove("OPENAI_API_KEY");
            }
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        }
        // Diagnostics: show whether per-user or per-project Aider config files exist and if they mention temperature
        try {
            java.util.List<java.io.File> cfgs = new java.util.ArrayList<>();
            String home = System.getProperty("user.home");
            String work = (pb.directory() != null ? pb.directory().getAbsolutePath() : null);
            if (home != null) {
                cfgs.add(new java.io.File(home, ".aider.conf"));
                cfgs.add(new java.io.File(home, ".aider.conf.yml"));
                cfgs.add(new java.io.File(home, ".aider.conf.yaml"));
                cfgs.add(new java.io.File(home, ".aider.conf.json"));
            }
            if (work != null) {
                cfgs.add(new java.io.File(work, ".aider.conf"));
                cfgs.add(new java.io.File(work, ".aider.conf.yml"));
                cfgs.add(new java.io.File(work, ".aider.conf.yaml"));
                cfgs.add(new java.io.File(work, ".aider.conf.json"));
            }
            for (java.io.File f : cfgs) {
                if (f != null && f.exists() && f.isFile()) {
                    String p = f.getAbsolutePath();
                    String content = java.nio.file.Files.readString(f.toPath());
                    boolean mentionsTemp = content.toLowerCase().contains("temperature");
                    String msg = "[Clone] Detected Aider config: " + p + (mentionsTemp ? " (contains 'temperature')" : "");
                    System.out.println(msg);
                    if (viewer != null) viewer.accept(msg);
                }
            }
        } catch (Throwable t) {
            System.err.println("[Clone] Config scan failed: " + t.getMessage());
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
                "Clone Refactoring",
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
                    toolWindow = twm.getToolWindow("Clone Output");
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
                        twm.getToolWindow("Clone Output")
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
                            if (title != null && title.startsWith("Clone ")) {
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
                System.err.println("Failed to close all Clone viewers: " + t.getMessage());
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
}