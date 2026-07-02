package org.jetbrains.research.anticopypaster.workflow;

import static org.jetbrains.research.anticopypaster.workflow.WorkflowReasonSupport.containsReasonName;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowReasonSupport.previewOneLine;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import java.io.File;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.research.anticopypaster.agents.LlmUsefulnessEvaluator;
import org.jetbrains.research.anticopypaster.agents.compilation;
import org.jetbrains.research.anticopypaster.agents.detection;
import org.jetbrains.research.anticopypaster.agents.usefulnessChecker;

final class WorkflowUsefulnessFeedbackSupport {

    private WorkflowUsefulnessFeedbackSupport() {}

    static String buildFocusedFeedbackRefactoredCode(com.intellij.openapi.project.Project project,
                                                     String fileName,
                                                     String beforeSource,
                                                     String afterSource,
                                                     java.util.List<WorkflowMethodSnapshotSupport.CloneMethodSnapshot> snapshots) {
        String fullSource = afterSource == null ? "" : afterSource;
        try {
            if (project == null || project.isDisposed() || fullSource.isBlank()) return fullSource;

            com.intellij.psi.PsiFile afterPsiFile = WorkflowMethodSnapshotSupport.parseInMemoryJavaFile(project, fileName, fullSource);
            if (!(afterPsiFile instanceof PsiJavaFile afterPsi)) return fullSource;
            com.intellij.psi.PsiFile beforePsiFile = WorkflowMethodSnapshotSupport.parseInMemoryJavaFile(project, fileName, beforeSource == null ? "" : beforeSource);

            java.util.LinkedHashMap<String, PsiMethod> afterMethods = collectAllMethodsByUsefulnessKey(afterPsi);
            if (afterMethods.isEmpty()) return fullSource;

            java.util.LinkedHashMap<String, PsiMethod> beforeMethods =
                    (beforePsiFile instanceof PsiJavaFile bPsi)
                            ? collectAllMethodsByUsefulnessKey(bPsi)
                            : new java.util.LinkedHashMap<>();

            java.util.LinkedHashSet<String> targetKeys = collectTargetMethodUsefulnessKeys(snapshots);
            if (targetKeys.isEmpty()) return fullSource;

            java.util.LinkedHashSet<String> addedKeys = new java.util.LinkedHashSet<>(afterMethods.keySet());
            addedKeys.removeAll(beforeMethods.keySet());

            java.util.LinkedHashSet<String> helperKeys = collectRelevantHelperMethodKeys(afterMethods, targetKeys, addedKeys);

            StringBuilder sb = new StringBuilder();
            appendFocusedMethodSection(sb, "Target clone methods", targetKeys, afterMethods, snapshots, false);
            appendFocusedMethodSection(sb, "New helper methods", helperKeys, afterMethods, snapshots, true);

            String focused = sb.toString().trim();
            return focused.isEmpty() ? fullSource : focused;
        } catch (Throwable t) {
            return fullSource;
        }
    }

    static String buildUsefulnessFeedbackPrompt(com.intellij.openapi.project.Project project,
                                                String fileName,
                                                String proposedSource,
                                                java.util.List<usefulnessChecker.Reason> reasons,
                                                java.util.List<WorkflowMethodSnapshotSupport.CloneMethodSnapshot> snapshots) {
        if (!containsReasonName(reasons, "POST_EXTRACTION_CLONE_DELETION_DETECTED")) {
            return usefulnessChecker.buildFeedbackPrompt(reasons);
        }

        java.util.List<String> missingMethods = findMissingTargetMethodDisplayNames(project, fileName, proposedSource, snapshots);
        StringBuilder missingText = new StringBuilder();
        if (!missingMethods.isEmpty()) {
            missingText.append("\n\nMissing target clone methods in the proposed source:\n");
            for (String name : missingMethods) {
                if (name == null || name.isBlank()) continue;
                missingText.append("- ").append(name).append("\n");
            }
            while (missingText.length() > 0 && Character.isWhitespace(missingText.charAt(missingText.length() - 1))) {
                missingText.setLength(missingText.length() - 1);
            }
        }

        return """
Your previous refactoring was rejected because one or more target clone methods were deleted after extraction.

Problem:
A valid Extract Method refactoring must preserve all original target clone methods.%s

How to fix it:
- Restore every missing target clone method.
- Keep the helper method.
- Make all original target clone methods call the helper.

Important constraints:
- Do not delete target clone methods.
- Do not merge them.
- Only perform Extract Method.
Follow the required output format for the refactoring task.
""".formatted(missingText);
    }

