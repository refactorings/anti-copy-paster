package org.jetbrains.research.anticopypaster.workflow;

import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.logStage;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.function.Consumer;
import org.jetbrains.research.anticopypaster.agents.detection;

final class WorkflowCloneRangeSupport {

    private WorkflowCloneRangeSupport() {}

    static final class MergedCloneOccurrence {
        final detection.CloneRange range;
        String code;

        MergedCloneOccurrence(detection.CloneRange range, String code) {
            this.range = range;
            this.code = code == null ? "" : code;
        }
    }

    static final class PsiRangeCandidate {
        final detection.CloneRange range;
        final int distanceScore;
        final int spanScore;

        PsiRangeCandidate(detection.CloneRange range, int distanceScore, int spanScore) {
            this.range = range;
            this.distanceScore = distanceScore;
            this.spanScore = spanScore;
        }
    }

    static String normalizeForMatch(String s) {
        if (s == null) return "";
        String t = s.replace("\r\n", "\n").replace("\r", "\n").trim();
        t = t.replaceAll("\\s+", " ");
        return t;
    }

    static String stripOuterBraces(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("{") && t.endsWith("}")) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    static int[] findSnippetLineRangeInText(String fileSource, String pastedSnippet) {
        try {
            if (fileSource == null || pastedSnippet == null) return null;
            if (pastedSnippet.isBlank()) return null;

            int idx = fileSource.indexOf(pastedSnippet);
            if (idx < 0) return null;

            int startLine = 1;
            for (int i = 0; i < idx; i++) {
                if (fileSource.charAt(i) == '\n') startLine++;
            }

            int endIdx = Math.min(fileSource.length(), idx + pastedSnippet.length());
            int endLine = startLine;
            for (int i = idx; i < endIdx; i++) {
                if (fileSource.charAt(i) == '\n') endLine++;
            }

            return new int[]{startLine, endLine};
        } catch (Throwable t) {
            return null;
        }
    }

