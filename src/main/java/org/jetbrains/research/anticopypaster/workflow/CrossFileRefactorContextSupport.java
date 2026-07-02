package org.jetbrains.research.anticopypaster.workflow;

import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.extractPackageName;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileTextEditSupport.buildCrossFileOccurrenceSpecs;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.List;

final class CrossFileRefactorContextSupport {
    private CrossFileRefactorContextSupport() {}

    static CrossFileRefactorContext build(Project project,
                                          List<CrossFileSource> sources,
                                          CrossFileClone selectedClone) {
        CrossFileRefactorContext context = new CrossFileRefactorContext();
        appendSelectedFileTargets(context, sources);
        appendOccurrenceTypeFacts(context, project, selectedClone);
        appendProjectOwnerTargets(context, CrossFileRefactoringSupport.collectSharedOwnerCandidates(project, sources));
        appendTypeWarnings(context);
        return context;
    }

    static void appendPromptFacts(StringBuilder sb, CrossFileRefactorContext context) {
        if (sb == null || context == null) return;

        sb.append("=== VERIFIED JAVA TYPE FACTS ===\n");
        if (context.occurrenceFacts.isEmpty()) {
            sb.append("(No PSI occurrence type facts were available. Do not guess inheritance or type identity.)\n");
        } else {
            for (CrossFileOccurrenceTypeFact fact : context.occurrenceFacts) {
                sb.append("- ").append(fact.occurrenceId).append(" in ").append(fact.path).append("\n");
                sb.append("  enclosing_class: ").append(blank(fact.enclosingClassFqn)).append("\n");
                sb.append("  direct_and_indirect_superclasses: ");
                sb.append(fact.superClassFqns.isEmpty() ? "(none found)" : String.join(", ", fact.superClassFqns)).append("\n");
            }
        }
        if (!context.warnings.isEmpty()) {
            sb.append("Verified warnings:\n");
            for (String warning : context.warnings) {
                sb.append("- ").append(warning).append("\n");
            }
        }
        sb.append("Do not claim two classes are the same type or share a superclass unless it appears in the verified facts above.\n\n");

        sb.append("=== ALLOWED SHARED HELPER TARGETS ===\n");
        sb.append("You may choose shared_helper.strategy/path ONLY from this list, or choose new_helper_class.\n");
        for (CrossFileAllowedHelperTarget target : context.allowedTargets) {
            if (target == null) continue;
            sb.append("- strategy=").append(target.strategy)
                    .append(", path=").append(target.path);
            if (target.classFqn != null && !target.classFqn.isBlank()) {
                sb.append(", class=").append(target.classFqn);
            }
            if (target.reason != null && !target.reason.isBlank()) {
                sb.append(", reason=").append(target.reason);
            }
            sb.append("\n");
        }
        sb.append("- strategy=new_helper_class, path=<new helper under a package visible to all callers>, reason=only when no listed existing target can validly own the helper\n");
        sb.append("Do not invent existing_project_file paths. If no listed existing target is valid, use new_helper_class.\n\n");
    }

    static void appendAllowedProjectOwnerSources(StringBuilder sb, CrossFileRefactorContext context) {
        if (sb == null || context == null) return;
        java.util.List<CrossFileAllowedHelperTarget> projectTargets = context.targetsByStrategy("existing_project_file");
        if (projectTargets.isEmpty()) return;
        sb.append("=== ALLOWED EXISTING PROJECT SHARED OWNER SOURCES ===\n");
        sb.append("These are the only existing_project_file helper targets allowed by PSI. Use only members visible in the source.\n\n");
        for (CrossFileAllowedHelperTarget target : projectTargets) {
            if (target == null || target.source == null) continue;
            sb.append("----- FILE: ").append(target.path).append(" -----\n");
            sb.append("```java\n").append(target.source.source).append("\n```\n\n");
        }
    }