    static LlmUsefulnessEvaluator.UsefulnessInput buildLlmUsefulnessInput(
            com.intellij.openapi.project.Project project,
            String fileName,
            detection.DetectedClone clone,
            String beforeSource,
            String afterSource,
            java.util.List<WorkflowMethodSnapshotSupport.CloneMethodSnapshot> snapshots,
            boolean wholeMethodClone) {
        String cloneContext = buildLlmUsefulnessCloneContext(clone, snapshots);
        String focusedBeforeCode = buildFocusedFeedbackRefactoredCode(project, fileName, beforeSource, beforeSource, snapshots);
        String focusedAfterCode = buildFocusedFeedbackRefactoredCode(project, fileName, beforeSource, afterSource, snapshots);
        return new LlmUsefulnessEvaluator.UsefulnessInput(
                fileName,
                wholeMethodClone
                        ? LlmUsefulnessEvaluator.CloneKind.WHOLE_METHOD
                        : LlmUsefulnessEvaluator.CloneKind.FRAGMENT,
                cloneContext,
                focusedBeforeCode,
                focusedAfterCode
        );
    }

    static String buildLlmUsefulnessCloneContext(detection.DetectedClone clone,
                                                 java.util.List<WorkflowMethodSnapshotSupport.CloneMethodSnapshot> snapshots) {
        StringBuilder sb = new StringBuilder();
        sb.append("Clone ID: ").append(clone == null || clone.id == null || clone.id.isBlank()
                ? "<unknown>"
                : clone.id).append("\n");

        String methodSummary = WorkflowMethodSnapshotSupport.summarizeCloneMethods(snapshots);
        if (!methodSummary.isBlank()) {
            sb.append("Target methods: ").append(methodSummary).append("\n");
        }

        String rangeSummary = WorkflowCloneSelectionSupport.summarizeCloneRanges(clone == null ? null : clone.ranges);
        if (!rangeSummary.isBlank()) {
            sb.append("Selected ranges: ").append(rangeSummary).append("\n");
        }

        if (clone != null && clone.refactorType != null && !clone.refactorType.isBlank()) {
            sb.append("Requested refactor type: ").append(clone.refactorType).append("\n");
        }

        String reasonPreview = previewOneLine(clone == null ? "" : clone.reason, 400);
        if (reasonPreview != null && !reasonPreview.isBlank()) {
            sb.append("Detection reason preview: ").append(reasonPreview).append("\n");
        }

        java.util.List<String> cloneCodes = WorkflowCloneSelectionSupport.getDetectedCloneCodes(clone, null);
        String[] ab = WorkflowCloneSelectionSupport.extractCloneCodeABFromReason(clone == null ? null : clone.reason);
        String cloneCodeA = !cloneCodes.isEmpty()
                ? WorkflowCloneSelectionSupport.firstNonBlank(cloneCodes.get(0), ab[0])
                : WorkflowCloneSelectionSupport.firstNonBlank(clone == null ? null : clone.cloneCodeA, ab[0]);
        String cloneCodeB = cloneCodes.size() > 1
                ? WorkflowCloneSelectionSupport.firstNonBlank(cloneCodes.get(1), ab[1])
                : WorkflowCloneSelectionSupport.firstNonBlank(clone == null ? null : clone.cloneCodeB, ab[1]);

        String cloneAPreview = previewOneLine(cloneCodeA, 200);
        if (cloneAPreview != null && !cloneAPreview.isBlank()) {
            sb.append("Clone A preview: ").append(cloneAPreview).append("\n");
        }

        String cloneBPreview = previewOneLine(cloneCodeB, 200);
        if (cloneBPreview != null && !cloneBPreview.isBlank()) {
            sb.append("Clone B preview: ").append(cloneBPreview).append("\n");
        }

        return sb.toString().trim();
    }

