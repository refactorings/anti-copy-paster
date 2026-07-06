package org.jetbrains.research.anticopypaster.workflow;

import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.extractJsonObjectSubstring;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.getJsonArray;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.getJsonDouble;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.getJsonObject;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.getJsonString;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.parseJsonStringArray;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.safeTruncate;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.stripOptionalJavaFence;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileProposalSupport.normalizeCrossFileCompileErrorFile;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.applyCrossFileSharedHelperPlan;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.containsMethodCall;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.findMissingHelperTypes;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.findMissingReplacementTypes;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.hasInvalidFunctionalInterfaceAnnotation;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.inferSharedHelperStrategy;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.newHelperPathAlreadyExists;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.normalizeNewHelperPlan;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.passesEndAsReferenceWithoutWriteBack;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.readExistingProjectSharedSource;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.referencesUndeclaredLimit;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.selectedCloneUsesPrimitiveArrays;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.sourceDeclaresField;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.sourceDeclaresMethod;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.stripJavaComments;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSharedHelperSupport.usesGenericPrimitiveArrayAbstraction;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.buildCrossFileSourceIndex;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.putCrossFileKey;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.readCurrentSource;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.resolveCrossFileSource;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.toProjectRelativePath;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileTextEditSupport.applyCrossFileOccurrenceReplacements;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileTextEditSupport.buildCrossFileOccurrenceSpecs;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileTextEditSupport.findJavaImportConflicts;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileTextEditSupport.filterOccurrenceSpecsForSource;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.logStage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jetbrains.research.anticopypaster.agents.compilation;
import org.jetbrains.research.anticopypaster.llm.LlmClient;

final class CrossFileRefactoringSupport {

    private static final List<CrossFilePanelistSpec> REFACTOR_PANELISTS = List.of(
            new CrossFilePanelistSpec("P1", "Refactoring Panelist 1"),
            new CrossFilePanelistSpec("P2", "Refactoring Panelist 2"),
            new CrossFilePanelistSpec("P3", "Refactoring Panelist 3")
    );
    private static final int CURATOR_CANDIDATE_MAX_CHARS = 12000;

    private CrossFileRefactoringSupport() {}

    static CrossFileRefactorResult runCrossFileRefactoringAgent(LlmClient llm,
                                                                        Project project,
                                                                        Consumer<String> viewer,
                                                                        List<CrossFileSource> sources,
                                                                        CrossFileClone selectedClone,
                                                                        String retryFeedback,
                                                                        CrossFileRefactorResult previousResult) {
        java.util.ArrayList<CrossFileRefactorPanelistOutcome> panelistOutcomes = new java.util.ArrayList<>();
        CrossFileRefactorContext context = CrossFileRefactorContextSupport.build(project, sources, selectedClone);
        String basePrompt = buildCrossFileRefactorPrompt(project, sources, selectedClone, retryFeedback, previousResult, context);
        for (CrossFilePanelistSpec spec : REFACTOR_PANELISTS) {
            String prompt = buildCrossFileRefactorPanelistPrompt(spec, basePrompt);
            String raw = WorkflowLlmCallSupport.callRefactor(llm, prompt, viewer, project);
            CrossFileRefactorResult parsed = parseCrossFileRefactorResult(raw, project, sources, selectedClone, context);
            panelistOutcomes.add(new CrossFileRefactorPanelistOutcome(
                    spec.id,
                    raw,
                    parsed,
                    parsed != null && parsed.parsed,
                    parsed == null ? "No refactor result returned." : parsed.message
            ));
            if (parsed != null) {
                for (String warning : parsed.warnings) {
                    logStage(viewer, "REFACTOR", "[" + spec.id + "] warning: " + warning);
                }
            }
        }

        String curatorPrompt = buildCrossFileRefactorCuratorPrompt(sources, selectedClone, panelistOutcomes, context);
        String curatorRaw = WorkflowLlmCallSupport.callRefactor(llm, curatorPrompt, viewer, project);
        CrossFilePanelistSelection selection = parseCrossFilePanelistSelection(curatorRaw);
        CrossFileRefactorPanelistOutcome selected = resolveSelectedRefactorPanelist(selection, panelistOutcomes);
        if (selected == null || selected.result == null || !selected.result.parsed) {
            CrossFileRefactorResult failure = new CrossFileRefactorResult();
            failure.message = buildRefactorSelectionFailureMessage(selection, panelistOutcomes);
            return failure;
        }

        selected.result.selectedPanelistId = selected.panelistId;
        selected.result.curatorSummary = selection == null ? "" : selection.summary;
        selected.result.curatorFeedback = selection == null ? "" : selection.feedback;
        selected.result.curatorConfidence = selection == null ? 0.0d : selection.confidence;
        if (selection != null && selection.matchedCategories != null) {
            selected.result.curatorMatchedCategories.addAll(selection.matchedCategories);
        }
        if (selection != null && selection.summary != null && !selection.summary.isBlank()) {
            logStage(viewer, "REFACTOR", "curator selected " + selected.panelistId + ": " + selection.summary);
        } else {
            logStage(viewer, "REFACTOR", "curator selected " + selected.panelistId);
        }
        return selected.result;
    }

    static String buildCrossFileRefactorPanelistPrompt(CrossFilePanelistSpec spec, String basePrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append(spec.title).append(" (").append(spec.id).append(")\n");
        sb.append("You are one of three independent Java refactoring panelists.\n");
        sb.append("Produce your own best Extract Method candidate for the provided cross-file target clone.\n");
        sb.append("Do not try to match the other panelists. Return the strongest valid candidate you can.\n\n");
        sb.append(basePrompt == null ? "" : basePrompt);
        return sb.toString();
    }