    static String buildPreviousProposalRepairBlock(CrossFileRefactorResult previousResult) {
        if (previousResult == null || previousResult.rawPlanJson == null || previousResult.rawPlanJson.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== REPAIR MODE: PREVIOUS JSON PLAN ===\n");
        sb.append("Repair the previous plan with the smallest possible JSON changes. ");
        sb.append("Keep the same helper strategy/path unless the compiler error proves that target is illegal. ");
        sb.append("Do not switch to an unrelated owner or broaden the refactor.\n");
        if (previousResult.selectedPanelistId != null && !previousResult.selectedPanelistId.isBlank()) {
            sb.append("previous_selected_panelist: ").append(previousResult.selectedPanelistId).append("\n");
        }
        sb.append("```json\n")
                .append(CrossFileJsonSupport.safeTruncate(previousResult.rawPlanJson, 12000))
                .append("\n```\n\n");
        return sb.toString();
    }

    private static void appendSelectedFileTargets(CrossFileRefactorContext context, List<CrossFileSource> sources) {
        if (context == null || sources == null) return;
        for (CrossFileSource source : sources) {
            if (source == null) continue;
            CrossFileAllowedHelperTarget target = new CrossFileAllowedHelperTarget();
            target.strategy = "existing_selected_file";
            target.path = source.relativePath;
            target.absolutePath = source.absolutePath;
            target.classFqn = primaryClassFqn(source);
            target.reason = "selected file";
            target.source = source;
            context.allowedTargets.add(target);
        }
    }

    private static void appendProjectOwnerTargets(CrossFileRefactorContext context, List<CrossFileSource> candidates) {
        if (context == null || candidates == null) return;
        java.util.Set<String> commonSuperclasses = commonSuperclassFqns(context);
        boolean requireCommonSuperclass = context.occurrenceFacts.size() >= 2;
        if (requireCommonSuperclass && commonSuperclasses.isEmpty()) {
            context.warnings.add("No PSI-confirmed common superclass was found across all target occurrence classes. Do not choose existing_project_file.");
        }
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (CrossFileSource source : candidates) {
            if (source == null) continue;
            String classFqn = primaryClassFqn(source);
            if (requireCommonSuperclass && !commonSuperclasses.contains(classFqn)) {
                continue;
            }
            String key = CrossFileRefactorContext.normalizePath(source.absolutePath);
            if (!seen.add(key)) continue;
            CrossFileAllowedHelperTarget target = new CrossFileAllowedHelperTarget();
            target.strategy = "existing_project_file";
            target.path = source.relativePath;
            target.absolutePath = source.absolutePath;
            target.classFqn = classFqn;
            target.reason = "PSI-confirmed common superclass/source owner";
            target.source = source;
            context.allowedTargets.add(target);
        }
    }

    private static java.util.Set<String> commonSuperclassFqns(CrossFileRefactorContext context) {
        java.util.LinkedHashSet<String> common = new java.util.LinkedHashSet<>();
        boolean initialized = false;
        if (context == null) return common;
        for (CrossFileOccurrenceTypeFact fact : context.occurrenceFacts) {
            if (fact == null || fact.superClassFqns.isEmpty()) {
                return java.util.Set.of();
            }
            java.util.LinkedHashSet<String> next = new java.util.LinkedHashSet<>(fact.superClassFqns);
            if (!initialized) {
                common.addAll(next);
                initialized = true;
            } else {
                common.retainAll(next);
            }
            if (common.isEmpty()) return java.util.Set.of();
        }
        return common;
    }

    private static void appendOccurrenceTypeFacts(CrossFileRefactorContext context,
                                                  Project project,
                                                  CrossFileClone selectedClone) {
        if (context == null || selectedClone == null) return;
        for (CrossFileOccurrenceSpec spec : buildCrossFileOccurrenceSpecs(selectedClone)) {
            CrossFileOccurrence occurrence = spec == null ? null : spec.occurrence;
            if (occurrence == null || occurrence.source == null) continue;
            CrossFileOccurrenceTypeFact fact = resolveOccurrenceTypeFact(project, spec);
            context.occurrenceFacts.add(fact);
        }
    }

    private static CrossFileOccurrenceTypeFact resolveOccurrenceTypeFact(Project project, CrossFileOccurrenceSpec spec) {
        CrossFileOccurrenceTypeFact fact = new CrossFileOccurrenceTypeFact();
        fact.occurrenceId = spec == null ? "" : spec.occurrenceId;
        CrossFileOccurrence occurrence = spec == null ? null : spec.occurrence;
        CrossFileSource source = occurrence == null ? null : occurrence.source;
        fact.path = source == null ? "" : source.relativePath;

        if (project != null && source != null && source.vf != null) {
            try {
                CrossFileOccurrenceTypeFact psiFact = ReadAction.compute(() -> resolveOccurrenceTypeFactWithPsi(project, spec));
                if (psiFact != null && psiFact.enclosingClassFqn != null && !psiFact.enclosingClassFqn.isBlank()) {
                    return psiFact;
                }
            } catch (Throwable ignored) {}
        }

        String fqn = primaryClassFqn(source);
        fact.enclosingClassFqn = fqn;
        fact.simpleName = simpleName(fqn);
        return fact;
    }

    private static CrossFileOccurrenceTypeFact resolveOccurrenceTypeFactWithPsi(Project project, CrossFileOccurrenceSpec spec) {
        CrossFileOccurrence occurrence = spec.occurrence;
        CrossFileSource source = occurrence.source;
        CrossFileOccurrenceTypeFact fact = new CrossFileOccurrenceTypeFact();
        fact.occurrenceId = spec.occurrenceId;
        fact.path = source.relativePath;

        PsiFile psiFile = PsiManager.getInstance(project).findFile(source.vf);
        if (!(psiFile instanceof PsiJavaFile javaFile)) return fact;
        int offset = offsetForLine(source.source, Math.max(1, occurrence.startLine));
        offset = firstNonWhitespaceOffset(source.source, offset);
        PsiElement element = offset >= 0 && offset < javaFile.getTextLength() ? javaFile.findElementAt(offset) : null;
        PsiClass psiClass = element == null ? null : PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
        if (psiClass == null && javaFile.getClasses().length > 0) {
            psiClass = javaFile.getClasses()[0];
        }
        if (psiClass == null) return fact;

        fact.enclosingClassFqn = classFqn(psiClass);
        fact.simpleName = psiClass.getName() == null ? simpleName(fact.enclosingClassFqn) : psiClass.getName();
        collectSuperclassFqns(psiClass, fact.superClassFqns, new java.util.LinkedHashSet<>(), 0);
        return fact;
    }

    private static void collectSuperclassFqns(PsiClass psiClass,
                                              java.util.List<String> out,
                                              java.util.Set<String> seen,
                                              int depth) {
        if (psiClass == null || depth > 12) return;
        for (PsiClassType type : psiClass.getExtendsListTypes()) {
            PsiClass superClass = type == null ? null : type.resolve();
            String fqn = classFqn(superClass);
            if (fqn == null || fqn.isBlank() || !seen.add(fqn)) continue;
            out.add(fqn);
            collectSuperclassFqns(superClass, out, seen, depth + 1);
        }
    }

    private static void appendTypeWarnings(CrossFileRefactorContext context) {
        java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>> bySimpleName = new java.util.LinkedHashMap<>();
        for (CrossFileOccurrenceTypeFact fact : context.occurrenceFacts) {
            if (fact == null || fact.simpleName == null || fact.simpleName.isBlank()) continue;
            bySimpleName.computeIfAbsent(fact.simpleName, ignored -> new java.util.LinkedHashSet<>())
                    .add(fact.enclosingClassFqn);
        }
        for (java.util.Map.Entry<String, java.util.LinkedHashSet<String>> entry : bySimpleName.entrySet()) {
            if (entry.getValue().size() > 1) {
                context.warnings.add("Classes share simple name '" + entry.getKey()
                        + "' but are different FQNs: " + String.join(", ", entry.getValue())
                        + ". Do not pass this/reference values between them or assume type compatibility.");
            }
        }
    }

    private static String primaryClassFqn(CrossFileSource source) {
        if (source == null) return "";
        String pkg = extractPackageName(source.source);
        String cls = "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\b(?:public\\s+)?(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)?(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)\\b")
                .matcher(source.source == null ? "" : source.source);
        if (matcher.find()) cls = matcher.group(1);
        if (cls == null || cls.isBlank()) {
            String name = source.ioFile == null ? "" : source.ioFile.getName();
            cls = name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : name;
        }
        if (cls == null || cls.isBlank()) return "";
        return pkg == null || pkg.isBlank() ? cls : pkg + "." + cls;
    }