    static int[] elementLineRange(Project project, VirtualFile vf, PsiElement el) {
        try {
            if (project == null || project.isDisposed() || vf == null || el == null) return null;
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) return null;

            int startOffset = Math.max(0, el.getTextRange().getStartOffset());
            int endOffset = Math.max(startOffset, el.getTextRange().getEndOffset());

            int startLine = doc.getLineNumber(startOffset) + 1;
            int endLine = doc.getLineNumber(Math.max(0, endOffset - 1)) + 1;

            return new int[]{startLine, endLine};
        } catch (Throwable t) {
            return null;
        }
    }

    static int getIntField(Object obj, String... names) {
        if (obj == null || names == null) return -1;
        Class<?> cls = obj.getClass();
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            try {
                java.lang.reflect.Field f = cls.getField(n);
                Object v = f.get(obj);
                if (v instanceof Number) return ((Number) v).intValue();
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof Number) return ((Number) v).intValue();
            } catch (Throwable ignored) {}
            try {
                String mname = "get" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                java.lang.reflect.Method m = cls.getMethod(mname);
                Object v = m.invoke(obj);
                if (v instanceof Number) return ((Number) v).intValue();
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    static String getStringField(Object obj, String... names) {
        if (obj == null || names == null) return null;
        Class<?> cls = obj.getClass();
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            try {
                java.lang.reflect.Field f = cls.getField(n);
                Object v = f.get(obj);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {}
            try {
                String mname = "get" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                java.lang.reflect.Method m = cls.getMethod(mname);
                Object v = m.invoke(obj);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    static void setIntField(Object obj, int value, String... names) {
        if (obj == null || names == null) return;
        Class<?> cls = obj.getClass();
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            try {
                java.lang.reflect.Field f = cls.getField(n);
                if (f.getType() == int.class || f.getType() == Integer.class) {
                    f.set(obj, value);
                    return;
                }
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(n);
                f.setAccessible(true);
                if (f.getType() == int.class || f.getType() == Integer.class) {
                    f.set(obj, value);
                    return;
                }
            } catch (Throwable ignored) {}
            try {
                String mname = "set" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                java.lang.reflect.Method m = cls.getMethod(mname, int.class);
                m.invoke(obj, value);
                return;
            } catch (Throwable ignored) {}
            try {
                String mname = "set" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                java.lang.reflect.Method m = cls.getMethod(mname, Integer.class);
                m.invoke(obj, value);
                return;
            } catch (Throwable ignored) {}
        }
    }

    static PsiMethod findWholeMethodCoveredBySnippet(Project project, VirtualFile vf, String fileSource, String pastedSnippet) {
        try {
            if (project == null || project.isDisposed() || vf == null) return null;
            if (pastedSnippet == null || pastedSnippet.isBlank()) return null;

            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (!(psiFile instanceof PsiJavaFile)) return null;

            String pNorm = normalizeForMatch(pastedSnippet);
            String pNormNoBraces = normalizeForMatch(stripOuterBraces(pastedSnippet));

            for (PsiMethod m : PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class)) {
                if (m == null) continue;

                String mText = m.getText();
                if (!mText.isBlank()) {
                    String mNorm = normalizeForMatch(mText);
                    if (!pNorm.isBlank() && mNorm.equals(pNorm)) return m;
                    if (!pNormNoBraces.isBlank() && mNorm.equals(pNormNoBraces)) return m;
                }

                PsiElement body = m.getBody();
                if (body == null) continue;
                String bodyText = body.getText();
                if (bodyText == null) bodyText = "";

                String bodyNorm = normalizeForMatch(bodyText);
                String bodyNoBracesNorm = normalizeForMatch(stripOuterBraces(bodyText));

                if (!pNorm.isBlank() && (bodyNorm.equals(pNorm) || bodyNoBracesNorm.equals(pNorm))) return m;
                if (!pNormNoBraces.isBlank() && (bodyNorm.equals(pNormNoBraces) || bodyNoBracesNorm.equals(pNormNoBraces))) return m;
            }

            int[] sn = findSnippetLineRangeInText(fileSource, pastedSnippet);
            if (sn == null) return null;

            int idx = (fileSource == null) ? -1 : fileSource.indexOf(pastedSnippet);
            if (idx < 0) return null;

            PsiElement at = psiFile.findElementAt(Math.min(idx, Math.max(0, psiFile.getTextLength() - 1)));
            PsiMethod host = PsiTreeUtil.getParentOfType(at, PsiMethod.class, false);
            if (host == null) return null;

            int[] mr = elementLineRange(project, vf, host);
            if (mr == null) return null;

            if (sn[0] <= mr[0] && sn[1] >= mr[1]) {
                return host;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean textMatchesSnippet(String candidateText, String snippet) {
        if (candidateText == null || candidateText.isBlank() || snippet == null || snippet.isBlank()) return false;

        String rawSnippet = snippet.strip();
        String rawSnippetNoBraces = stripOuterBraces(rawSnippet);
        String normalizedSnippet = normalizeForMatch(rawSnippet);
        String normalizedSnippetNoBraces = normalizeForMatch(rawSnippetNoBraces);
        String normalizedCandidate = normalizeForMatch(candidateText);

        if (candidateText.contains(rawSnippet)) return true;
        if (!rawSnippetNoBraces.isBlank() && candidateText.contains(rawSnippetNoBraces)) return true;
        if (!normalizedSnippet.isBlank() && normalizedCandidate.equals(normalizedSnippet)) return true;
        if (!normalizedSnippetNoBraces.isBlank() && normalizedCandidate.equals(normalizedSnippetNoBraces)) return true;
        if (!normalizedSnippet.isBlank() && normalizedCandidate.contains(normalizedSnippet)) return true;
        return !normalizedSnippetNoBraces.isBlank() && normalizedCandidate.contains(normalizedSnippetNoBraces);
    }

    static boolean methodContainsSnippet(PsiMethod method, String snippet) {
        if (method == null || snippet == null || snippet.isBlank()) return false;
        try {
            String rawSnippet = snippet.strip();
            String rawSnippetNoBraces = stripOuterBraces(rawSnippet);
            String normalizedSnippet = normalizeForMatch(rawSnippet);
            String normalizedSnippetNoBraces = normalizeForMatch(rawSnippetNoBraces);

            String methodText = method.getText();
            if (methodText != null && !methodText.isBlank()) {
                if (methodText.contains(rawSnippet)) return true;
                if (!rawSnippetNoBraces.isBlank() && methodText.contains(rawSnippetNoBraces)) return true;

                String normalizedMethodText = normalizeForMatch(methodText);
                if (!normalizedSnippet.isBlank() && normalizedMethodText.contains(normalizedSnippet)) return true;
                if (!normalizedSnippetNoBraces.isBlank() && normalizedMethodText.contains(normalizedSnippetNoBraces)) return true;
            }

            PsiElement body = method.getBody();
            String bodyText = body == null ? "" : body.getText();
            if (!bodyText.isBlank()) {
                if (bodyText.contains(rawSnippet)) return true;
                if (!rawSnippetNoBraces.isBlank() && bodyText.contains(rawSnippetNoBraces)) return true;

                String normalizedBodyText = normalizeForMatch(bodyText);
                if (!normalizedSnippet.isBlank() && normalizedBodyText.contains(normalizedSnippet)) return true;
                if (!normalizedSnippetNoBraces.isBlank() && normalizedBodyText.contains(normalizedSnippetNoBraces)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static boolean snippetMatchesWholeMethod(PsiMethod method, String snippet) {
        if (method == null || snippet == null || snippet.isBlank()) return false;
        String methodText = method.getText();
        PsiCodeBlock body = method.getBody();
        String bodyText = body == null ? "" : body.getText();
        return textMatchesSnippet(methodText, snippet)
                || textMatchesSnippet(bodyText, snippet)
                || textMatchesSnippet(stripOuterBraces(bodyText), snippet);
    }

    static detection.CloneRange offsetLineRange(Project project,
                                                VirtualFile vf,
                                                int startOffset,
                                                int endOffset) {
        try {
            if (project == null || project.isDisposed() || vf == null) return null;
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) return null;

            int safeStart = Math.max(0, Math.min(startOffset, doc.getTextLength()));
            int safeEnd = Math.max(safeStart, Math.min(endOffset, doc.getTextLength()));
            detection.CloneRange range = new detection.CloneRange();
            range.startLine = doc.getLineNumber(safeStart) + 1;
            range.endLine = doc.getLineNumber(Math.max(safeStart, safeEnd - 1)) + 1;
            return range;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static detection.CloneRange findFragmentRangeInMethod(Project project,
                                                          VirtualFile vf,
                                                          PsiMethod method,
                                                          String fileSource,
                                                          String snippet,
                                                          detection.CloneRange rawRange) {
        if (method == null || snippet == null || snippet.isBlank()) return null;

        if (snippetMatchesWholeMethod(method, snippet)) {
            int[] methodLines = elementLineRange(project, vf, method);
            if (methodLines != null) {
                detection.CloneRange methodRange = new detection.CloneRange();
                methodRange.startLine = methodLines[0];
                methodRange.endLine = methodLines[1];
                return methodRange;
            }
        }

        PsiRangeCandidate best = findBestStatementSequenceCandidate(project, vf, method, fileSource, snippet, rawRange);
        PsiRangeCandidate single = findBestSingleElementCandidate(project, vf, method, snippet, rawRange);
        if (isBetterPsiRangeCandidate(single, best)) {
            best = single;
        }
        return best == null ? null : best.range;
    }

    static PsiRangeCandidate findBestStatementSequenceCandidate(Project project,
                                                                VirtualFile vf,
                                                                PsiMethod method,
                                                                String fileSource,
                                                                String snippet,
                                                                detection.CloneRange rawRange) {
        if (method == null || fileSource == null || fileSource.isBlank() || snippet == null || snippet.isBlank()) return null;

        PsiRangeCandidate best = null;
        java.util.Collection<PsiCodeBlock> blocks = PsiTreeUtil.findChildrenOfType(method, PsiCodeBlock.class);
        for (PsiCodeBlock block : blocks) {
            if (block == null) continue;
            PsiStatement[] statements = block.getStatements();
            for (int start = 0; start < statements.length; start++) {
                for (int end = start; end < statements.length; end++) {
                    PsiStatement first = statements[start];
                    PsiStatement last = statements[end];
                    if (first == null || last == null) continue;

                    int startOffset = first.getTextRange().getStartOffset();
                    int endOffset = last.getTextRange().getEndOffset();
                    if (startOffset < 0 || endOffset <= startOffset || endOffset > fileSource.length()) continue;

                    String candidateText = fileSource.substring(startOffset, endOffset);
                    if (!textMatchesSnippet(candidateText, snippet)) continue;

                    detection.CloneRange range = offsetLineRange(project, vf, startOffset, endOffset);
                    PsiRangeCandidate candidate = buildPsiRangeCandidate(range, rawRange);
                    if (isBetterPsiRangeCandidate(candidate, best)) {
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    static PsiRangeCandidate findBestSingleElementCandidate(Project project,
                                                            VirtualFile vf,
                                                            PsiMethod method,
                                                            String snippet,
                                                            detection.CloneRange rawRange) {
        if (method == null || snippet == null || snippet.isBlank()) return null;

        PsiRangeCandidate best = null;
        for (PsiElement element : PsiTreeUtil.findChildrenOfAnyType(method, PsiStatement.class, PsiCodeBlock.class)) {
            if (element == null) continue;
            if (element instanceof PsiCodeBlock && element == method.getBody()) continue;
            if (!textMatchesSnippet(element.getText(), snippet)) continue;

            int[] lines = elementLineRange(project, vf, element);
            if (lines == null) continue;

            detection.CloneRange range = new detection.CloneRange();
            range.startLine = lines[0];
            range.endLine = lines[1];
            PsiRangeCandidate candidate = buildPsiRangeCandidate(range, rawRange);
            if (isBetterPsiRangeCandidate(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    static PsiRangeCandidate buildPsiRangeCandidate(detection.CloneRange range,
                                                    detection.CloneRange preferredRange) {
        if (range == null) return null;
        int distance = preferredRange == null
                ? 0
                : Math.abs(range.startLine - preferredRange.startLine) + Math.abs(range.endLine - preferredRange.endLine);
        int span = Math.max(0, range.endLine - range.startLine);
        return new PsiRangeCandidate(range, distance, span);
    }

    static boolean isBetterPsiRangeCandidate(PsiRangeCandidate candidate, PsiRangeCandidate best) {
        if (candidate == null) return false;
        if (best == null) return true;
        if (candidate.distanceScore != best.distanceScore) {
            return candidate.distanceScore < best.distanceScore;
        }
        if (candidate.spanScore != best.spanScore) {
            return candidate.spanScore < best.spanScore;
        }
        if (candidate.range.startLine != best.range.startLine) {
            return candidate.range.startLine < best.range.startLine;
        }
        return candidate.range.endLine < best.range.endLine;
    }

    static PsiMethod findMethodForCloneSnippet(Project project,
                                               VirtualFile vf,
                                               String fileSource,
                                               String snippet,
                                               detection.CloneRange preferredRange) {
        try {
            if (project == null || project.isDisposed() || vf == null) return null;
            if (snippet == null || snippet.isBlank()) return null;

            PsiMethod preferredHost = WorkflowMethodSnapshotSupport.findMethodContainingCloneRange(project, vf, preferredRange);
            if (preferredHost != null && (methodContainsSnippet(preferredHost, snippet) || snippetMatchesWholeMethod(preferredHost, snippet))) {
                return preferredHost;
            }

            PsiMethod exactWhole = findWholeMethodCoveredBySnippet(project, vf, fileSource, snippet);
            if (exactWhole != null) return exactWhole;

            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (!(psiFile instanceof PsiJavaFile)) return null;

            PsiMethod best = null;
            long bestScore = Long.MAX_VALUE;
            for (PsiMethod method : PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class)) {
                if (!methodContainsSnippet(method, snippet)) continue;

                long score = 0L;
                if (preferredRange != null) {
                    int[] methodLines = elementLineRange(project, vf, method);
                    if (methodLines != null) {
                        int deltaStart = Math.abs(methodLines[0] - preferredRange.startLine);
                        int deltaEnd = Math.abs(methodLines[1] - preferredRange.endLine);
                        score = (long) deltaStart + deltaEnd;
                    }
                }

                if (best == null || score < bestScore) {
                    best = method;
                    bestScore = score;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    static java.util.List<detection.DetectedClone> resolveDetectedCloneRangesWithPsi(Project project,
                                                                                      VirtualFile vf,
                                                                                      String fileSource,
                                                                                      java.util.List<detection.DetectedClone> clones,
                                                                                      Consumer<String> viewer) {
        if (clones == null || clones.isEmpty()) return java.util.Collections.emptyList();

        java.util.ArrayList<detection.DetectedClone> resolved = new java.util.ArrayList<>();
        int changedCloneCount = 0;
        for (detection.DetectedClone clone : clones) {
            detection.DetectedClone adjusted = resolveSingleDetectedCloneWithPsi(project, vf, fileSource, clone);
            resolved.add(adjusted);

            String beforeSummary = WorkflowCloneSelectionSupport.summarizeCloneRanges(clone == null ? null : clone.ranges);
            String afterSummary = WorkflowCloneSelectionSupport.summarizeCloneRanges(adjusted == null ? null : adjusted.ranges);
            if (!java.util.Objects.equals(beforeSummary, afterSummary)) {
                changedCloneCount++;
                String cloneId = clone == null || clone.id == null || clone.id.isBlank() ? "<unknown>" : clone.id;
                logStage(viewer, "DETECTION", "psi-resolved clone ranges for " + cloneId + ": [" + beforeSummary + "] -> [" + afterSummary + "]");
            }
        }

        if (changedCloneCount > 0) {
            logStage(viewer, "DETECTION", "psi-resolved clone ranges: " + changedCloneCount + "/" + resolved.size() + " clone group(s)");
        }
        return resolved;
    }

    static detection.DetectedClone resolveSingleDetectedCloneWithPsi(Project project,
                                                                     VirtualFile vf,
                                                                     String fileSource,
                                                                     detection.DetectedClone clone) {
        if (clone == null) return null;
        if ("psi_fallback_same_file".equals(clone.id)) {
            return clone;
        }

        detection.DetectedClone adjusted = new detection.DetectedClone();
        adjusted.id = clone.id;
        adjusted.refactorType = clone.refactorType;
        adjusted.reason = clone.reason;
        adjusted.cloneCodes = new java.util.ArrayList<>();
        adjusted.ranges = new java.util.ArrayList<>();

        java.util.List<String> cloneCodes = WorkflowCloneSelectionSupport.getDetectedCloneCodes(clone, fileSource);
        int occurrenceCount = Math.max(
                cloneCodes.size(),
                clone.ranges == null ? 0 : clone.ranges.size()
        );

        for (int i = 0; i < occurrenceCount; i++) {
            detection.CloneRange rawRange = (clone.ranges == null || i >= clone.ranges.size()) ? null : clone.ranges.get(i);
            String snippet = i < cloneCodes.size() ? cloneCodes.get(i) : "";
            detection.CloneRange resolvedRange = resolveCloneRangeWithPsi(project, vf, fileSource, snippet, rawRange);
            if (resolvedRange != null) {
                adjusted.ranges.add(resolvedRange);
                adjusted.cloneCodes.add(snippet == null ? "" : snippet);
            }
        }

        adjusted.cloneCodeA = adjusted.cloneCodes.isEmpty() ? "" : adjusted.cloneCodes.get(0);
        adjusted.cloneCodeB = adjusted.cloneCodes.size() > 1 ? adjusted.cloneCodes.get(1) : "";
        return adjusted;
    }

    static detection.CloneRange resolveCloneRangeWithPsi(Project project,
                                                         VirtualFile vf,
                                                         String fileSource,
                                                         String snippet,
                                                         detection.CloneRange rawRange) {
        PsiMethod rawHostMethod = WorkflowMethodSnapshotSupport.findMethodContainingCloneRange(project, vf, rawRange);
        if (rawHostMethod != null) {
            detection.CloneRange fragmentRange = findFragmentRangeInMethod(project, vf, rawHostMethod, fileSource, snippet, rawRange);
            if (fragmentRange != null) {
                return fragmentRange;
            }
            if (rawRange != null) {
                return copyCloneRange(rawRange);
            }

            int[] methodLines = elementLineRange(project, vf, rawHostMethod);
            if (methodLines != null) {
                detection.CloneRange methodRange = new detection.CloneRange();
                methodRange.startLine = methodLines[0];
                methodRange.endLine = methodLines[1];
                return methodRange;
            }
        }

        PsiMethod method = findMethodForCloneSnippet(project, vf, fileSource, snippet, rawRange);
        if (method != null) {
            detection.CloneRange fragmentRange = findFragmentRangeInMethod(project, vf, method, fileSource, snippet, rawRange);
            if (fragmentRange != null) {
                return fragmentRange;
            }
            int[] methodLines = elementLineRange(project, vf, method);
            if (methodLines != null) {
                detection.CloneRange methodRange = new detection.CloneRange();
                methodRange.startLine = methodLines[0];
                methodRange.endLine = methodLines[1];
                return methodRange;
            }
        }
        return copyCloneRange(rawRange);
    }

    static detection.CloneRange copyCloneRange(detection.CloneRange range) {
        if (range == null) return null;
        detection.CloneRange copy = new detection.CloneRange();
        copy.startLine = range.startLine;
        copy.endLine = range.endLine;
        return copy;
    }

    static java.util.List<detection.DetectedClone> mergeOverlappingDetectedClones(String fileSource,
                                                                                   java.util.List<detection.DetectedClone> clones,
                                                                                   Consumer<String> viewer) {
        if (clones == null || clones.isEmpty()) return java.util.Collections.emptyList();

        java.util.ArrayList<detection.DetectedClone> working = new java.util.ArrayList<>();
        for (detection.DetectedClone clone : clones) {
            if (clone != null) working.add(clone);
        }
        if (working.size() <= 1) return working;

        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < working.size(); i++) {
                for (int j = i + 1; j < working.size(); j++) {
                    if (detectedClonesOverlap(working.get(i), working.get(j))) {
                        detection.DetectedClone merged = mergeDetectedClonePair(fileSource, working.get(i), working.get(j));
                        working.set(i, merged);
                        working.remove(j);
                        changed = true;
                        break outer;
                    }
                }
            }
        }

        if (working.size() != clones.size()) {
            logStage(viewer, "DETECTION", "merged overlapping clone groups: " + clones.size() + " -> " + working.size());
        }
        return working;
    }

    static boolean detectedClonesOverlap(detection.DetectedClone first, detection.DetectedClone second) {
        if (first == null || second == null || first.ranges == null || second.ranges == null) return false;
        for (detection.CloneRange left : first.ranges) {
            for (detection.CloneRange right : second.ranges) {
                if (cloneRangesOverlap(left, right)) return true;
            }
        }
        return false;
    }

    static boolean cloneRangesOverlap(detection.CloneRange left, detection.CloneRange right) {
        if (left == null || right == null) return false;
        return left.startLine <= right.endLine && left.endLine >= right.startLine;
    }

    static detection.DetectedClone mergeDetectedClonePair(String fileSource,
                                                          detection.DetectedClone first,
                                                          detection.DetectedClone second) {
        detection.DetectedClone merged = new detection.DetectedClone();
        merged.id = mergeCloneIds(first, second);
        merged.refactorType = WorkflowCloneSelectionSupport.firstNonBlank(
                first == null ? null : first.refactorType,
                second == null ? null : second.refactorType
        );
        merged.reason = mergeCloneReasons(first, second);
        java.util.ArrayList<MergedCloneOccurrence> mergedOccurrences = mergeCloneOccurrences(fileSource, first, second);
        merged.ranges = new java.util.ArrayList<>();
        merged.cloneCodes = new java.util.ArrayList<>();
        for (MergedCloneOccurrence occurrence : mergedOccurrences) {
            if (occurrence == null || occurrence.range == null) continue;
            merged.ranges.add(occurrence.range);
            merged.cloneCodes.add(occurrence.code == null ? "" : occurrence.code);
        }
        merged.cloneCodeA = merged.cloneCodes.isEmpty() ? "" : merged.cloneCodes.get(0);
        merged.cloneCodeB = merged.cloneCodes.size() > 1 ? merged.cloneCodes.get(1) : "";
        return merged;
    }

    static String mergeCloneIds(detection.DetectedClone first, detection.DetectedClone second) {
        String a = first == null ? "" : first.id;
        String b = second == null ? "" : second.id;
        if (a == null || a.isBlank()) return b == null ? "" : b;
        if (b == null || b.isBlank()) return a;
        if (a.equals(b)) return a;
        return a + "__" + b;
    }

    static String mergeCloneReasons(detection.DetectedClone first, detection.DetectedClone second) {
        java.util.LinkedHashSet<String> parts = new java.util.LinkedHashSet<>();
        if (first != null && first.reason != null && !first.reason.isBlank()) parts.add(first.reason.strip());
        if (second != null && second.reason != null && !second.reason.isBlank()) parts.add(second.reason.strip());
        return String.join("\n\n", parts);
    }

    static java.util.ArrayList<MergedCloneOccurrence> mergeCloneOccurrences(String fileSource,
                                                                             detection.DetectedClone first,
                                                                             detection.DetectedClone second) {
        java.util.LinkedHashMap<String, MergedCloneOccurrence> unique = new java.util.LinkedHashMap<>();
        addCloneOccurrences(unique, first, fileSource);
        addCloneOccurrences(unique, second, fileSource);
        java.util.ArrayList<MergedCloneOccurrence> out = new java.util.ArrayList<>(unique.values());
        out.sort((a, b) -> {
            int cmp = Integer.compare(a.range.startLine, b.range.startLine);
            return cmp != 0 ? cmp : Integer.compare(a.range.endLine, b.range.endLine);
        });
        return out;
    }

    static void addCloneOccurrences(java.util.Map<String, MergedCloneOccurrence> out,
                                    detection.DetectedClone clone,
                                    String fileSource) {
        if (out == null || clone == null || clone.ranges == null) return;
        java.util.List<String> cloneCodes = WorkflowCloneSelectionSupport.getDetectedCloneCodes(clone, fileSource);
        for (int i = 0; i < clone.ranges.size(); i++) {
            detection.CloneRange range = clone.ranges.get(i);
            if (range == null) continue;
            String key = range.startLine + ":" + range.endLine;
            String code = i < cloneCodes.size() ? cloneCodes.get(i) : "";
            if (out.containsKey(key)) {
                MergedCloneOccurrence existing = out.get(key);
                if (existing != null && (existing.code == null || existing.code.isBlank()) && code != null && !code.isBlank()) {
                    existing.code = code;
                }
                continue;
            }
            detection.CloneRange copy = new detection.CloneRange();
            copy.startLine = range.startLine;
            copy.endLine = range.endLine;
            out.put(key, new MergedCloneOccurrence(copy, code));
        }
    }
}