    static String buildLlmUsefulnessFeedbackSection(LlmUsefulnessEvaluator.EvaluationResult evaluation) {
        if (evaluation == null || evaluation.curatorResult == null || !evaluation.curatorResult.parsed) {
            return "";
        }

        LlmUsefulnessEvaluator.CuratorResult curator = evaluation.curatorResult;
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n[LLM_USEFULNESS]\n");
        sb.append("Curator decision: ").append(curator.useful ? "USEFUL" : "NOT_USEFUL").append("\n");
        if (!curator.reasons.isEmpty()) {
            sb.append("Reasons: ").append(curator.reasons).append("\n");
        }
        if (!curator.summary.isBlank()) {
            sb.append("Summary: ").append(curator.summary).append("\n");
        }
        if (!curator.feedback.isBlank()) {
            sb.append("Feedback: ").append(curator.feedback).append("\n");
        }
        if (curator.confidence > 0.0d) {
            sb.append("Confidence: ").append(String.format(Locale.ROOT, "%.2f", curator.confidence)).append("\n");
        }
        if (evaluation.notes != null && !evaluation.notes.isBlank()) {
            sb.append("Notes: ").append(evaluation.notes).append("\n");
        }
        return sb.toString().trim();
    }

    static String mergeRevisionInstructions(String primary, String secondary) {
        String first = primary == null ? "" : primary.strip();
        String second = secondary == null ? "" : secondary.strip();
        if (first.isBlank()) return second;
        if (second.isBlank()) return first;
        if (first.equals(second)) return first;
        return first + "\n\nAdditional PSI guidance:\n" + second;
    }

    static compilation.CompileResult ignoreBaselineCompileErrors(String targetFileName,
                                                                 compilation.CompileResult proposal,
                                                                 compilation.CompileResult baseline) {
        if (proposal == null || "compile_ok".equals(proposal.status)) return proposal;
        if (proposal.errors == null || proposal.errors.isEmpty()) return proposal;
        if (baseline == null || baseline.errors == null || baseline.errors.isEmpty()) return proposal;

        java.util.Set<String> baselineKeys = new java.util.LinkedHashSet<>();
        for (compilation.CompileError error : baseline.errors) {
            String key = compileErrorKey(targetFileName, error);
            if (key != null && !key.isBlank()) {
                baselineKeys.add(key);
            }
        }
        if (baselineKeys.isEmpty()) return proposal;

        java.util.List<compilation.CompileError> newErrors = new java.util.ArrayList<>();
        for (compilation.CompileError error : proposal.errors) {
            String key = compileErrorKey(targetFileName, error);
            if (key == null || key.isBlank() || !baselineKeys.contains(key)) {
                newErrors.add(error);
            }
        }

        if (newErrors.isEmpty()) {
            String summary = "Compilation produced only " + proposal.errors.size() +
                    " pre-existing baseline error(s); ignoring them for this proposal.";
            return new compilation.CompileResult("compile_ok", proposal.buildTool, proposal.errors, summary);
        }

        String first = newErrors.get(0).message != null ? newErrors.get(0).message : "(no message)";
        String summary = String.format(
                Locale.ROOT,
                "Compilation failed with %d new error(s). First: %s",
                newErrors.size(),
                first
        );
        return new compilation.CompileResult(proposal.status, proposal.buildTool, newErrors, summary);
    }

    static String compileErrorKey(String targetFileName, compilation.CompileError error) {
        if (error == null) return "";
        String file = normalizeCompileErrorFile(targetFileName, error.file);
        String message = error.message != null ? error.message : error.raw;
        message = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        if (file.isBlank() && message.isBlank()) return "";
        String line = "";
        if (!isTargetCompileErrorFile(targetFileName, error.file) && error.line != null) {
            line = ":" + error.line;
        }
        return file + line + "|" + message;
    }

    static String normalizeCompileErrorFile(String targetFileName, String file) {
        if (file == null || file.isBlank()) return "";
        String normalized = file.replace('\\', '/').trim();
        int slash = normalized.lastIndexOf('/');
        String basename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        String targetBasename = targetFileName == null ? "" : new File(targetFileName).getName();
        if (!targetBasename.isBlank() && targetBasename.equals(basename)) {
            return basename;
        }
        return normalized;
    }