    static String buildCrossFileRefactorPrompt(Project project,
                                                       List<CrossFileSource> sources,
                                                       CrossFileClone selectedClone,
                                                       String retryFeedback,
                                                       CrossFileRefactorResult previousResult,
                                                       CrossFileRefactorContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert Java refactoring agent.\n");
        sb.append("You specialize in clone removal via Extract Method across selected Java files and improving software quality ");
        sb.append("(readability, maintainability, cohesion, and low coupling).\n");
        sb.append("Follow the constraints and output format EXACTLY.\n");
        sb.append("Do not skip the refactoring task. If the cross-file target clone can be extracted with parameters or a helper class, you must still perform Extract Method.\n");
        sb.append("If anything is unclear, prefer the most conservative valid cross-file Extract Method that targets the provided clone rather than leaving the files unchanged.\n");
        sb.append("The selected occurrence snippets are the mandatory refactoring target. Do NOT ignore them and do NOT switch to a different duplicated region.\n\n");

        appendSelectedFiles(sb, sources);

        sb.append("=== CONTEXT ===\n");
        sb.append("- Language: Java\n");
        sb.append("- Goal: Remove duplicated code (clone) with Extract Method if clones are worth refactoring.\n");
        sb.append("- Scope: Cross-file working set. Use shared extracted logic rather than separate helper methods per file.\n");
        sb.append("- Constraints: Preserve behavior, package declarations, imports, public API, and existing file names.\n");
        sb.append("- You may add exactly one small helper class only when there is no valid existing shared location.\n");
        sb.append("- You may also modify one allowed existing project shared owner, such as a common superclass, when it is the natural place for the shared helper.\n");
        sb.append("- Do not refactor unrelated duplicates that are not part of the selected cross-file clone class.\n");
        sb.append("- The workflow will reject the result unless EVERY affected file from TARGET CLONE OCCURRENCES replaces its clone occurrence with calls to shared extracted logic.\n\n");

        appendTargetClone(sb, selectedClone);
        CrossFileRefactorContextSupport.appendPromptFacts(sb, context);
        if (retryFeedback != null && !retryFeedback.isBlank()) {
            sb.append("=== PREVIOUS ATTEMPT FEEDBACK ===\n");
            sb.append(retryFeedback).append("\n\n");
            sb.append("You must fix the issues above in this attempt while still producing a valid Extract Method refactoring plan.\n\n");
            sb.append(CrossFileRefactorContextSupport.buildPreviousProposalRepairBlock(previousResult));
        }
        appendWorkingSet(sb, sources);
        CrossFileRefactorContextSupport.appendAllowedProjectOwnerSources(sb, context);

        sb.append("=== REFACTORING TASK ===\n");
        sb.append("Refactoring Pattern: Extract Method (ONLY)\n");
        sb.append("Intent: Improve maintainability and reduce duplication across files while preserving behavior.\n");
        sb.append("Requirements:\n");
        sb.append("1) Refactor the provided cross-file target clone, not some other duplication in the selected files.\n");
        sb.append("2) First try to introduce ONE shared helper method, then replace every target occurrence with a call to it.\n");
        sb.append("3) Preferred shared location order: allowed existing_selected_file target, allowed existing_project_file target, then one new package-level helper class.\n");
        sb.append("4) Replace ALL target clone occurrences with calls to the shared extracted logic.\n");
        sb.append("5) Prefer a parameterized helper method when the clone occurrences have small local differences.\n");
        sb.append("6) Choose the smallest safe extraction boundary that still removes the target duplication.\n");
        sb.append("7) Ensure the result compiles as valid Java.\n");
        sb.append("8) Do not leave the target clone unchanged if a valid cross-file Extract Method can be applied with the allowed helper targets.\n\n");

        sb.append("=== STEPS TO FOLLOW (DO NOT OUTPUT THESE STEPS) ===\n");
        sb.append("Step 1: Use the selected occurrence snippets as the primary target. Use line ranges only as approximate hints to locate those snippets in the files.\n");
        sb.append("Step 2: Locate only the occurrences that correspond to the selected cross-file clone class.\n");
        sb.append("Step 3: Create one shared helper method with a clear name and parameters if needed.\n");
        sb.append("Step 4: Replace every target occurrence in every affected file with a helper call or minimal wrapper statements.\n");
        sb.append("Step 5: Re-check for compilation issues (imports, generics, visibility, checked exceptions, package access, static context).\n\n");

        sb.append("=== STRICT CONSTRAINTS (HARD RULES) ===\n");
        sb.append("- Do not create separate helper methods in each affected file; that only relocates the cross-file clone.\n");
        sb.append("- Do not return whole existing files, whole existing classes, package declarations for existing files, or unrelated edits.\n");
        sb.append("- Keep each replacement_code to only the method call or minimal statements that replace that exact occurrence.\n");
        sb.append("- Preserve checked exceptions, return values, side effects, ordering, synchronization, visibility, and null behavior.\n");
        sb.append("- Preserve package declarations, existing imports unless explicitly needed, public/protected method signatures, and class public API.\n");
        sb.append("- The selected occurrence snippets are mandatory refactoring targets.\n");
        sb.append("- The clone ranges are approximate hints and must not override the snippets.\n");
        sb.append("- Do NOT refactor unrelated duplicated code elsewhere in the selected files.\n");
        sb.append("- If exact matching is difficult, prefer the closest valid extraction centered on the selected occurrence snippets.\n");
        sb.append("- Minimize edits outside the target clone regions and the chosen shared helper location.\n");
        sb.append("- If you create a new helper class, keep it package-private only when all callers are in the same package; otherwise make it public and use a fully-qualified helper class name in replacement_code.\n");
        sb.append("- A helper called from multiple classes must be static and must not be private. Use package-private visibility when the callers are in the same package, otherwise public.\n");
        sb.append("- Java visibility must compile: a helper inserted into an existing shared owner may use only members declared in that owner plus public/protected/package-visible API available from its parameters. Do not access subclass private fields/methods from the helper.\n");
        sb.append("- If target logic needs private fields or private methods from ByteChunk/CharChunk/etc., keep those accesses inside replacement_code callbacks/lambdas in the original class, or pass plain values into the helper.\n");
        sb.append("- Do not call methods or fields through AbstractChunk unless they are declared in AbstractChunk. For example, do not invent chunk.makeSpace(), chunk.flushBuffer(), chunk.out, or ((SubClass) chunk).privateField access.\n");
        sb.append("- Primitive arrays byte[] and char[] cannot be abstracted with generic T[] parameters. Use a helper signature that Java can actually call with primitive arrays.\n");
        sb.append("- Use @FunctionalInterface only when the interface has exactly one abstract method.\n");
        sb.append("- Existing-file helpers should avoid new imports when possible. Prefer fully-qualified names for non-java.lang types inside helper_method and replacement_code. shared_helper.imports may be used only when it will not conflict with existing imports or declared types; otherwise the workflow rejects the plan and reports the exact conflict to repair with fully-qualified names.\n");
        sb.append("- If helper_method references a custom callback/interface type, include that nested interface declaration in helper_method after the helper method. Do not reference undefined ArrayWriter/Callback/etc. types.\n");
        sb.append("- replacement_code must be self-contained inside the replacement scope. Do not use deleted local variables such as limit unless replacement_code declares them first, e.g. int limit = getLimitInternal();\n");
        sb.append("- Do not pass new int[]{end} as a mutable reference unless replacement_code assigns the updated value back to end before returning.\n");
        sb.append("- Do NOT include explanatory prose outside JSON.\n\n");

        sb.append("=== OUTPUT FORMAT ===\n");
        sb.append("- Output ONLY a valid JSON object.\n");
        sb.append("- Do NOT return whole existing source files.\n");
        sb.append("- Do NOT use markdown fences.\n");
        sb.append("- Use paths and occurrence_id values exactly as listed in TARGET CLONE OCCURRENCES.\n");
        sb.append("{\n");
        sb.append("  \"status\": \"refactored\",\n");
        sb.append("  \"summary\": \"short explanation of the shared extract-method refactor\",\n");
        sb.append("  \"shared_helper\": {\n");
        sb.append("    \"strategy\": \"existing_selected_file\" | \"existing_project_file\" | \"new_helper_class\",\n");
        sb.append("    \"path\": \"relative/path/SharedOwner.java or relative/path/NewHelper.java\",\n");
        sb.append("    \"package_name\": \"package.name.for.new.helper.class only\",\n");
        sb.append("    \"class_name\": \"NewHelperClassName for new helper class only\",\n");
        sb.append("    \"imports\": [\"java.io.IOException\"],\n");
        sb.append("    \"helper_method\": \"one shared helper method declaration plus any required nested callback interface declarations, not a class or file\",\n");
        sb.append("    \"justification\": \"why this shared location is safe\"\n");
        sb.append("  },\n");
        sb.append("  \"files\": [\n");
        sb.append("    {\n");
        sb.append("      \"path\": \"relative/path/ExactlyAsListed.java\",\n");
        sb.append("      \"occurrence_replacements\": [\n");
        sb.append("        {\n");
        sb.append("          \"occurrence_id\": \"OCCURRENCE_1\",\n");
        sb.append("          \"replacement_code\": \"only the call to the shared helper or minimal replacement statements\"\n");
        sb.append("        }\n");
        sb.append("      ]\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("- Include one file object for every affected file.\n");
        sb.append("- Include one replacement entry for every occurrence in that file.\n");
        sb.append("- For strategy=existing_selected_file or existing_project_file, path must exactly match one ALLOWED SHARED HELPER TARGET. Do not invent existing paths or parent classes.\n");
        sb.append("- For strategy=existing_selected_file or existing_project_file, prefer an empty imports array and use fully-qualified names when a type simple name could conflict.\n");
        sb.append("- For strategy=new_helper_class, do not return full class source; return package_name, class_name, imports, and helper_method only. The workflow will build the file.\n");
        sb.append("- Do NOT leave the target clone unchanged if a valid cross-file Extract Method can be applied with the allowed helper targets.\n");
        return sb.toString();
    }

    static void appendSelectedFiles(StringBuilder sb, List<CrossFileSource> sources) {
        sb.append("=== SELECTED FILES ===\n");
        for (CrossFileSource source : sources) {
            sb.append("- ").append(source.relativePath).append("\n");
        }
        sb.append("\n");
    }

    static void appendWorkingSet(StringBuilder sb, List<CrossFileSource> sources) {
        sb.append("=== WORKING SET ===\n");
        for (CrossFileSource source : sources) {
            sb.append("----- FILE: ").append(source.relativePath).append(" -----\n");
            sb.append("```java\n").append(source.source).append("\n```\n\n");
        }
    }

    static void appendSharedOwnerCandidates(StringBuilder sb,
                                                    Project project,
                                                    List<CrossFileSource> sources) {
        java.util.List<CrossFileSource> candidates = collectSharedOwnerCandidates(project, sources);
        if (candidates.isEmpty()) return;
        sb.append("=== EXISTING PROJECT SHARED OWNER CANDIDATES ===\n");
        sb.append("You may use one of these existing project files as shared_helper.strategy=existing_project_file if it is the natural shared owner.\n");
        sb.append("Do not guess fields or methods; use only what is visible in this source.\n\n");
        for (CrossFileSource candidate : candidates) {
            if (candidate == null) continue;
            sb.append("----- FILE: ").append(candidate.relativePath).append(" -----\n");
            sb.append("```java\n").append(candidate.source).append("\n```\n\n");
        }
    }

    static java.util.List<CrossFileSource> collectSharedOwnerCandidates(Project project,
                                                                                List<CrossFileSource> sources) {
        java.util.ArrayList<CrossFileSource> out = new java.util.ArrayList<>();
        if (sources == null || sources.isEmpty()) return out;

        java.util.LinkedHashSet<String> selectedPaths = new java.util.LinkedHashSet<>();
        for (CrossFileSource source : sources) {
            if (source == null) continue;
            selectedPaths.add(source.absolutePath);
        }

        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (CrossFileSource source : sources) {
            if (source == null || source.source == null || source.ioFile == null) continue;
            for (CrossFileSource candidate : resolveSuperclassSourceCandidates(project, source)) {
                if (candidate == null) continue;
                String absolute = candidate.absolutePath;
                if (selectedPaths.contains(absolute) || !seen.add(absolute)) continue;
                out.add(candidate);
            }
        }
        return out;
    }

    static java.util.List<CrossFileSource> resolveSuperclassSourceCandidates(Project project, CrossFileSource source) {
        java.util.LinkedHashMap<String, CrossFileSource> out = new java.util.LinkedHashMap<>();
        if (project != null && source != null && source.vf != null) {
            try {
                java.util.List<CrossFileSource> resolved = ReadAction.compute(() -> {
                    java.util.ArrayList<CrossFileSource> candidates = new java.util.ArrayList<>();
                    PsiFile psiFile = PsiManager.getInstance(project).findFile(source.vf);
                    if (!(psiFile instanceof PsiJavaFile javaFile)) return candidates;
                    for (PsiClass psiClass : javaFile.getClasses()) {
                        collectResolvedSuperclassSources(project, psiClass, candidates);
                    }
                    return candidates;
                });
                for (CrossFileSource candidate : resolved) {
                    putResolvedSuperclassSource(out, candidate);
                }
            } catch (Throwable ignored) {}
        }

        for (String superclassName : extractSuperclassNames(source == null ? "" : source.source)) {
            putResolvedSuperclassSource(out, readSamePackageSuperclassSource(project, source, superclassName));
        }
        return new java.util.ArrayList<>(out.values());
    }

    private static void collectResolvedSuperclassSources(Project project,
                                                         PsiClass psiClass,
                                                         java.util.List<CrossFileSource> out) {
        collectResolvedSuperclassSources(project, psiClass, out, new java.util.LinkedHashSet<>(), 0);
    }

    private static void collectResolvedSuperclassSources(Project project,
                                                         PsiClass psiClass,
                                                         java.util.List<CrossFileSource> out,
                                                         java.util.Set<String> seenClasses,
                                                         int depth) {
        if (psiClass == null || out == null) return;
        if (depth > 12) return;
        for (PsiClassType type : psiClass.getExtendsListTypes()) {
            PsiClass superClass = type == null ? null : type.resolve();
            if (superClass == null) continue;
            String key = superClass.getQualifiedName();
            PsiFile containingFile = superClass == null ? null : superClass.getContainingFile();
            VirtualFile vf = containingFile == null ? null : containingFile.getVirtualFile();
            if (key == null || key.isBlank()) {
                key = vf == null ? "" : vf.getPath();
            }
            if (key.isBlank() || !seenClasses.add(key)) continue;
            if (vf == null || !vf.getName().endsWith(".java")) continue;
            if (project != null && !ProjectFileIndex.getInstance(project).isInContent(vf)) continue;
            File file = new File(vf.getPath());
            out.add(new CrossFileSource(
                    vf,
                    file,
                    vf.getPath(),
                    toProjectRelativePath(project, vf.getPath()),
                    containingFile.getText()
            ));
            collectResolvedSuperclassSources(project, superClass, out, seenClasses, depth + 1);
        }
        for (PsiClass innerClass : psiClass.getInnerClasses()) {
            collectResolvedSuperclassSources(project, innerClass, out, seenClasses, depth);
        }
    }

    private static CrossFileSource readSamePackageSuperclassSource(Project project,
                                                                   CrossFileSource source,
                                                                   String superclassName) {
        File candidateFile = resolveSamePackageJavaFile(source, superclassName);
        if (candidateFile == null || !candidateFile.isFile()) return null;
        try {
            VirtualFile vf = LocalFileSystem.getInstance().findFileByIoFile(candidateFile);
            String text = readCurrentSource(vf, candidateFile);
            String absolute = candidateFile.getAbsolutePath();
            return new CrossFileSource(
                    vf,
                    candidateFile,
                    absolute,
                    toProjectRelativePath(project, absolute),
                    text
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void putResolvedSuperclassSource(Map<String, CrossFileSource> out, CrossFileSource source) {
        if (out == null || source == null || source.absolutePath == null || source.absolutePath.isBlank()) return;
        if (!source.relativePath.endsWith(".java") && !source.absolutePath.endsWith(".java")) return;
        out.putIfAbsent(source.absolutePath, source);
    }

    static java.util.List<String> extractSuperclassNames(String source) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (source == null || source.isBlank()) return out;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\bclass\\s+[A-Za-z_$][\\w$]*\\s+extends\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)?)")
                .matcher(source);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value != null && !value.isBlank()) out.add(value.trim());
        }
        return out;
    }

    static File resolveSamePackageJavaFile(CrossFileSource source, String typeName) {
        if (source == null || source.ioFile == null || typeName == null || typeName.isBlank()) return null;
        String simpleName = typeName.trim();
        int dot = simpleName.lastIndexOf('.');
        if (dot >= 0) simpleName = simpleName.substring(dot + 1);
        if (simpleName.isBlank()) return null;
        File parent = source.ioFile.getParentFile();
        return parent == null ? null : new File(parent, simpleName + ".java");
    }

    static void appendTargetClone(StringBuilder sb, CrossFileClone selectedClone) {
        sb.append("=== TARGET CLONE OCCURRENCES ===\n");
        if (selectedClone == null) {
            sb.append("(none)\n\n");
            return;
        }
        sb.append("Clone ID: ").append(selectedClone.displayId()).append("\n");
        if (selectedClone.refactorType != null && !selectedClone.refactorType.isBlank()) {
            sb.append("Requested refactor type: ").append(selectedClone.refactorType).append("\n");
        }
        if (selectedClone.reason != null && !selectedClone.reason.isBlank()) {
            sb.append("Reason: ").append(selectedClone.reason).append("\n");
        }
        for (CrossFileOccurrenceSpec spec : buildCrossFileOccurrenceSpecs(selectedClone)) {
            if (spec == null || spec.occurrence == null || spec.occurrence.source == null) continue;
            CrossFileOccurrence occurrence = spec.occurrence;
            sb.append(spec.occurrenceId).append(":\n");
            sb.append("- path: ").append(occurrence.source.relativePath).append("\n");
            sb.append("- lines: ").append(occurrence.startLine).append("-").append(occurrence.endLine).append("\n");
            if (occurrence.snippet != null && !occurrence.snippet.isBlank()) {
                sb.append("```java\n").append(occurrence.snippet).append("\n```\n");
            }
        }
        sb.append("\n");
    }

    static String buildRetryFeedback(String heading, String detail, String feedback) {
        StringBuilder sb = new StringBuilder();
        if (heading != null && !heading.isBlank()) {
            sb.append(heading.trim()).append("\n");
        }
        if (detail != null && !detail.isBlank()) {
            sb.append("Problem: ").append(detail.trim()).append("\n");
        }
        if (feedback != null && !feedback.isBlank()) {
            sb.append("Checker feedback: ").append(feedback.trim()).append("\n");
        }
        sb.append("Required correction: if a previous JSON proposal is provided, repair it with the smallest possible changes; otherwise produce a JSON refactoring plan that prefers one shared_helper plus occurrence_replacements for every target file. Preserve the original behavior exactly.");
        return sb.toString();
    }

    static String buildCompileRetryDetail(compilation.CompileResult compileResult) {
        if (compileResult == null) return "Compilation did not return a result.";
        StringBuilder sb = new StringBuilder();
        sb.append(compileResult.summary == null ? "Compilation failed." : compileResult.summary);
        if (compileResult.errors != null && !compileResult.errors.isEmpty()) {
            sb.append("\n\nCompiler errors to fix:");
            int count = Math.min(compileResult.errors.size(), 8);
            for (int i = 0; i < count; i++) {
                compilation.CompileError error = compileResult.errors.get(i);
                if (error == null) continue;
                sb.append("\n- ");
                String file = normalizeCrossFileCompileErrorFile(null, error.file);
                if (file != null && !file.isBlank()) {
                    sb.append(file);
                    if (error.line != null) sb.append(":").append(error.line);
                    sb.append(": ");
                }
                String message = error.message == null || error.message.isBlank()
                        ? "(no message)"
                        : error.message;
                sb.append(message);
                if (error.raw != null && !error.raw.isBlank()) {
                    String raw = safeTruncate(error.raw, 500).replace("\n", " | ");
                    if (!raw.equals(message)) {
                        sb.append(" [").append(raw).append("]");
                    }
                }
            }
        }
        sb.append("\n\nRepair guidance: keep the previous helper strategy/path unless the compiler error proves that target is illegal. Do not switch to an unverified parent/owner. Do not add imports with simple-name conflicts; use fully-qualified names.");
        return sb.toString();
    }

    static String buildCrossFileRefactorCuratorPrompt(List<CrossFileSource> sources,
                                                              CrossFileClone selectedClone,
                                                              List<CrossFileRefactorPanelistOutcome> panelistOutcomes,
                                                              CrossFileRefactorContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the refactoring curator.\n");
        sb.append("You must review three candidate cross-file Extract Method refactorings.\n");
        sb.append("Choose the single best panelist candidate for downstream usefulness and compilation validation.\n");
        sb.append("If no candidate is fully acceptable, still select the least-bad parsed panelist candidate and explain residual risks in feedback.\n");
        sb.append("Do not generate a new refactoring plan. Select only one existing panelist candidate.\n\n");

        appendSelectedFiles(sb, sources);
        appendTargetClone(sb, selectedClone);
        CrossFileRefactorContextSupport.appendPromptFacts(sb, context);

        sb.append("=== PANELIST CANDIDATES ===\n");
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        for (CrossFileRefactorPanelistOutcome outcome : panelistOutcomes) {
            sb.append("[").append(outcome == null ? "" : outcome.panelistId).append("]\n");
            sb.append(gson.toJson(toRefactorPanelistJson(outcome))).append("\n\n");
        }

        sb.append("=== SELECTION RULES ===\n");
        sb.append("1) Prefer a parsed candidate that updates every affected file and keeps changes focused on the target occurrences.\n");
        sb.append("2) Prefer candidates with one shared_helper in an allowed existing target or one new helper class.\n");
        sb.append("3) Reject candidates that introduce separate helpers in each affected file, because that relocates the cross-file clone instead of removing it.\n");
        sb.append("4) Reject candidates that return whole files, omit an affected occurrence, or refactor unrelated code.\n");
        sb.append("5) Reject candidates with warnings about Java visibility, inaccessible members, undefined helper callback types, undeclared replacement variables, primitive-array generics, or invalid functional interfaces; they are likely to fail compilation.\n");
        sb.append("6) Reject candidates that access subclass private fields/methods from a helper inserted in a shared owner.\n");
        sb.append("7) Reject candidates whose existing_project_file path is not in ALLOWED SHARED HELPER TARGETS, even if a panelist claims it is a common superclass.\n");
        sb.append("8) Reject candidates that add imports with simple-name conflicts; prefer candidates using fully-qualified names.\n");
        sb.append("9) If every candidate failed to parse or apply, select the least-bad candidate and explain why it is still risky.\n\n");

        sb.append("=== OUTPUT FORMAT ===\n");
        sb.append("Return ONLY a JSON object with this exact shape.\n");
        sb.append("{\n");
        sb.append("  \"decision\": \"select_panelist\",\n");
        sb.append("  \"selected_panelist_id\": \"P1\",\n");
        sb.append("  \"matched_categories\": [\"EXTRACT_METHOD_CONFIRMED\"],\n");
        sb.append("  \"summary\": \"short explanation of why this candidate is best or least-bad\",\n");
        sb.append("  \"feedback\": \"optional short guidance about residual risk\",\n");
        sb.append("  \"confidence\": 0.85\n");
        sb.append("}\n");
        return sb.toString();
    }

    static JsonObject toRefactorPanelistJson(CrossFileRefactorPanelistOutcome outcome) {
        JsonObject obj = new JsonObject();
        obj.addProperty("panelist_id", outcome == null ? "" : outcome.panelistId);
        obj.addProperty("parsed", outcome != null && outcome.parsed);
        obj.addProperty("error", outcome == null ? "" : outcome.error);
        CrossFileRefactorResult result = outcome == null ? null : outcome.result;
        obj.addProperty("status", result == null ? "" : result.status);
        obj.addProperty("summary", result == null ? "" : result.summary);
        obj.addProperty("message", result == null ? "" : result.message);

        JsonArray warnings = new JsonArray();
        if (result != null) {
            for (String warning : result.warnings) {
                warnings.add(warning == null ? "" : warning);
            }
        }
        obj.add("warnings", warnings);

        JsonArray changedFiles = new JsonArray();
        if (result != null) {
            for (CrossFileSource source : result.newSourcesByFile.keySet()) {
                if (source != null) changedFiles.add(source.relativePath);
            }
            for (CrossFileNewSource source : result.newFilesByPath.values()) {
                if (source != null) changedFiles.add(source.relativePath);
            }
        }
        obj.add("changed_files", changedFiles);
        obj.addProperty("raw_response_preview", safeTruncate(outcome == null ? "" : outcome.rawResponse, CURATOR_CANDIDATE_MAX_CHARS));
        return obj;
    }

    static CrossFilePanelistSelection parseCrossFilePanelistSelection(String raw) {
        CrossFilePanelistSelection result = new CrossFilePanelistSelection();
        result.rawResponse = raw == null ? "" : raw;
        String json = extractJsonObjectSubstring(raw);
        if (json == null || json.isBlank()) {
            result.error = "Could not extract curator JSON.";
            return result;
        }
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            result.parsed = true;
            result.selectedPanelistId = getJsonString(
                    obj,
                    "selected_panelist_id",
                    "selectedPanelistId",
                    "panelist_id",
                    "panelistId",
                    "selected_candidate_id",
                    "selectedCandidateId",
                    "candidate_id",
                    "candidateId"
            );
            result.summary = getJsonString(obj, "summary", "message", "reason");
            result.feedback = getJsonString(obj, "feedback", "fix", "recommendation");
            result.confidence = getJsonDouble(obj, 0.0d, "confidence", "score");
            JsonArray categories = getJsonArray(obj, "matched_categories", "matchedCategories", "categories");
            if (categories != null) {
                for (JsonElement element : categories) {
                    if (element == null || element.isJsonNull()) continue;
                    try {
                        String category = element.getAsString();
                        if (category != null && !category.isBlank()) result.matchedCategories.add(category);
                    } catch (Throwable ignored) {}
                }
            }
            return result;
        } catch (Throwable t) {
            result.error = "Could not parse curator JSON: " + t.getMessage();
            return result;
        }
    }

    static CrossFileRefactorPanelistOutcome resolveSelectedRefactorPanelist(CrossFilePanelistSelection selection,
                                                                                   List<CrossFileRefactorPanelistOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return null;
        String selectedId = selection == null ? "" : selection.selectedPanelistId;
        if (selectedId != null && !selectedId.isBlank()) {
            String normalized = selectedId.trim().toUpperCase(java.util.Locale.ROOT);
            for (CrossFileRefactorPanelistOutcome outcome : outcomes) {
                if (outcome != null && normalized.equals(outcome.panelistId.toUpperCase(java.util.Locale.ROOT))) {
                    return outcome;
                }
            }
        }
        return highestScoringRefactorPanelistOutcome(outcomes);
    }

    static CrossFileRefactorPanelistOutcome highestScoringRefactorPanelistOutcome(
            List<CrossFileRefactorPanelistOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return null;
        CrossFileRefactorPanelistOutcome best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (CrossFileRefactorPanelistOutcome outcome : outcomes) {
            if (outcome == null) continue;
            double score = scoreRefactorPanelistOutcome(outcome);
            if (best == null || score > bestScore) {
                best = outcome;
                bestScore = score;
            }
        }
        return best == null ? outcomes.get(0) : best;
    }

    private static double scoreRefactorPanelistOutcome(CrossFileRefactorPanelistOutcome outcome) {
        if (outcome == null || outcome.result == null) return 0.0d;
        CrossFileRefactorResult result = outcome.result;
        double score = 0.0d;
        if (result.parsed) score += 0.4d;
        if (result.hasChanges()) score += 0.3d;
        if ("refactored".equalsIgnoreCase(result.status == null ? "" : result.status.trim())) score += 0.2d;
        if (result.warnings.isEmpty()) score += 0.1d;
        return score;
    }

    static String buildRefactorSelectionFailureMessage(CrossFilePanelistSelection selection,
                                                               List<CrossFileRefactorPanelistOutcome> outcomes) {
        StringBuilder sb = new StringBuilder("No valid refactoring panelist candidate could be applied.");
        if (selection != null && selection.error != null && !selection.error.isBlank()) {
            sb.append(" Curator error: ").append(selection.error);
        }
        if (selection != null && selection.summary != null && !selection.summary.isBlank()) {
            sb.append(" Curator summary: ").append(safeTruncate(selection.summary, 220).replace("\n", " "));
        }
        java.util.ArrayList<String> diagnostics = new java.util.ArrayList<>();
        if (outcomes != null) {
            for (CrossFileRefactorPanelistOutcome outcome : outcomes) {
                if (outcome == null) continue;
                String detail = outcome.error;
                if ((detail == null || detail.isBlank()) && outcome.result != null) {
                    detail = outcome.result.message;
                }
                diagnostics.add(outcome.panelistId + "=" + (detail == null || detail.isBlank() ? "unavailable" : safeTruncate(detail, 160).replace("\n", " ")));
            }
        }
        if (!diagnostics.isEmpty()) {
            sb.append(" Panelist diagnostics: ").append(String.join("; ", diagnostics));
        }
        return sb.toString();
    }

    static CrossFileRefactorResult parseCrossFileRefactorResult(String raw,
                                                                        Project project,
                                                                        List<CrossFileSource> sources,
                                                                        CrossFileClone selectedClone,
                                                                        CrossFileRefactorContext context) {
        CrossFileRefactorResult result = new CrossFileRefactorResult();
        String json = extractJsonObjectSubstring(raw);
        if (json == null || json.isBlank()) {
            result.message = "Could not extract JSON refactor plan from LLM output.";
            return result;
        }

        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            result.parsed = true;
            result.rawPlanJson = json;
            result.status = getJsonString(obj, "status", "result");
            result.summary = getJsonString(obj, "summary", "message", "reason");
            if (result.status == null || result.status.isBlank()) {
                result.status = "refactored";
            }
            if ("failed".equalsIgnoreCase(result.status.trim())
                    || "no_clones".equalsIgnoreCase(result.status.trim())) {
                return result;
            }

            Map<String, CrossFileSource> index = buildCrossFileSourceIndex(sources);
            CrossFileSharedHelperPlan sharedHelperPlan = parseCrossFileSharedHelperPlan(obj, project, sources, index);
            if (sharedHelperPlan == null || !sharedHelperPlan.isCentralizedStrategy()) {
                result.parsed = false;
                result.message = "Refactoring Agent must provide a shared_helper with strategy existing_selected_file, existing_project_file, or new_helper_class.";
                return result;
            }
            String sharedHelperIssue = validateCrossFileSharedHelperPlan(sharedHelperPlan, selectedClone, context);
            if (!sharedHelperIssue.isBlank()) {
                result.parsed = false;
                result.message = sharedHelperIssue;
                return result;
            }
            JsonArray files = getJsonArray(obj, "files", "modified_files", "modifiedFiles", "changes");
            if (files == null) {
                result.parsed = false;
                result.message = "JSON parsed but no files array with occurrence_replacements was present.";
                return result;
            }

            java.util.List<CrossFileOccurrenceSpec> specs = buildCrossFileOccurrenceSpecs(selectedClone);
            for (JsonElement element : files) {
                if (element == null || element.isJsonNull() || !element.isJsonObject()) continue;
                JsonObject fileObj = element.getAsJsonObject();
                String path = getJsonString(fileObj, "path", "file", "file_path", "filePath", "relative_path", "relativePath");
                if (path == null || path.isBlank()) {
                    result.warnings.add("Skipping change with missing path.");
                    continue;
                }
                CrossFileSource target = resolveCrossFileSource(path, index);
                if (target == null) {
                    result.warnings.add("Skipping change for unknown file path: " + path);
                    continue;
                }

                JsonArray replacementsJson = getJsonArray(
                        fileObj,
                        "occurrence_replacements",
                        "occurrenceReplacements",
                        "replacements",
                        "refactored_occurrences",
                        "refactoredOccurrences"
                );
                java.util.List<CrossFileOccurrenceRewrite> replacements =
                        parseCrossFileOccurrenceReplacements(replacementsJson, specs, target);
                String replacementIssue = validateCrossFileOccurrenceReplacements(sharedHelperPlan, replacements, target);
                if (!replacementIssue.isBlank()) {
                    result.parsed = false;
                    result.message = replacementIssue;
                    return result;
                }
                try {
                    String newSource = applyCrossFileOccurrenceReplacements(target, selectedClone, replacements);
                    if (!newSource.equals(target.source)) {
                        result.newSourcesByFile.put(target, newSource);
                    } else {
                        result.warnings.add("Structured plan produced unchanged source for: " + path);
                    }
                } catch (IllegalStateException e) {
                    result.warnings.add("Could not apply structured plan for " + target.relativePath + ": " + e.getMessage());
                }
            }

            applyCrossFileSharedHelperPlan(result, sources, index, selectedClone, sharedHelperPlan);
            validateRequiredCrossFileChanges(result, selectedClone);
            return result;
        } catch (Throwable t) {
            result.parsed = false;
            result.message = "Could not parse cross-file refactor JSON: " + t.getMessage();
            return result;
        }
    }

    static java.util.List<CrossFileOccurrenceRewrite> parseCrossFileOccurrenceReplacements(JsonArray arr,
                                                                                                    List<CrossFileOccurrenceSpec> orderedSpecs,
                                                                                                    CrossFileSource target) {
        java.util.ArrayList<CrossFileOccurrenceRewrite> out = new java.util.ArrayList<>();
        if (arr == null) return out;
        java.util.List<CrossFileOccurrenceSpec> targetSpecs = filterOccurrenceSpecsForSource(orderedSpecs, target);
        int inferredIndex = 0;
        for (JsonElement element : arr) {
            if (element == null || element.isJsonNull()) continue;
            CrossFileOccurrenceRewrite rewrite = new CrossFileOccurrenceRewrite();
            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                rewrite.occurrenceId = getJsonString(obj, "occurrence_id", "occurrenceId", "id");
                rewrite.replacementCode = stripOptionalJavaFence(getJsonString(
                        obj,
                        "replacement_code",
                        "replacementCode",
                        "refactored_code",
                        "refactoredCode",
                        "replacement",
                        "code",
                        "updated_code",
                        "updatedCode"
                ));
            } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                rewrite.replacementCode = stripOptionalJavaFence(element.getAsString());
            } else {
                continue;
            }

            if ((rewrite.occurrenceId == null || rewrite.occurrenceId.isBlank())
                    && inferredIndex < targetSpecs.size()) {
                rewrite.occurrenceId = targetSpecs.get(inferredIndex).occurrenceId;
            }
            if (rewrite.occurrenceId == null || rewrite.occurrenceId.isBlank()) continue;
            if (rewrite.replacementCode == null) rewrite.replacementCode = "";
            out.add(rewrite);
            inferredIndex++;
        }
        return out;
    }

    static CrossFileSharedHelperPlan parseCrossFileSharedHelperPlan(JsonObject obj,
                                                                            Project project,
                                                                            List<CrossFileSource> sources,
                                                                            Map<String, CrossFileSource> sourceIndex) {
        JsonObject helperObj = getJsonObject(obj, "shared_helper", "sharedHelper", "helper", "extracted_helper", "extractedHelper");
        if (helperObj == null) return null;

        CrossFileSharedHelperPlan plan = new CrossFileSharedHelperPlan();
        plan.strategy = getJsonString(helperObj, "strategy", "type", "kind");
        plan.path = getJsonString(helperObj, "path", "file", "file_path", "filePath", "relative_path", "relativePath");
        plan.packageName = getJsonString(helperObj, "package_name", "packageName", "package");
        plan.className = getJsonString(helperObj, "class_name", "className", "helper_class", "helperClass");
        plan.helperMethod = stripOptionalJavaFence(getJsonString(
                helperObj,
                "helper_method",
                "helperMethod",
                "method",
                "extracted_method",
                "extractedMethod"
        ));
        plan.justification = getJsonString(helperObj, "justification", "reason", "summary");
        plan.imports.addAll(parseJsonStringArray(getJsonArray(helperObj, "imports", "import_list", "importList")));

        CrossFileSource selectedTarget = resolveCrossFileSource(plan.path, sourceIndex);
        CrossFileSource projectTarget = selectedTarget == null
                ? readExistingProjectSharedSource(project, sources, plan.path)
                : null;
        if (projectTarget != null) {
            putCrossFileKey(sourceIndex, projectTarget.absolutePath, projectTarget);
            putCrossFileKey(sourceIndex, projectTarget.relativePath, projectTarget);
            putCrossFileKey(sourceIndex, projectTarget.vf == null ? "" : projectTarget.vf.getName(), projectTarget);
        }

        if (plan.strategy == null || plan.strategy.isBlank()) {
            if (selectedTarget != null) {
                plan.strategy = "existing_selected_file";
            } else if (projectTarget != null) {
                plan.strategy = "existing_project_file";
            } else {
                plan.strategy = inferSharedHelperStrategy(plan, sourceIndex);
            }
        }

        if (plan.isExistingFileStrategy()) {
            plan.existingTarget = selectedTarget == null ? projectTarget : selectedTarget;
        } else if ("new_helper_class".equalsIgnoreCase(plan.strategy)) {
            normalizeNewHelperPlan(plan, sources);
            plan.newHelperPathAlreadyExists =
                    selectedTarget != null || projectTarget != null || newHelperPathAlreadyExists(project, plan);
        }
        return plan;
    }

    static String validateCrossFileSharedHelperPlan(CrossFileSharedHelperPlan plan,
                                                            CrossFileClone selectedClone,
                                                            CrossFileRefactorContext context) {
        if (plan == null || plan.helperMethod == null) return "";
        String helper = plan.helperMethod;
        String helperCode = stripJavaComments(helper);
        java.util.ArrayList<String> issues = new java.util.ArrayList<>();

        if (plan.isExistingFileStrategy() && context != null
                && !context.allowsExistingTarget(plan.strategy, plan.path)) {
            issues.add("shared_helper target " + plan.strategy + ":" + plan.path
                    + " is not in the PSI-verified allowed helper target list");
        }

        if (usesGenericPrimitiveArrayAbstraction(helperCode) && selectedCloneUsesPrimitiveArrays(selectedClone)) {
            issues.add("helper_method uses generic T[]/U[] arrays, which Java cannot call with primitive byte[]/char[] clone arrays");
        }
        if (hasInvalidFunctionalInterfaceAnnotation(helperCode)) {
            issues.add("helper_method marks an interface as @FunctionalInterface even though it declares multiple abstract methods");
        }

        CrossFileSource owner = plan.existingTarget;
        String importOwnerSource = plan.isExistingFileStrategy() && owner != null && owner.source != null
                ? owner.source
                : "";
        java.util.List<String> importConflicts = findJavaImportConflicts(importOwnerSource, plan.imports);
        if (!importConflicts.isEmpty()) {
            String location = plan.isExistingFileStrategy() && owner != null
                    ? " with existing imports in " + owner.relativePath
                    : " within requested imports";
            issues.add("shared_helper.imports conflict" + location + ": "
                    + String.join(", ", importConflicts)
                    + ". Use fully-qualified names instead of adding these imports");
        }

        if (plan.isExistingFileStrategy() && owner != null) {
            String ownerSource = owner.source == null ? "" : owner.source;
            java.util.ArrayList<String> inaccessible = new java.util.ArrayList<>();
            if (containsMethodCall(helperCode, "makeSpace") && !sourceDeclaresMethod(ownerSource, "makeSpace")) {
                inaccessible.add("makeSpace(int)");
            }
            if (containsMethodCall(helperCode, "flushBuffer") && !sourceDeclaresMethod(ownerSource, "flushBuffer")) {
                inaccessible.add("flushBuffer()");
            }
            if (helperCode.contains(".out") && !sourceDeclaresField(ownerSource, "out")) {
                inaccessible.add("out");
            }
            if (helperCode.matches("(?s).*\\)\\s*\\.\\s*buff\\b.*") && !sourceDeclaresField(ownerSource, "buff")) {
                inaccessible.add("subclass private field buff");
            }
            if (!inaccessible.isEmpty()) {
                issues.add("helper_method is inserted into " + owner.relativePath
                        + " but references unavailable member(s): " + String.join(", ", inaccessible));
            }
            java.util.List<String> missingTypes = findMissingHelperTypes(plan, ownerSource);
            if (!missingTypes.isEmpty()) {
                issues.add("helper_method references undefined helper type(s): " + String.join(", ", missingTypes));
            }
        }

        if (issues.isEmpty()) return "";
        return "Refactoring Agent produced a helper that is likely to fail Java compilation: "
                + String.join("; ", issues)
                + ". Put private-member accesses in caller-side replacement_code callbacks/arguments, or use a valid new helper class when no existing target can own the helper.";
    }

    static String validateCrossFileOccurrenceReplacements(CrossFileSharedHelperPlan plan,
                                                                  java.util.List<CrossFileOccurrenceRewrite> replacements,
                                                                  CrossFileSource target) {
        if (replacements == null || replacements.isEmpty()) return "";
        java.util.ArrayList<String> issues = new java.util.ArrayList<>();
        for (CrossFileOccurrenceRewrite rewrite : replacements) {
            if (rewrite == null || rewrite.replacementCode == null) continue;
            String code = stripJavaComments(rewrite.replacementCode);
            String occurrenceId = rewrite.occurrenceId == null || rewrite.occurrenceId.isBlank()
                    ? "occurrence"
                    : rewrite.occurrenceId;
            if (referencesUndeclaredLimit(code)
                    && (target == null || !sourceDeclaresField(target.source, "limit"))) {
                issues.add(occurrenceId + " replacement_code uses limit without declaring it; include int limit = getLimitInternal() or pass getLimitInternal() directly");
            }
            if (passesEndAsReferenceWithoutWriteBack(code)) {
                issues.add(occurrenceId + " replacement_code passes new int[]{end} but never writes the updated value back to end");
            }
            java.util.List<String> missingTypes = findMissingReplacementTypes(plan, code);
            if (!missingTypes.isEmpty()) {
                issues.add(occurrenceId + " replacement_code references undefined helper type(s): " + String.join(", ", missingTypes));
            }
        }
        if (issues.isEmpty()) return "";
        return "Refactoring Agent produced replacement_code that is likely to fail Java compilation: "
                + String.join("; ", issues)
                + ". Make replacement_code self-contained and only reference helper types actually declared by shared_helper.helper_method or existing code.";
    }

    static void validateRequiredCrossFileChanges(CrossFileRefactorResult result, CrossFileClone selectedClone) {
        if (result == null || !result.parsed) return;
        java.util.LinkedHashSet<CrossFileSource> requiredSources = selectedClone == null
                ? new java.util.LinkedHashSet<>()
                : selectedClone.affectedSources();
        if (requiredSources.isEmpty()) return;

        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        java.util.ArrayList<String> unchanged = new java.util.ArrayList<>();
        for (CrossFileSource requiredSource : requiredSources) {
            if (requiredSource == null) continue;
            if (!result.newSourcesByFile.containsKey(requiredSource)) {
                missing.add(requiredSource.relativePath);
            } else if (result.newSourcesByFile.get(requiredSource).equals(requiredSource.source)) {
                unchanged.add(requiredSource.relativePath);
            }
        }
        if (!missing.isEmpty() || !unchanged.isEmpty()) {
            result.parsed = false;
            StringBuilder msg = new StringBuilder("Refactoring Agent did not update every affected cross-file clone file.");
            if (!missing.isEmpty()) {
                msg.append(" Missing changed source for: ").append(String.join(", ", missing)).append(".");
            }
            if (!unchanged.isEmpty()) {
                msg.append(" Returned unchanged source for: ").append(String.join(", ", unchanged)).append(".");
            }
            result.message = msg.toString();
        }
    }

}
