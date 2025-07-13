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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

public class AiderHelper {

    public static void checkAndSuggestRefactor(Project project, VirtualFile file, String provider, String model, String apikey, String aiderPath) {
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
                    String output = runAiderWithPrompt(project, aiderPath, tempFilePath,
                            "Please detect any clones in this file. Response with either 'clones found' or 'no clones found'", provider, model, apikey);

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
                                runRefactorWithPreview(project, fileName, filePath, provider, model, apikey, aiderPath);
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

    private static void runRefactorWithPreview(Project project, String fileName, String filePath, String provider, String model, String apikey, String aiderPath) {
        notify(project, "Aider is running code refactoring on " + fileName + "...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                File originalFile = new File(filePath);
                File tempFile = File.createTempFile("aider_refactor_", ".java");
                Files.copy(originalFile.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                String output = runAiderWithPrompt(project, aiderPath, tempFile.getAbsolutePath(),
                        "Please refactor this file by Extraction Method to eliminate clones.", provider, model, apikey);
                System.out.println("===> Refactor output:\n" + output);

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
                        } catch (InterruptedException ignored) {}

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
                    notify(project, "No meaningful changes in refactored code for file " + fileName + ".");
                }

            } catch (Exception e) {
                notify(project, "Refactor failed for file " + fileName + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private static boolean containsDuplicateHint(String output) {
        String normalized = output.toLowerCase().trim();
        return normalized.contains("clones found") && !normalized.contains("no clones found");
    }

    public static String runAiderWithPrompt(Project project, String aiderPath, String filePath, String prompt, String provider, String model, String apikey) throws IOException, InterruptedException {
        if (model.startsWith("deepseek-")) {
            model = "deepseek/" + model;
        }
        return runCommand(project, provider,
                apikey,
                aiderPath,
                "--model", model,
                "--yes",
                "--message", prompt,
                filePath
        );
    }

    private static String runCommand(Project project, String provider, String apikey, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        switch (provider.toUpperCase()) {
            case "OPENAI" -> pb.environment().put("OPENAI_API_KEY", apikey);
            case "GEMINI" -> {
                pb.environment().put("GEMINI_API_KEY", apikey);
                pb.environment().put("AIDER_GEMINI_PROVIDER", "google-ai-studio");
            }
            case "ANTHROPIC" -> pb.environment().put("ANTHROPIC_API_KEY", apikey);
            case "DEEPSEEK" -> pb.environment().put("DEEPSEEK_API_KEY", apikey);
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        }

        Process process = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println("[AIDER] " + line);
            output.append(line).append("\n");
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

    private static void notify(Project project, String content) {
        Notification notification = new Notification(
                "AiderRefactor",
                "Aider Refactoring",
                content,
                NotificationType.INFORMATION
        );
        Notifications.Bus.notify(notification, project);
    }
}