    static boolean isTargetCompileErrorFile(String targetFileName, String file) {
        if (file == null || file.isBlank()) return false;
        String normalized = file.replace('\\', '/').trim();
        int slash = normalized.lastIndexOf('/');
        String basename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        String targetBasename = targetFileName == null ? "" : new File(targetFileName).getName();
        return !targetBasename.isBlank() && targetBasename.equals(basename);
    }

    static void appendFocusedMethodSection(StringBuilder sb,
                                           String title,
                                           java.util.LinkedHashSet<String> keys,
                                           Map<String, PsiMethod> afterMethods,
                                           java.util.List<WorkflowMethodSnapshotSupport.CloneMethodSnapshot> snapshots,
                                           boolean helperSection) {
        if (sb == null || keys == null || keys.isEmpty()) return;

        if (sb.length() > 0) sb.append("\n\n");
        sb.append("// ").append(title).append("\n");

        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            PsiMethod method = afterMethods == null ? null : afterMethods.get(key);
            if (method == null) {
                if (!helperSection) {
                    String displayName = findSnapshotDisplayName(snapshots, key);
                    sb.append("// Missing in proposed source: ").append(displayName == null ? key : displayName).append("\n");
                }
                continue;
            }

            String displayName = WorkflowMethodSnapshotSupport.buildMethodDisplayName(method);
            if (displayName != null && !displayName.isBlank()) {
                sb.append("// ").append(displayName).append("\n");
            }
            sb.append(method.getText().strip()).append("\n\n");
        }

