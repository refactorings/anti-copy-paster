package org.jetbrains.research.anticopypaster.workflow;

import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.readCurrentSource;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.resolveCrossFileSource;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.toProjectRelativePath;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileTextEditSupport.findMatchingBrace;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileTextEditSupport.firstOccurrenceAnchor;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileTextEditSupport.normalizeJavaImportName;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileTextEditSupport.reindentBlock;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class CrossFileSharedHelperSupport {

    private CrossFileSharedHelperSupport() {}

    static boolean usesGenericPrimitiveArrayAbstraction(String helperMethod) {
        if (helperMethod == null || helperMethod.isBlank()) return false;
        java.util.regex.Matcher typeParams = java.util.regex.Pattern
                .compile("<\\s*([A-Z][A-Za-z0-9_$]*(?:\\s*,\\s*[A-Z][A-Za-z0-9_$]*)*)\\s*>")
                .matcher(helperMethod);
        while (typeParams.find()) {
            String[] names = typeParams.group(1).split(",");
            for (String name : names) {
                String type = name.trim();
                if (type.isBlank()) continue;
                java.util.regex.Pattern arrayUse = java.util.regex.Pattern
                        .compile("\\b" + java.util.regex.Pattern.quote(type) + "\\s*\\[\\s*]");
                if (arrayUse.matcher(helperMethod).find()) return true;
            }
        }
        return false;
    }

    static boolean selectedCloneUsesPrimitiveArrays(CrossFileClone selectedClone) {
        if (selectedClone == null || selectedClone.occurrences == null) return false;
        for (CrossFileOccurrence occurrence : selectedClone.occurrences) {
            String text = occurrence == null ? "" : occurrence.snippet;
            if (text.contains("byte[]") || text.contains("char[]")
                    || text.contains("short[]") || text.contains("int[]")
                    || text.contains("long[]") || text.contains("float[]")
                    || text.contains("double[]") || text.contains("boolean[]")) {
                return true;
            }
        }
        return false;
    }

    static boolean hasInvalidFunctionalInterfaceAnnotation(String helperMethod) {
        if (helperMethod == null || !helperMethod.contains("@FunctionalInterface")) return false;
        int searchFrom = 0;
        while (searchFrom < helperMethod.length()) {
            int annotation = helperMethod.indexOf("@FunctionalInterface", searchFrom);
            if (annotation < 0) return false;
            int interfaceIdx = helperMethod.indexOf("interface", annotation);
            if (interfaceIdx < 0) return false;
            int openBrace = helperMethod.indexOf('{', interfaceIdx);
            if (openBrace < 0) return false;
            int closeBrace = findMatchingBrace(helperMethod, openBrace);
            if (closeBrace < 0) return false;
            String body = helperMethod.substring(openBrace + 1, closeBrace);
            int abstractMethodCount = 0;
            for (String line : body.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isBlank()
                        || trimmed.startsWith("//")
                        || trimmed.startsWith("*")
                        || trimmed.startsWith("@")
                        || trimmed.startsWith("default ")
                        || trimmed.startsWith("static ")
                        || trimmed.startsWith("private ")) {
                    continue;
                }
                if (trimmed.contains("(") && trimmed.endsWith(";")) {
                    abstractMethodCount++;
                }
            }
            if (abstractMethodCount > 1) return true;
            searchFrom = closeBrace + 1;
        }
        return false;
    }

    static boolean containsMethodCall(String code, String methodName) {
        if (code == null || methodName == null || methodName.isBlank()) return false;
        return java.util.regex.Pattern
                .compile("\\b" + java.util.regex.Pattern.quote(methodName) + "\\s*\\(")
                .matcher(code)
                .find();
    }

    static String stripJavaComments(String code) {
        if (code == null || code.isBlank()) return "";
        return code
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    static boolean referencesUndeclaredLimit(String code) {
        if (code == null || code.isBlank()) return false;
        if (!java.util.regex.Pattern.compile("\\blimit\\b").matcher(code).find()) return false;
        return !java.util.regex.Pattern
                .compile("\\b(?:int|final\\s+int|var)\\s+limit\\b")
                .matcher(code)
                .find();
    }

    static boolean passesEndAsReferenceWithoutWriteBack(String code) {
        if (code == null || code.isBlank()) return false;
        boolean passesEnd = java.util.regex.Pattern
                .compile("new\\s+int\\s*\\[\\s*]\\s*\\{\\s*end\\s*}")
                .matcher(code)
                .find();
        if (!passesEnd) return false;
        return !java.util.regex.Pattern
                .compile("\\bend\\s*=")
                .matcher(code)
                .find();
    }

    static java.util.List<String> findMissingHelperTypes(CrossFileSharedHelperPlan plan, String ownerSource) {
        if (plan == null || plan.helperMethod == null) return java.util.List.of();
        String signature = helperDeclarationPrefix(plan.helperMethod);
        return findMissingCustomTypes(signature, plan, ownerSource);
    }

    static java.util.List<String> findMissingReplacementTypes(CrossFileSharedHelperPlan plan, String code) {
        if (code == null || code.isBlank()) return java.util.List.of();
        String ownerSource = plan == null || plan.existingTarget == null ? "" : plan.existingTarget.source;
        java.util.LinkedHashSet<String> referenced = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\bnew\\s+([A-Z][A-Za-z0-9_$]*)\\b|\\b([A-Z][A-Za-z0-9_$]*)\\s*<")
                .matcher(code);
        while (matcher.find()) {
            String type = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            if (type != null && !type.isBlank()) referenced.add(type);
        }
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        for (String type : referenced) {
            if (isMissingCustomType(type, plan, ownerSource)) missing.add(type);
        }
        return missing;
    }

    static java.util.List<String> findMissingCustomTypes(String text,
                                                                 CrossFileSharedHelperPlan plan,
                                                                 String ownerSource) {
        if (text == null || text.isBlank()) return java.util.List.of();
        java.util.LinkedHashSet<String> referenced = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\b([A-Z][A-Za-z0-9_$]*)\\b")
                .matcher(text);
        while (matcher.find()) {
            String type = matcher.group(1);
            if (type != null && typeLooksLikeHelperCallback(type)) referenced.add(type);
        }
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        for (String type : referenced) {
            if (isMissingCustomType(type, plan, ownerSource)) missing.add(type);
        }
        return missing;
    }

    static String helperDeclarationPrefix(String helperMethod) {
        if (helperMethod == null) return "";
        int brace = helperMethod.indexOf('{');
        if (brace < 0) return helperMethod;
        return helperMethod.substring(0, brace);
    }

    static boolean typeLooksLikeHelperCallback(String type) {
        if (type == null || type.isBlank()) return false;
        if (isKnownJavaType(type)) return false;
        return type.endsWith("Writer")
                || type.endsWith("Reader")
                || type.endsWith("Callback")
                || type.endsWith("Handler")
                || type.endsWith("Operation")
                || type.endsWith("Copier")
                || type.endsWith("Strategy");
    }

    static boolean isMissingCustomType(String type, CrossFileSharedHelperPlan plan, String ownerSource) {
        if (type == null || type.isBlank() || isKnownJavaType(type)) return false;
        String helper = plan == null || plan.helperMethod == null ? "" : plan.helperMethod;
        if (sourceDeclaresType(helper, type) || sourceDeclaresType(ownerSource, type)) return false;
        if (plan != null) {
            for (String importName : plan.imports) {
                String normalized = normalizeJavaImportName(importName);
                if (normalized.equals(type) || normalized.endsWith("." + type)) return false;
            }
        }
        return true;
    }

    static boolean isKnownJavaType(String type) {
        if (type == null) return false;
        return switch (type) {
            case "Object", "String", "Integer", "Long", "Boolean", "Double", "Float", "Short", "Byte",
                    "Character", "Void", "IOException", "Runnable", "AutoCloseable", "Closeable",
                    "Consumer", "BiConsumer", "Function", "BiFunction", "Supplier", "Predicate",
                    "ObjIntConsumer", "IntConsumer", "IntFunction", "List", "Map", "Set" -> true;
            default -> false;
        };
    }

    static boolean sourceDeclaresMethod(String source, String methodName) {
        if (source == null || methodName == null || methodName.isBlank()) return false;
        return java.util.regex.Pattern
                .compile("\\b" + java.util.regex.Pattern.quote(methodName) + "\\s*\\(")
                .matcher(source)
                .find();
    }

    static boolean sourceDeclaresType(String source, String typeName) {
        if (source == null || typeName == null || typeName.isBlank()) return false;
        return java.util.regex.Pattern
                .compile("\\b(?:class|interface|enum|record)\\s+" + java.util.regex.Pattern.quote(typeName) + "\\b")
                .matcher(source)
                .find();
    }

    static boolean sourceDeclaresField(String source, String fieldName) {
        if (source == null || fieldName == null || fieldName.isBlank()) return false;
        return java.util.regex.Pattern
                .compile("(?m)^\\s*(?:private|protected|public)?\\s*(?:static\\s+)?(?:final\\s+)?[\\w<>\\[\\]., ?]+\\s+[^;=]*\\b"
                        + java.util.regex.Pattern.quote(fieldName)
                        + "\\b\\s*(?:[=;,])")
                .matcher(source)
                .find();
    }

    static String inferSharedHelperStrategy(CrossFileSharedHelperPlan plan,
                                                    Map<String, CrossFileSource> sourceIndex) {
        if (plan == null) return "";
        if (plan.path != null && !plan.path.isBlank() && resolveCrossFileSource(plan.path, sourceIndex) != null) {
            return "existing_selected_file";
        }
        if (plan.path != null && !plan.path.isBlank() && !plan.path.endsWith(".java")) {
            return "";
        }
        if ((plan.className != null && !plan.className.isBlank())
                || (plan.path != null && !plan.path.isBlank() && plan.path.endsWith(".java"))) {
            return "new_helper_class";
        }
        return "";
    }

    static void normalizeNewHelperPlan(CrossFileSharedHelperPlan plan, List<CrossFileSource> sources) {
        if (plan == null) return;
        if (plan.className == null || plan.className.isBlank()) {
            String path = plan.path == null ? "" : plan.path.replace("\\", "/");
            int slash = path.lastIndexOf('/');
            String fileName = slash >= 0 ? path.substring(slash + 1) : path;
            if (fileName.endsWith(".java")) {
                plan.className = fileName.substring(0, fileName.length() - ".java".length());
            }
        }
        if (plan.className == null || plan.className.isBlank()) {
            plan.className = "CrossFileCloneHelper";
        }
        if (plan.packageName == null || plan.packageName.isBlank()) {
            plan.packageName = inferCommonPackage(sources);
        }
        if (plan.path == null || plan.path.isBlank()) {
            String packagePath = plan.packageName == null || plan.packageName.isBlank()
                    ? ""
                    : plan.packageName.replace('.', '/') + "/";
            plan.path = packagePath + plan.className + ".java";
        }
        if (!plan.path.endsWith(".java")) {
            plan.path = plan.path + ".java";
        }
        plan.relativePath = normalizeNewFileRelativePath(plan.path, sources);
        plan.ioFile = resolveNewHelperFile(plan.relativePath, sources);
        plan.publicClass = helperClassNeedsPublicVisibility(plan.packageName, sources);
    }

    static void applyCrossFileSharedHelperPlan(CrossFileRefactorResult result,
                                                       List<CrossFileSource> sources,
                                                       Map<String, CrossFileSource> sourceIndex,
                                                       CrossFileClone selectedClone,
                                                       CrossFileSharedHelperPlan plan) {
        if (result == null || plan == null) return;
        if (!plan.isCentralizedStrategy()) return;
        if (plan.helperMethod == null || plan.helperMethod.isBlank()) {
            result.parsed = false;
            result.message = "Shared helper strategy was selected, but shared_helper.helper_method is missing.";
            return;
        }

        if (plan.isExistingFileStrategy()) {
            CrossFileSource target = plan.existingTarget == null
                    ? resolveCrossFileSource(plan.path, sourceIndex)
                    : plan.existingTarget;
            if (target == null) {
                result.parsed = false;
                result.message = "Shared helper target could not be resolved as a selected or existing project file: " + plan.path;
                return;
            }
            String base = result.newSourcesByFile.getOrDefault(target, target.source);
            int anchorPosition = firstOccurrenceAnchor(target, selectedClone);
            String updated = CrossFileTextEditSupport.insertHelperMethod(base, plan.helperMethod, anchorPosition);
            updated = CrossFileTextEditSupport.insertJavaImports(updated, plan.imports);
            if (!updated.equals(target.source)) {
                result.newSourcesByFile.put(target, updated);
            }
            return;
        }

        if ("new_helper_class".equalsIgnoreCase(plan.strategy)) {
            normalizeNewHelperPlan(plan, sources);
            String source = buildNewHelperClassSource(plan);
            if (source.isBlank()) {
                result.parsed = false;
                result.message = "Could not build new helper class source.";
                return;
            }
            result.newFilesByPath.put(plan.relativePath, new CrossFileNewSource(plan.relativePath, plan.ioFile, source));
        }
    }

    static String buildNewHelperClassSource(CrossFileSharedHelperPlan plan) {
        if (plan == null || plan.helperMethod == null || plan.helperMethod.isBlank()) return "";
        String className = plan.className == null || plan.className.isBlank() ? "CrossFileCloneHelper" : plan.className.trim();
        StringBuilder sb = new StringBuilder();
        if (plan.packageName != null && !plan.packageName.isBlank()) {
            sb.append("package ").append(plan.packageName.trim()).append(";\n\n");
        }
        java.util.LinkedHashSet<String> imports = new java.util.LinkedHashSet<>();
        for (String value : plan.imports) {
            if (value == null || value.isBlank()) continue;
            String normalized = value.trim();
            if (normalized.startsWith("import ")) {
                normalized = normalized.substring("import ".length()).trim();
            }
            if (normalized.endsWith(";")) {
                normalized = normalized.substring(0, normalized.length() - 1).trim();
            }
            if (!normalized.isBlank()) imports.add(normalized);
        }
        for (String importValue : imports) {
            sb.append("import ").append(importValue).append(";\n");
        }
        if (!imports.isEmpty()) sb.append("\n");

        sb.append(plan.publicClass ? "public final class " : "final class ").append(className).append(" {\n");
        sb.append("    private ").append(className).append("() {}\n\n");
        sb.append(reindentBlock(plan.helperMethod, "    ")).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    static String inferCommonPackage(List<CrossFileSource> sources) {
        java.util.ArrayList<String> packages = new java.util.ArrayList<>();
        if (sources != null) {
            for (CrossFileSource source : sources) {
                String packageName = extractPackageName(source == null ? "" : source.source);
                if (packageName != null && !packageName.isBlank()) packages.add(packageName);
            }
        }
        if (packages.isEmpty()) return "";
        String[] parts = packages.get(0).split("\\.");
        int keep = parts.length;
        for (int i = 1; i < packages.size(); i++) {
            String[] other = packages.get(i).split("\\.");
            keep = Math.min(keep, other.length);
            for (int j = 0; j < keep; j++) {
                if (!parts[j].equals(other[j])) {
                    keep = j;
                    break;
                }
            }
        }
        if (keep <= 0) return packages.get(0);
        return String.join(".", java.util.Arrays.copyOf(parts, keep));
    }

    static boolean helperClassNeedsPublicVisibility(String helperPackage, List<CrossFileSource> sources) {
        String helper = helperPackage == null ? "" : helperPackage.trim();
        if (sources == null || sources.isEmpty()) return false;
        for (CrossFileSource source : sources) {
            String packageName = extractPackageName(source == null ? "" : source.source);
            String caller = packageName == null ? "" : packageName.trim();
            if (!caller.equals(helper)) {
                return true;
            }
        }
        return false;
    }

    static String extractPackageName(String source) {
        if (source == null || source.isBlank()) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)\\s*;")
                .matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    static String normalizeNewFileRelativePath(String path, List<CrossFileSource> sources) {
        if (path == null || path.isBlank()) return "CrossFileCloneHelper.java";
        String normalized = path.trim().replace("\\", "/");
        File asFile = new File(normalized);
        if (asFile.isAbsolute()) {
            String base = inferProjectBasePath(sources);
            String abs = asFile.getAbsolutePath().replace("\\", "/");
            if (base != null && !base.isBlank()) {
                String normalizedBase = base.replace("\\", "/");
                if (abs.startsWith(normalizedBase)) {
                    String rel = abs.substring(normalizedBase.length());
                    if (rel.startsWith("/")) rel = rel.substring(1);
                    if (!rel.isBlank()) return rel;
                }
            }
            return asFile.getName();
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    static File resolveNewHelperFile(String relativePath, List<CrossFileSource> sources) {
        String path = relativePath == null || relativePath.isBlank() ? "CrossFileCloneHelper.java" : relativePath;
        File asFile = new File(path);
        if (asFile.isAbsolute()) return asFile;
        String base = inferProjectBasePath(sources);
        if (base == null || base.isBlank()) {
            CrossFileSource first = sources == null || sources.isEmpty() ? null : sources.get(0);
            File parent = first == null || first.ioFile == null ? null : first.ioFile.getParentFile();
            return parent == null ? new File(path) : new File(parent, new File(path).getName());
        }
        return new File(base, path);
    }

    static String inferProjectBasePath(List<CrossFileSource> sources) {
        if (sources == null) return "";
        for (CrossFileSource source : sources) {
            if (source == null || source.absolutePath == null || source.relativePath == null) continue;
            String absolute = source.absolutePath.replace("\\", "/");
            String relative = source.relativePath.replace("\\", "/");
            if (!relative.isBlank() && absolute.endsWith(relative)) {
                String base = absolute.substring(0, absolute.length() - relative.length());
                while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                return base;
            }
        }
        return "";
    }

    static CrossFileSource readExistingProjectSharedSource(Project project,
                                                                   List<CrossFileSource> sources,
                                                                   String path) {
        File file = resolveExistingProjectFile(project, sources, path);
        if (file == null || !file.isFile() || !file.getName().endsWith(".java")) {
            return null;
        }
        try {
            VirtualFile vf = LocalFileSystem.getInstance().findFileByIoFile(file);
            String source = readCurrentSource(vf, file);
            String absolutePath = file.getAbsolutePath();
            String relativePath = toProjectRelativePath(project, absolutePath);
            return new CrossFileSource(vf, file, absolutePath, relativePath, source);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static File resolveExistingProjectFile(Project project,
                                                   List<CrossFileSource> sources,
                                                   String path) {
        if (path == null || path.isBlank()) return null;
        String trimmed = path.trim();
        File direct = new File(trimmed);
        if (direct.isAbsolute()) {
            return direct;
        }

        String basePath = project == null ? null : project.getBasePath();
        if (basePath != null && !basePath.isBlank()) {
            File fromProject = new File(basePath, trimmed);
            if (fromProject.exists()) return fromProject;
        }

        String inferredBase = inferProjectBasePath(sources);
        if (inferredBase != null && !inferredBase.isBlank()) {
            File fromInferred = new File(inferredBase, trimmed);
            if (fromInferred.exists()) return fromInferred;
        }

        String basename = new File(trimmed).getName();
        if (basename.isBlank() || sources == null) return direct;
        for (CrossFileSource source : sources) {
            if (source == null || source.ioFile == null) continue;
            File dir = source.ioFile.getParentFile();
            while (dir != null) {
                File sibling = new File(dir, basename);
                if (sibling.isFile()) return sibling;
                dir = dir.getParentFile();
            }
        }
        return direct;
    }
}