    private static String classFqn(PsiClass psiClass) {
        if (psiClass == null) return "";
        String qn = psiClass.getQualifiedName();
        if (qn != null && !qn.isBlank()) return qn;
        PsiFile file = psiClass.getContainingFile();
        String pkg = file instanceof PsiJavaFile javaFile ? javaFile.getPackageName() : "";
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        PsiClass current = psiClass;
        while (current != null) {
            if (current.getName() != null && !current.getName().isBlank()) {
                names.add(0, current.getName());
            }
            current = PsiTreeUtil.getParentOfType(current, PsiClass.class, true);
        }
        String suffix = String.join(".", names);
        return pkg == null || pkg.isBlank() ? suffix : pkg + "." + suffix;
    }

    private static int offsetForLine(String source, int line) {
        if (source == null || source.isEmpty() || line <= 1) return 0;
        int currentLine = 1;
        for (int i = 0; i < source.length(); i++) {
            if (currentLine == line) return i;
            if (source.charAt(i) == '\n') currentLine++;
        }
        return Math.max(0, source.length() - 1);
    }

    private static int firstNonWhitespaceOffset(String source, int offset) {
        if (source == null || source.isEmpty()) return 0;
        int i = Math.max(0, Math.min(offset, source.length() - 1));
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        return i >= source.length() ? Math.max(0, source.length() - 1) : i;
    }

    private static String simpleName(String fqn) {
        if (fqn == null) return "";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "(unknown)" : value;
    }
}