        while (sb.length() > 0 && Character.isWhitespace(sb.charAt(sb.length() - 1))) {
            sb.setLength(sb.length() - 1);
        }
    }

    static java.util.LinkedHashSet<String> collectTargetMethodUsefulnessKeys(
            java.util.List<WorkflowMethodSnapshotSupport.CloneMethodSnapshot> snapshots) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (snapshots == null || snapshots.isEmpty()) return out;
        for (WorkflowMethodSnapshotSupport.CloneMethodSnapshot snapshot : snapshots) {
            if (snapshot == null) continue;
            if (snapshot.methodKey != null && !snapshot.methodKey.isBlank()) {
                out.add(snapshot.methodKey);
            }
        }
        return out;
    }

    static java.util.List<String> findMissingTargetMethodDisplayNames(
            com.intellij.openapi.project.Project project,
            String fileName,
            String proposedSource,
            java.util.List<WorkflowMethodSnapshotSupport.CloneMethodSnapshot> snapshots) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        try {
            if (project == null || project.isDisposed() || proposedSource == null || proposedSource.isBlank()) return out;
            java.util.LinkedHashSet<String> targetKeys = collectTargetMethodUsefulnessKeys(snapshots);
            if (targetKeys.isEmpty()) return out;

            com.intellij.psi.PsiFile afterPsiFile = WorkflowMethodSnapshotSupport.parseInMemoryJavaFile(project, fileName, proposedSource);
            if (!(afterPsiFile instanceof PsiJavaFile afterPsi)) return out;
            java.util.LinkedHashMap<String, PsiMethod> afterMethods = collectAllMethodsByUsefulnessKey(afterPsi);
            for (String key : targetKeys) {
                if (key == null || key.isBlank()) continue;
                if (afterMethods.containsKey(key)) continue;
                String displayName = findSnapshotDisplayName(snapshots, key);
                out.add((displayName == null || displayName.isBlank()) ? key : displayName);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static java.util.LinkedHashSet<String> collectRelevantHelperMethodKeys(
            Map<String, PsiMethod> afterMethods,
            java.util.Set<String> targetKeys,
            java.util.Set<String> addedKeys) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (afterMethods == null || afterMethods.isEmpty() || targetKeys == null || targetKeys.isEmpty()
                || addedKeys == null || addedKeys.isEmpty()) {
            return out;
        }

        for (String targetKey : targetKeys) {
            PsiMethod targetMethod = afterMethods.get(targetKey);
            if (targetMethod == null) continue;
            out.addAll(collectCalledAddedMethodKeys(targetMethod, addedKeys));
        }
        if (!out.isEmpty()) return out;

        java.util.LinkedHashSet<String> targetClasses = new java.util.LinkedHashSet<>();
        for (String targetKey : targetKeys) {
            String className = extractClassNameFromUsefulnessKey(targetKey);
            if (className != null && !className.isBlank()) targetClasses.add(className);
        }

        for (String addedKey : addedKeys) {
            if (targetClasses.contains(extractClassNameFromUsefulnessKey(addedKey))) {
                out.add(addedKey);
            }
        }
        return out;
    }

    static java.util.LinkedHashSet<String> collectCalledAddedMethodKeys(PsiMethod method,
                                                                        java.util.Set<String> addedKeys) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        try {
            if (method == null || addedKeys == null || addedKeys.isEmpty()) return out;
            PsiCodeBlock body = method.getBody();
            if (body == null) return out;

            java.util.Collection<PsiMethodCallExpression> calls =
                    PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class);
            if (calls == null || calls.isEmpty()) return out;

            for (PsiMethodCallExpression call : calls) {
                PsiMethod resolved = null;
                try {
                    resolved = call.resolveMethod();
                } catch (Throwable ignored) {
                }

                String key = null;
                if (resolved != null) {
                    key = WorkflowMethodSnapshotSupport.buildUsefulnessMethodKey(resolved);
                }
                if ((key == null || key.isBlank()) && call.getMethodExpression() != null) {
                    String name = call.getMethodExpression().getReferenceName();
                    int arity = call.getArgumentList() == null ? 0 : call.getArgumentList().getExpressionCount();
                    key = findAddedMethodKeyByNameAndArity(addedKeys, name, arity);
                }

                if (key != null && addedKeys.contains(key)) out.add(key);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static String findAddedMethodKeyByNameAndArity(java.util.Set<String> addedKeys,
                                                   String methodName,
                                                   int parameterCount) {
        if (addedKeys == null || addedKeys.isEmpty() || methodName == null || methodName.isBlank()) return null;
        for (String key : addedKeys) {
            if (key == null || key.isBlank()) continue;
            if (!methodName.equals(extractMethodNameFromUsefulnessKey(key))) continue;
            if (extractParameterCountFromUsefulnessKey(key) == parameterCount) return key;
        }
        return null;
    }

    static String findSnapshotDisplayName(java.util.List<WorkflowMethodSnapshotSupport.CloneMethodSnapshot> snapshots,
                                          String trackingKey) {
        if (snapshots == null || snapshots.isEmpty() || trackingKey == null || trackingKey.isBlank()) return trackingKey;
        for (WorkflowMethodSnapshotSupport.CloneMethodSnapshot snapshot : snapshots) {
            if (snapshot == null) continue;
            if (trackingKey.equals(snapshot.methodKey)) return snapshot.displayName;
            String fallbackKey = WorkflowMethodSnapshotSupport.buildMethodTrackingKey(
                    snapshot.className, snapshot.methodName, snapshot.parameterCount);
            if (trackingKey.equals(fallbackKey)) return snapshot.displayName;
        }
        return trackingKey;
    }

    static java.util.LinkedHashMap<String, PsiMethod> collectAllMethodsByUsefulnessKey(PsiJavaFile javaFile) {
        java.util.LinkedHashMap<String, PsiMethod> out = new java.util.LinkedHashMap<>();
        if (javaFile == null) return out;
        PsiClass[] classes = javaFile.getClasses();
        if (classes == null) return out;
        for (PsiClass psiClass : classes) {
            collectMethodsByUsefulnessKeyRecursive(psiClass, out);
        }
        return out;
    }

    static void collectMethodsByUsefulnessKeyRecursive(PsiClass psiClass,
                                                       java.util.LinkedHashMap<String, PsiMethod> out) {
        if (psiClass == null || out == null) return;
        for (PsiMethod method : psiClass.getMethods()) {
            String key = WorkflowMethodSnapshotSupport.buildUsefulnessMethodKey(method);
            if (key != null && !key.isBlank()) out.putIfAbsent(key, method);
        }
        for (PsiClass inner : psiClass.getInnerClasses()) {
            collectMethodsByUsefulnessKeyRecursive(inner, out);
        }
    }

    static String extractClassNameFromUsefulnessKey(String usefulnessKey) {
        if (usefulnessKey == null || usefulnessKey.isBlank()) return "";
        int idx = usefulnessKey.indexOf('#');
        return idx < 0 ? usefulnessKey : usefulnessKey.substring(0, idx);
    }

    static String extractMethodNameFromUsefulnessKey(String usefulnessKey) {
        if (usefulnessKey == null || usefulnessKey.isBlank()) return "";
        int hashIdx = usefulnessKey.indexOf('#');
        int parenIdx = usefulnessKey.indexOf('(');
        if (hashIdx < 0 || parenIdx < hashIdx) return "";
        return usefulnessKey.substring(hashIdx + 1, parenIdx);
    }

    static int extractParameterCountFromUsefulnessKey(String usefulnessKey) {
        if (usefulnessKey == null || usefulnessKey.isBlank()) return -1;
        int start = usefulnessKey.indexOf('(');
        int end = usefulnessKey.lastIndexOf(')');
        if (start < 0 || end < start) return -1;
        String params = usefulnessKey.substring(start + 1, end).trim();
        if (params.isEmpty()) return 0;
        int count = 1;
        int genericDepth = 0;
        for (int i = 0; i < params.length(); i++) {
            char ch = params.charAt(i);
            if (ch == '<') genericDepth++;
            else if (ch == '>' && genericDepth > 0) genericDepth--;
            else if (ch == ',' && genericDepth == 0) count++;
        }
        return count;
    }

    static boolean looksLikeValidExtractMethodDelegation(String beforeSource, String afterSource,
                                                         String wrapperA, String wrapperB) {
        if (beforeSource == null || afterSource == null) return false;
        if (!afterSource.contains("private") || !afterSource.contains("(")) return false;
        if (wrapperA == null || wrapperA.isBlank() || wrapperB == null || wrapperB.isBlank()) return false;

        String helperA = findDelegationTarget(afterSource, wrapperA);
        String helperB = findDelegationTarget(afterSource, wrapperB);
        if (helperA == null || helperB == null) return false;
        if (!helperA.equals(helperB)) return false;
        if (!containsMethodDeclaration(afterSource, helperA)) return false;
        if (containsMethodDeclaration(beforeSource, helperA)) return false;

        String helperBody = extractMethodBody(afterSource, helperA);
        if (helperBody == null) return false;
        return helperBody.trim().length() >= 80;
    }

    static String findDelegationTarget(String source, String methodName) {
        if (source == null || methodName == null) return null;
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "(?s)\\b" + java.util.regex.Pattern.quote(methodName) + "\\s*\\([^)]*\\)\\s*\\{(.*?)\\}");
            java.util.regex.Pattern p2 = java.util.regex.Pattern.compile(
                    "(?s)\\b" + java.util.regex.Pattern.quote(methodName) + "\\s*\\([^)]*\\)\\s*\\{(.*)\\n\\s*\\}");
            java.util.regex.Matcher m = p.matcher(source);
            if (!m.find()) {
                m = p2.matcher(source);
                if (!m.find()) return null;
            }
            String body = m.group(1);
            if (body == null) return null;
            java.util.regex.Matcher r = java.util.regex.Pattern.compile(
                    "\\breturn\\s+([A-Za-z_][\\w]*)\\s*\\(").matcher(body);
            if (r.find()) return r.group(1);
            java.util.regex.Matcher c = java.util.regex.Pattern.compile(
                    "\\b([A-Za-z_][\\w]*)\\s*\\(").matcher(body);
            if (c.find()) return c.group(1);
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean containsMethodDeclaration(String source, String methodName) {
        if (source == null || methodName == null) return false;
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "(?m)^(?:\\s*(?:public|protected|private|static|final|synchronized|abstract)\\s+)*[A-Za-z_][\\w<>\\[\\]]*\\s+"
                            + java.util.regex.Pattern.quote(methodName) + "\\s*\\(");
            return p.matcher(source).find();
        } catch (Throwable t) {
            return false;
        }
    }

    static String extractMethodBody(String source, String methodName) {
        if (source == null || methodName == null) return null;
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "(?s)\\b" + java.util.regex.Pattern.quote(methodName) + "\\s*\\([^)]*\\)\\s*\\{(.*?)\\}");
            java.util.regex.Matcher m = p.matcher(source);
            if (!m.find()) return null;
            return m.group(1);
        } catch (Throwable t) {
            return null;
        }
    }
}
