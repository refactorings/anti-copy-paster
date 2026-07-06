package org.jetbrains.research.anticopypaster.workflow;

import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.logStage;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.function.Consumer;
import org.jetbrains.research.anticopypaster.agents.detection;

final class WorkflowMethodSnapshotSupport {

    private WorkflowMethodSnapshotSupport() {}

    static final class CloneMethodSnapshot {
        final SmartPsiElementPointer<PsiMethod> pointer;
        final String className;
        final String methodName;
        final int parameterCount;
        final String methodKey;
        final String baselineBodyText;
        final String displayName;

        CloneMethodSnapshot(SmartPsiElementPointer<PsiMethod> pointer,
                            String className,
                            String methodName,
                            int parameterCount,
                            String methodKey,
                            String baselineBodyText,
                            String displayName) {
            this.pointer = pointer;
            this.className = className == null ? "<no-class>" : className;
            this.methodName = methodName == null ? "<unknown>" : methodName;
            this.parameterCount = parameterCount;
            this.methodKey = methodKey == null ? "" : methodKey;
            this.baselineBodyText = baselineBodyText == null ? "" : baselineBodyText;
            this.displayName = displayName == null ? "<unknown>" : displayName;
        }
    }

    static String summarizeCloneMethods(java.util.List<CloneMethodSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) return "";
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (CloneMethodSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.displayName == null || snapshot.displayName.isBlank()) continue;
            names.add(snapshot.displayName);
        }
        return String.join(" <-> ", names);
    }

    static String buildMethodDisplayName(PsiMethod method) {
        if (method == null) return "<unknown>";
        PsiClass cls = method.getContainingClass();
        String className = cls == null ? "<no-class>"
                : (cls.getQualifiedName() != null ? cls.getQualifiedName() : cls.getName());
        if (className == null || className.isBlank()) className = "<no-class>";
        return className + "#" + method.getName();
    }

    static String buildMethodTrackingKey(PsiMethod method) {
        if (method == null) return "<unknown>";
        PsiClass cls = method.getContainingClass();
        String className = cls == null ? "<no-class>"
                : (cls.getQualifiedName() != null ? cls.getQualifiedName() : cls.getName());
        if (className == null || className.isBlank()) className = "<no-class>";
        return className + "#" + method.getName() + "#" + method.getParameterList().getParametersCount();
    }

    static String buildMethodTrackingKey(String className, String methodName, int parameterCount) {
        String safeClass = (className == null || className.isBlank()) ? "<no-class>" : className;
        String safeMethod = (methodName == null || methodName.isBlank()) ? "<unknown>" : methodName;
        return safeClass + "#" + safeMethod + "#" + parameterCount;
    }

    static String getMethodClassName(PsiMethod method) {
        if (method == null) return "<no-class>";
        PsiClass cls = method.getContainingClass();
        String className = cls == null ? "<no-class>"
                : (cls.getQualifiedName() != null ? cls.getQualifiedName() : cls.getName());
        if (className == null || className.isBlank()) className = "<no-class>";
        return className;
    }

    static String buildUsefulnessMethodKey(PsiMethod method) {
        if (method == null) return "";
        String className = getMethodClassName(method);
        StringBuilder sb = new StringBuilder();
        sb.append(className).append("#").append(method.getName()).append("(");
        try {
            com.intellij.psi.PsiParameter[] params = method.getParameterList() == null
                    ? new com.intellij.psi.PsiParameter[0]
                    : method.getParameterList().getParameters();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(",");
                com.intellij.psi.PsiType type = params[i] == null ? null : params[i].getType();
                sb.append(type == null ? "" : type.getCanonicalText());
            }
        } catch (Throwable ignored) {}
        sb.append(")");
        return sb.toString();
    }

    static String getMethodBodyText(PsiMethod method) {
        if (method == null) return "";
        try {
            PsiElement body = method.getBody();
            if (body != null) {
                String text = body.getText();
                return text == null ? "" : text;
            }
            String text = method.getText();
            return text == null ? "" : text;
        } catch (Throwable t) {
            return "";
        }
    }

    static String normalizeMethodBodyText(String text) {
        if (text == null || text.isBlank()) return "";
        try {
            String s = text.replace("\r\n", "\n").replace('\r', '\n');
            s = s.replaceAll("//.*?(?=\n|$)", "");
            s = s.replaceAll("(?s)/\\*.*?\\*/", "");
            s = s.replaceAll("\\s+", " ").trim();
            return s;
        } catch (Throwable t) {
            return text;
        }
    }

    static String extractClassNameFromTrackingKey(String trackingKey) {
        if (trackingKey == null || trackingKey.isBlank()) return "";
        int idx = trackingKey.indexOf('#');
        return idx < 0 ? trackingKey : trackingKey.substring(0, idx);
    }

    static com.intellij.psi.PsiFile parseInMemoryJavaFile(Project project, String fileName, String text) {
        try {
            String effectiveName = (fileName == null || fileName.isBlank()) ? "Temp.java" : fileName;
            if (!effectiveName.endsWith(".java")) effectiveName = effectiveName + ".java";
            String effectiveText = text == null ? "" : text;
            com.intellij.psi.PsiFile psi = com.intellij.psi.PsiFileFactory.getInstance(project)
                    .createFileFromText(effectiveName, com.intellij.lang.java.JavaLanguage.INSTANCE, effectiveText, false, true);
            return (psi instanceof PsiJavaFile javaFile) ? javaFile : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static PsiMethod findMethodBySnapshot(Project project, VirtualFile vf, CloneMethodSnapshot snapshot) {
        return ReadAction.compute(() -> {
            try {
                if (project == null || project.isDisposed() || vf == null || snapshot == null) return null;

                if (snapshot.pointer != null) {
                    PsiMethod pointed = snapshot.pointer.getElement();
                    if (pointed != null && pointed.isValid()) {
                        String cls = getMethodClassName(pointed);
                        if (java.util.Objects.equals(snapshot.className, cls)
                                && java.util.Objects.equals(snapshot.methodName, pointed.getName())
                                && snapshot.parameterCount == pointed.getParameterList().getParametersCount()) {
                            return pointed;
                        }
                    }
                }

                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (!(psiFile instanceof PsiJavaFile javaFile)) return null;

                for (PsiClass psiClass : javaFile.getClasses()) {
                    String cls = psiClass.getQualifiedName() != null ? psiClass.getQualifiedName() : psiClass.getName();
                    if (!java.util.Objects.equals(snapshot.className, cls)) continue;
                    for (PsiMethod method : psiClass.getMethods()) {
                        if (!java.util.Objects.equals(snapshot.methodName, method.getName())) continue;
                        if (snapshot.parameterCount != method.getParameterList().getParametersCount()) continue;
                        return method;
                    }
                }
                return null;
            } catch (Throwable t) {
                return null;
            }
        });
    }

    static PsiMethod findMethodContainingLine(Project project, VirtualFile vf, int oneBasedLine) {
        return ReadAction.compute(() -> {
            try {
                if (project == null || project.isDisposed() || vf == null) return null;
                if (oneBasedLine <= 0) return null;

                Document doc = FileDocumentManager.getInstance().getDocument(vf);
                if (doc == null) return null;
                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (!(psiFile instanceof PsiJavaFile)) return null;
                if (doc.getLineCount() <= 0) return null;

                int zeroBasedLine = Math.max(0, Math.min(oneBasedLine - 1, doc.getLineCount() - 1));
                int startOffset = doc.getLineStartOffset(zeroBasedLine);
                int endOffset = Math.max(startOffset, doc.getLineEndOffset(zeroBasedLine) - 1);

                PsiElement at = psiFile.findElementAt(startOffset);
                if (at == null) at = psiFile.findElementAt(endOffset);
                if (at == null) return null;

                return PsiTreeUtil.getParentOfType(at, PsiMethod.class, false);
            } catch (Throwable t) {
                return null;
            }
        });
    }

    static PsiMethod findMethodContainingCloneRange(Project project, VirtualFile vf, detection.CloneRange range) {
        return ReadAction.compute(() -> {
            if (range == null) return null;
            PsiMethod start = findMethodContainingLine(project, vf, range.startLine);
            PsiMethod end = findMethodContainingLine(project, vf, range.endLine);
            if (start != null && end != null) {
                String startKey = buildMethodTrackingKey(start);
                String endKey = buildMethodTrackingKey(end);
                if (startKey.equals(endKey)) return start;
            }
            return start != null ? start : end;
        });
    }

    static void addCloneMethodSnapshot(java.util.Map<String, CloneMethodSnapshot> out,
                                       Project project,
                                       PsiMethod method,
                                       Consumer<String> viewer) {
        try {
            if (out == null || project == null || project.isDisposed() || method == null) return;

            String key = buildMethodTrackingKey(method);
            if (out.containsKey(key)) return;

            SmartPsiElementPointer<PsiMethod> ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method);
            String displayName = buildMethodDisplayName(method);
            String className = getMethodClassName(method);
            String methodName = method.getName();
            int parameterCount = method.getParameterList().getParametersCount();
            String exactMethodKey = buildUsefulnessMethodKey(method);
            String baselineBodyText = normalizeMethodBodyText(getMethodBodyText(method));
            out.put(key, new CloneMethodSnapshot(ptr, className, methodName, parameterCount, exactMethodKey, baselineBodyText, displayName));
            logStage(viewer, "WATCH", "tracking cloned method: " + displayName);
        } catch (Throwable t) {
            logStage(viewer, "WATCH", "failed to snapshot cloned method: " + t.getMessage());
        }
    }

    static java.util.List<CloneMethodSnapshot> captureCloneMethodSnapshots(Project project,
                                                                            VirtualFile vf,
                                                                            detection.DetectedClone clone,
                                                                            Consumer<String> viewer) {
        return ReadAction.compute(() -> {
            java.util.LinkedHashMap<String, CloneMethodSnapshot> out = new java.util.LinkedHashMap<>();
            try {
                if (project == null || project.isDisposed() || vf == null || clone == null || clone.ranges == null) {
                    return new java.util.ArrayList<>();
                }

                for (detection.CloneRange range : clone.ranges) {
                    if (range == null) continue;

                    PsiMethod method = findMethodContainingLine(project, vf, range.startLine);
                    if (method == null) method = findMethodContainingLine(project, vf, range.endLine);
                    if (method == null) continue;

                    String key = buildMethodTrackingKey(method);
                    if (out.containsKey(key)) continue;
                    SmartPsiElementPointer<PsiMethod> ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method);
                    String displayName = buildMethodDisplayName(method);
                    String className = getMethodClassName(method);
                    String methodName = method.getName();
                    int parameterCount = method.getParameterList().getParametersCount();
                    String methodKey = buildUsefulnessMethodKey(method);
                    String baselineBodyText = normalizeMethodBodyText(getMethodBodyText(method));
                    out.put(key, new CloneMethodSnapshot(ptr, className, methodName, parameterCount, methodKey, baselineBodyText, displayName));
                    logStage(viewer, "WATCH", "tracking cloned method: " + displayName);
                }
            } catch (Throwable t) {
                logStage(viewer, "WATCH", "failed to capture cloned method snapshots: " + t.getMessage());
            }
            return new java.util.ArrayList<>(out.values());
        });
    }

    static java.util.List<org.jetbrains.research.anticopypaster.agents.usefulnessChecker.TargetMethodHint> buildUsefulnessTargetMethodHints(
            PsiMethod wholeMethod,
            java.util.List<CloneMethodSnapshot> watchedCloneMethods,
            Consumer<String> viewer
    ) {
        return ReadAction.compute(() -> {
            java.util.LinkedHashMap<String, org.jetbrains.research.anticopypaster.agents.usefulnessChecker.TargetMethodHint> out = new java.util.LinkedHashMap<>();
            try {
                if (wholeMethod != null) {
                    org.jetbrains.research.anticopypaster.agents.usefulnessChecker.TargetMethodHint hint =
                            new org.jetbrains.research.anticopypaster.agents.usefulnessChecker.TargetMethodHint(
                                    getMethodClassName(wholeMethod),
                                    wholeMethod.getName(),
                                    wholeMethod.getParameterList() == null ? 0 : wholeMethod.getParameterList().getParametersCount(),
                                    buildUsefulnessMethodKey(wholeMethod)
                            );
                    out.put(hint.methodKey.isBlank()
                            ? (hint.className + "#" + hint.methodName + "#" + hint.parameterCount)
                            : hint.methodKey, hint);
                }

                if (watchedCloneMethods != null) {
                    for (CloneMethodSnapshot snapshot : watchedCloneMethods) {
                        if (snapshot == null) continue;
                        org.jetbrains.research.anticopypaster.agents.usefulnessChecker.TargetMethodHint hint =
                                new org.jetbrains.research.anticopypaster.agents.usefulnessChecker.TargetMethodHint(
                                        snapshot.className,
                                        snapshot.methodName,
                                        snapshot.parameterCount,
                                        snapshot.methodKey
                                );
                        out.put(hint.methodKey.isBlank()
                                ? (hint.className + "#" + hint.methodName + "#" + hint.parameterCount)
                                : hint.methodKey, hint);
                    }
                }
            } catch (Throwable t) {
                logStage(viewer, "USEFUL", "failed to build target method hints: " + t.getMessage());
            }

            java.util.ArrayList<org.jetbrains.research.anticopypaster.agents.usefulnessChecker.TargetMethodHint> hints =
                    new java.util.ArrayList<>(out.values());
            if (hints.size() >= 2) {
                String joined = hints.stream()
                        .map(h -> h.methodKey == null || h.methodKey.isBlank()
                                ? (h.className + "#" + h.methodName + "#" + h.parameterCount)
                                : h.methodKey)
                        .collect(java.util.stream.Collectors.joining(", "));
                logStage(viewer, "USEFUL", "target method hints=" + hints.size() + ": " + joined);
                return hints;
            }

            if (!hints.isEmpty()) {
                logStage(viewer, "USEFUL", "insufficient target method hints for target-only analysis: " + hints.size());
            }
            return java.util.List.of();
        });
    }

    static String findModifiedCloneMethod(Project project,
                                          VirtualFile vf,
                                          java.util.List<CloneMethodSnapshot> snapshots) {
        return ReadAction.compute(() -> {
            try {
                if (snapshots == null || snapshots.isEmpty()) return null;
                for (CloneMethodSnapshot snapshot : snapshots) {
                    if (snapshot == null) continue;

                    PsiMethod method = findMethodBySnapshot(project, vf, snapshot);
                    if (method == null || !method.isValid()) {
                        continue;
                    }

                    String currentBodyText = normalizeMethodBodyText(getMethodBodyText(method));
                    if (!java.util.Objects.equals(snapshot.baselineBodyText, currentBodyText)) {
                        return snapshot.displayName;
                    }
                }
                return null;
            } catch (Throwable t) {
                return null;
            }
        });
    }
}
