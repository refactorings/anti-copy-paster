package org.jetbrains.research.anticopypaster.workflow;

import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.stripOptionalJavaFence;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.snippetsEquivalent;

import java.util.List;

final class CrossFileTextEditSupport {

    private CrossFileTextEditSupport() {}

    static final class TextSpan {
        final int start;
        final int end;

        TextSpan(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    static final class ResolvedReplacement {
        final TextSpan span;
        final String newText;
        final boolean preformatted;

        ResolvedReplacement(TextSpan span, String newText, boolean preformatted) {
            this.span = span;
            this.newText = newText == null ? "" : newText;
            this.preformatted = preformatted;
        }
    }

    static final class MethodTextSpan {
        final int start;
        final int end;
        final int bodyStart;
        final int bodyEnd;
        final String methodName;

        MethodTextSpan(int start, int end, int bodyStart, int bodyEnd, String methodName) {
            this.start = start;
            this.end = end;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
            this.methodName = methodName == null ? "" : methodName;
        }
    }

    static final class ReplacementTarget {
        final TextSpan span;
        final String newText;
        final boolean preformatted;

        ReplacementTarget(TextSpan span, String newText, boolean preformatted) {
            this.span = span;
            this.newText = newText == null ? "" : newText;
            this.preformatted = preformatted;
        }
    }

    static final class NormalizedMatchText {
        final String text;
        final java.util.List<Integer> offsets;

        NormalizedMatchText(String text, java.util.List<Integer> offsets) {
            this.text = text == null ? "" : text;
            this.offsets = offsets == null ? List.of() : List.copyOf(offsets);
        }
    }

    static String applyCrossFileOccurrenceReplacements(CrossFileSource source,
                                                               CrossFileClone selectedClone,
                                                               java.util.List<CrossFileOccurrenceRewrite> occurrenceReplacements) {
        if (source == null) {
            throw new IllegalStateException("Missing target source.");
        }
        String originalSource = source.source == null ? "" : source.source;
        java.util.List<CrossFileOccurrenceSpec> specs =
                filterOccurrenceSpecsForSource(buildCrossFileOccurrenceSpecs(selectedClone), source);
        if (specs.isEmpty()) {
            throw new IllegalStateException("No target occurrences are known for this file.");
        }

        java.util.LinkedHashMap<String, CrossFileOccurrenceRewrite> rewritesById = new java.util.LinkedHashMap<>();
        if (occurrenceReplacements != null) {
            for (CrossFileOccurrenceRewrite rewrite : occurrenceReplacements) {
                if (rewrite == null || rewrite.occurrenceId == null || rewrite.occurrenceId.isBlank()) continue;
                rewritesById.put(rewrite.occurrenceId.trim(), rewrite);
            }
        }

        java.util.ArrayList<ResolvedReplacement> resolved = new java.util.ArrayList<>();
        java.util.ArrayList<TextSpan> usedSpans = new java.util.ArrayList<>();
        for (CrossFileOccurrenceSpec spec : specs) {
            CrossFileOccurrenceRewrite rewrite = rewritesById.get(spec.occurrenceId);
            if (rewrite == null || rewrite.replacementCode == null || rewrite.replacementCode.isBlank()) {
                throw new IllegalStateException("Missing replacement_code for " + spec.occurrenceId + ".");
            }
            TextSpan located = locateCrossFileOccurrence(originalSource, spec, usedSpans);
            if (located == null) {
                throw new IllegalStateException("Could not locate " + spec.occurrenceId
                        + " near lines " + spec.occurrence.startLine + "-" + spec.occurrence.endLine + ".");
            }
            ReplacementTarget target = selectReplacementTarget(originalSource, located, rewrite.replacementCode);
            usedSpans.add(target.span);
            resolved.add(new ResolvedReplacement(target.span, target.newText, target.preformatted));
        }

        resolved.sort((a, b) -> Integer.compare(b.span.start, a.span.start));
        String updated = originalSource;
        for (ResolvedReplacement replacement : resolved) {
            String originalText = updated.substring(replacement.span.start, replacement.span.end);
            String adjusted = replacement.preformatted
                    ? replacement.newText
                    : reindentLikeOriginal(replacement.newText, originalText);
            updated = updated.substring(0, replacement.span.start) + adjusted + updated.substring(replacement.span.end);
        }
        return updated;
    }

    static String insertHelperMethod(String source, String helperMethod, int anchorPosition) {
        if (source == null) source = "";
        helperMethod = stripOptionalJavaFence(helperMethod);
        if (helperMethod == null || helperMethod.isBlank()) {
            throw new IllegalStateException("Missing helper_method.");
        }
        int insertPos = findEnclosingTypeInsertionPoint(source, anchorPosition);
        String memberIndent = detectMemberIndent(source, insertPos);
        String helperBlock = reindentBlock(helperMethod, memberIndent);
        String insertion = "\n\n" + helperBlock + "\n";
        return source.substring(0, insertPos) + insertion + source.substring(insertPos);
    }

    static String insertJavaImports(String source, List<String> imports) {
        if (source == null || source.isBlank() || imports == null || imports.isEmpty()) {
            return source == null ? "" : source;
        }
        java.util.LinkedHashSet<String> missingImports = new java.util.LinkedHashSet<>();
        for (String value : imports) {
            String normalized = normalizeJavaImportName(value);
            if (normalized.isBlank()
                    || normalized.startsWith("java.lang.")
                    || source.contains("import " + normalized + ";")
                    || !findJavaImportConflicts(source, java.util.List.of(normalized)).isEmpty()) {
                continue;
            }
            missingImports.add(normalized);
        }
        if (missingImports.isEmpty()) return source;

        StringBuilder insertion = new StringBuilder();
        for (String importName : missingImports) {
            insertion.append("import ").append(importName).append(";\n");
        }

        java.util.regex.Matcher importMatcher = java.util.regex.Pattern
                .compile("(?m)^\\s*import\\s+(?:static\\s+)?[^;]+;\\s*$")
                .matcher(source);
        int insertPos = -1;
        while (importMatcher.find()) {
            insertPos = importMatcher.end();
        }
        if (insertPos >= 0) {
            return source.substring(0, insertPos)
                    + "\n"
                    + insertion
                    + source.substring(insertPos);
        }

        java.util.regex.Matcher packageMatcher = java.util.regex.Pattern
                .compile("(?m)^\\s*package\\s+[A-Za-z_][\\w.]*\\s*;\\s*$")
                .matcher(source);
        if (packageMatcher.find()) {
            insertPos = packageMatcher.end();
            return source.substring(0, insertPos)
                    + "\n\n"
                    + insertion
                    + source.substring(insertPos);
        }

        return insertion + "\n" + source;
    }

    static String normalizeJavaImportName(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.startsWith("import ")) {
            normalized = normalized.substring("import ".length()).trim();
        }
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized.matches("(?:static\\s+)?[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*|\\.\\*)*")
                ? normalized
                : "";
    }

    static java.util.List<String> findJavaImportConflicts(String source, List<String> imports) {
        java.util.ArrayList<String> conflicts = new java.util.ArrayList<>();
        if (source == null || imports == null || imports.isEmpty()) return conflicts;

        java.util.LinkedHashMap<String, String> existingBySimpleName = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^\\s*import\\s+(?!static\\s+)([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+)\\s*;\\s*$")
                .matcher(source);
        while (matcher.find()) {
            String fqn = matcher.group(1);
            String simple = simpleImportName(fqn);
            if (!simple.isBlank()) existingBySimpleName.putIfAbsent(simple, fqn);
        }
        java.util.LinkedHashSet<String> declaredTypeNames = findDeclaredJavaTypeNames(source);

        java.util.LinkedHashMap<String, String> requestedBySimpleName = new java.util.LinkedHashMap<>();
        for (String value : imports) {
            String normalized = normalizeJavaImportName(value);
            if (normalized.isBlank()
                    || normalized.startsWith("static ")
                    || normalized.endsWith(".*")
                    || normalized.startsWith("java.lang.")) {
                continue;
            }
            String simple = simpleImportName(normalized);
            if (simple.isBlank()) continue;
            if (declaredTypeNames.contains(simple)) {
                conflicts.add(normalized + " conflicts with declared type " + simple);
                continue;
            }
            String existing = existingBySimpleName.get(simple);
            if (existing != null && !existing.equals(normalized)) {
                conflicts.add(normalized + " conflicts with existing import " + existing);
                continue;
            }
            String requested = requestedBySimpleName.putIfAbsent(simple, normalized);
            if (requested != null && !requested.equals(normalized)) {
                conflicts.add(normalized + " conflicts with requested import " + requested);
            }
        }
        return conflicts;
    }

    private static java.util.LinkedHashSet<String> findDeclaredJavaTypeNames(String source) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        if (source == null || source.isBlank()) return names;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)\\b")
                .matcher(source);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name != null && !name.isBlank()) names.add(name);
        }
        return names;
    }

    private static String simpleImportName(String fqn) {
        if (fqn == null || fqn.isBlank()) return "";
        String normalized = fqn.trim();
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    static int firstOccurrenceAnchor(CrossFileSource source, CrossFileClone selectedClone) {
        if (source == null || selectedClone == null || selectedClone.occurrences == null) return -1;
        for (CrossFileOccurrence occurrence : selectedClone.occurrences) {
            if (occurrence == null || occurrence.source != source) continue;
            TextSpan span = spanForLineRange(source.source, occurrence.startLine, occurrence.endLine);
            return span == null ? -1 : span.start;
        }
        return -1;
    }

    static java.util.List<CrossFileOccurrenceSpec> buildCrossFileOccurrenceSpecs(CrossFileClone clone) {
        java.util.ArrayList<CrossFileOccurrenceSpec> specs = new java.util.ArrayList<>();
        if (clone == null || clone.occurrences == null) return specs;
        int counter = 1;
        for (CrossFileOccurrence occurrence : clone.occurrences) {
            if (occurrence == null || occurrence.source == null) continue;
            specs.add(new CrossFileOccurrenceSpec("OCCURRENCE_" + counter++, occurrence));
        }
        return specs;
    }

    static java.util.List<CrossFileOccurrenceSpec> filterOccurrenceSpecsForSource(List<CrossFileOccurrenceSpec> specs,
                                                                                          CrossFileSource source) {
        java.util.ArrayList<CrossFileOccurrenceSpec> out = new java.util.ArrayList<>();
        if (specs == null || source == null) return out;
        for (CrossFileOccurrenceSpec spec : specs) {
            if (spec == null || spec.occurrence == null) continue;
            if (spec.occurrence.source == source) {
                out.add(spec);
            }
        }
        return out;
    }

    static TextSpan locateCrossFileOccurrence(String source,
                                                      CrossFileOccurrenceSpec spec,
                                                      List<TextSpan> usedSpans) {
        if (source == null || source.isBlank() || spec == null || spec.occurrence == null) return null;
        TextSpan lineSpan = spanForLineRange(source, spec.occurrence.startLine, spec.occurrence.endLine);
        if (lineSpan != null && !overlapsUsed(lineSpan, usedSpans)) {
            String rangeText = source.substring(lineSpan.start, lineSpan.end);
            if (spec.occurrence.snippet == null || spec.occurrence.snippet.isBlank()
                    || snippetsEquivalent(rangeText, spec.occurrence.snippet)) {
                return lineSpan;
            }
        }

        java.util.List<TextSpan> candidates = findExactCandidates(source, spec.occurrence.snippet);
        if (candidates.isEmpty()) {
            candidates = findTrimmedLineCandidates(source, spec.occurrence.snippet);
        }
        if (candidates.isEmpty()) {
            candidates = findWhitespaceNormalizedCandidates(source, spec.occurrence.snippet);
        }
        if (candidates.isEmpty()) return null;

        int[] lineStarts = computeLineStarts(source);
        TextSpan best = null;
        long bestScore = Long.MAX_VALUE;
        for (TextSpan candidate : candidates) {
            if (overlapsUsed(candidate, usedSpans)) continue;
            int line = lineOfOffset(lineStarts, candidate.start);
            long score = Math.abs((long) line - spec.occurrence.startLine);
            if (best == null || score < bestScore || (score == bestScore && candidate.start < best.start)) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    static TextSpan spanForLineRange(String source, int startLine, int endLine) {
        if (source == null || startLine <= 0 || endLine < startLine) return null;
        int start = offsetAtLine(source, startLine);
        int end = offsetAtLineEnd(source, endLine);
        if (start < 0 || end < start || end > source.length()) return null;
        return new TextSpan(start, end);
    }

    static int offsetAtLine(String source, int line1Based) {
        if (source == null) return -1;
        if (line1Based <= 1) return 0;
        int line = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
                if (line == line1Based) return i + 1;
            }
        }
        return line == line1Based ? source.length() : -1;
    }

    static int offsetAtLineEnd(String source, int line1Based) {
        if (source == null || line1Based <= 0) return -1;
        int start = offsetAtLine(source, line1Based);
        if (start < 0) return -1;
        int newline = source.indexOf('\n', start);
        int end = newline < 0 ? source.length() : newline;
        if (end > start && source.charAt(end - 1) == '\r') end--;
        return end;
    }

    static ReplacementTarget selectReplacementTarget(String source,
                                                             TextSpan locatedSpan,
                                                             String replacementCode) {
        if (source == null || locatedSpan == null) {
            return new ReplacementTarget(locatedSpan, replacementCode, false);
        }
        if (replacementCode == null || replacementCode.isBlank()) {
            return new ReplacementTarget(locatedSpan, replacementCode, false);
        }
        if (replacementMatchesLocatedWholeMethod(source, locatedSpan, replacementCode)) {
            return new ReplacementTarget(locatedSpan, replacementCode, false);
        }

        MethodTextSpan methodSpan = resolveWholeMethodSpan(source, locatedSpan);
        if (methodSpan == null || methodSpan.bodyStart >= methodSpan.bodyEnd) {
            return new ReplacementTarget(locatedSpan, replacementCode, false);
        }

        String originalBody = source.substring(methodSpan.bodyStart, methodSpan.bodyEnd);
        String leadingWhitespace = leadingWhitespace(originalBody);
        String trailingWhitespace = trailingWhitespace(originalBody);
        String bodyCore = originalBody.substring(
                Math.min(leadingWhitespace.length(), originalBody.length()),
                Math.max(Math.min(leadingWhitespace.length(), originalBody.length()),
                        originalBody.length() - trailingWhitespace.length())
        );
        String indentBasis = bodyCore.isBlank() ? originalBody : bodyCore;
        String adjustedBody = reindentLikeOriginal(replacementCode, indentBasis);
        String wrappedBody = leadingWhitespace + adjustedBody + trailingWhitespace;
        return new ReplacementTarget(
                new TextSpan(methodSpan.bodyStart, methodSpan.bodyEnd),
                wrappedBody,
                true
        );
    }

    static boolean overlapsUsed(TextSpan candidate, List<TextSpan> usedSpans) {
        if (candidate == null || usedSpans == null) return false;
        for (TextSpan used : usedSpans) {
            if (used == null) continue;
            if (candidate.start < used.end && used.start < candidate.end) {
                return true;
            }
        }
        return false;
    }

    static java.util.List<TextSpan> findExactCandidates(String source, String snippet) {
        java.util.ArrayList<TextSpan> out = new java.util.ArrayList<>();
        if (source == null || snippet == null || snippet.isBlank()) return out;
        int from = 0;
        while (from <= source.length()) {
            int idx = source.indexOf(snippet, from);
            if (idx < 0) break;
            out.add(new TextSpan(idx, idx + snippet.length()));
            from = idx + Math.max(1, snippet.length());
        }
        return out;
    }

    static java.util.List<TextSpan> findTrimmedLineCandidates(String source, String snippet) {
        java.util.ArrayList<TextSpan> out = new java.util.ArrayList<>();
        if (source == null || snippet == null || snippet.isBlank()) return out;

        String normalizedSource = normalizeNewlines(source);
        String normalizedSnippet = normalizeNewlines(snippet);
        String[] sourceLines = normalizedSource.split("\n", -1);
        String[] snippetLines = normalizedSnippet.split("\n", -1);

        java.util.ArrayList<String> needed = new java.util.ArrayList<>();
        for (String line : snippetLines) {
            String trimmed = normalizeLineForMatch(line);
            if (!trimmed.isEmpty()) needed.add(trimmed);
        }
        if (needed.isEmpty()) return out;

        int[] lineStarts = computeLineStarts(normalizedSource);
        for (int start = 0; start < sourceLines.length; start++) {
            int iSource = start;
            int iNeed = 0;
            while (iSource < sourceLines.length && sourceLines[iSource].trim().isEmpty()) iSource++;
            int candidateStart = iSource;

            while (iSource < sourceLines.length && iNeed < needed.size()) {
                String trimmed = normalizeLineForMatch(sourceLines[iSource]);
                if (trimmed.isEmpty()) {
                    iSource++;
                    continue;
                }
                if (!trimmed.equals(needed.get(iNeed))) break;
                iSource++;
                iNeed++;
            }

            if (iNeed == needed.size()) {
                int endLine = Math.max(candidateStart, iSource - 1);
                int startOffset = lineStarts[Math.min(candidateStart, lineStarts.length - 1)];
                int endOffset = endLine + 1 < lineStarts.length ? lineStarts[endLine + 1] : normalizedSource.length();
                out.add(new TextSpan(startOffset, endOffset));
            }
        }
        return out;
    }

    static java.util.List<TextSpan> findWhitespaceNormalizedCandidates(String source, String snippet) {
        java.util.ArrayList<TextSpan> out = new java.util.ArrayList<>();
        if (source == null || snippet == null || snippet.isBlank()) return out;

        NormalizedMatchText normalizedSource = normalizeForWhitespaceInsensitiveMatch(source);
        String normalizedSnippet = normalizeWhitespaceFree(snippet);
        if (normalizedSnippet.isBlank()) return out;

        int from = 0;
        while (from <= normalizedSource.text.length() - normalizedSnippet.length()) {
            int idx = normalizedSource.text.indexOf(normalizedSnippet, from);
            if (idx < 0) break;
            int startOffset = normalizedSource.offsets.get(idx);
            int endOffset = normalizedSource.offsets.get(idx + normalizedSnippet.length() - 1) + 1;
            out.add(new TextSpan(startOffset, endOffset));
            from = idx + Math.max(1, normalizedSnippet.length());
        }
        return out;
    }

    static int[] computeLineStarts(String source) {
        String normalized = normalizeNewlines(source);
        java.util.ArrayList<Integer> starts = new java.util.ArrayList<>();
        starts.add(0);
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == '\n' && i + 1 <= normalized.length()) {
                starts.add(i + 1);
            }
        }
        int[] arr = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) arr[i] = starts.get(i);
        return arr;
    }

    static int lineOfOffset(int[] lineStarts, int offset) {
        if (lineStarts == null || lineStarts.length == 0) return 1;
        int lo = 0;
        int hi = lineStarts.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int start = lineStarts[mid];
            int next = (mid + 1 < lineStarts.length) ? lineStarts[mid + 1] : Integer.MAX_VALUE;
            if (offset < start) {
                hi = mid - 1;
            } else if (offset >= next) {
                lo = mid + 1;
            } else {
                return mid + 1;
            }
        }
        return Math.max(1, Math.min(lineStarts.length, lo + 1));
    }

    static int findEnclosingTypeInsertionPoint(String source, int anchorPosition) {
        if (source == null || source.isEmpty()) return 0;
        int anchor = anchorPosition >= 0 ? anchorPosition : source.length() - 1;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\b(class|interface|enum|record)\\b[^\\{;]*\\{")
                .matcher(source);

        int bestClose = -1;
        int bestSpan = Integer.MAX_VALUE;
        while (matcher.find()) {
            int openBrace = source.indexOf('{', matcher.start());
            if (openBrace < 0) continue;
            int closeBrace = findMatchingBrace(source, openBrace);
            if (closeBrace <= openBrace) continue;
            if (openBrace < anchor && anchor < closeBrace) {
                int span = closeBrace - openBrace;
                if (span < bestSpan) {
                    bestSpan = span;
                    bestClose = closeBrace;
                }
            }
        }

        if (bestClose >= 0) return bestClose;
        int fallback = source.lastIndexOf('}');
        return fallback >= 0 ? fallback : source.length();
    }

    static int findMatchingBrace(String source, int openBrace) {
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean escaped = false;

        for (int i = openBrace; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (inChar) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '\'') inChar = false;
                continue;
            }

            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '\'') {
                inChar = true;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    static MethodTextSpan resolveWholeMethodSpan(String source, TextSpan span) {
        if (source == null || source.isBlank() || span == null) return null;
        int start = Math.max(0, Math.min(span.start, source.length()));
        int end = Math.max(start, Math.min(span.end, source.length()));
        if (start >= end) return null;

        while (start < end && Character.isWhitespace(source.charAt(start))) start++;
        while (end > start && Character.isWhitespace(source.charAt(end - 1))) end--;
        if (start >= end) return null;

        int openBrace = source.indexOf('{', start);
        if (openBrace < 0 || openBrace >= end) return null;

        int closeBrace = findMatchingBrace(source, openBrace);
        if (closeBrace < 0 || closeBrace + 1 != end) return null;

        String header = source.substring(start, openBrace).trim();
        String methodName = methodNameFromHeader(header);
        if (methodName.isBlank()) return null;
        return new MethodTextSpan(start, end, openBrace + 1, closeBrace, methodName);
    }

    static boolean looksLikeWholeMethodText(String text) {
        if (text == null || text.isBlank()) return false;
        return resolveWholeMethodSpan(text, new TextSpan(0, text.length())) != null;
    }

    static boolean replacementMatchesLocatedWholeMethod(String source, TextSpan locatedSpan, String replacementCode) {
        if (replacementCode == null || replacementCode.isBlank()) return false;
        MethodTextSpan replacementMethod = resolveWholeMethodSpan(
                replacementCode,
                new TextSpan(0, replacementCode.length())
        );
        if (replacementMethod == null) return false;
        MethodTextSpan targetMethod = resolveWholeMethodSpan(source, locatedSpan);
        if (targetMethod == null) return false;
        return replacementMethod.methodName.equals(targetMethod.methodName);
    }

    static boolean looksLikeMethodHeader(String header) {
        if (header == null) return false;
        return !methodNameFromHeader(header).isBlank();
    }

    static String methodNameFromHeader(String header) {
        if (header == null) return "";
        String normalized = normalizeLineForMatch(header);
        if (normalized.isEmpty()) return "";
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        String[] rejectedPrefixes = {
                "if ", "for ", "while ", "switch ", "try", "catch ", "do", "else",
                "synchronized ", "class ", "interface ", "enum ", "record ", "new "
        };
        for (String prefix : rejectedPrefixes) {
            if (lower.startsWith(prefix)) return "";
        }
        if (normalized.contains("->") || normalized.contains("=")) return "";

        int openParen = normalized.indexOf('(');
        int closeParen = normalized.lastIndexOf(')');
        if (openParen <= 0 || closeParen < openParen) return "";

        String beforeParen = normalized.substring(0, openParen).trim();
        if (beforeParen.isEmpty()) return "";
        int split = Math.max(beforeParen.lastIndexOf(' '), beforeParen.lastIndexOf('.'));
        String candidateName = beforeParen.substring(split + 1).trim();
        return candidateName.matches("[A-Za-z_$][A-Za-z0-9_$]*") ? candidateName : "";
    }

    static String leadingWhitespace(String text) {
        if (text == null || text.isEmpty()) return "";
        int index = 0;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        return text.substring(0, index);
    }

    static String trailingWhitespace(String text) {
        if (text == null || text.isEmpty()) return "";
        int index = text.length();
        while (index > 0 && Character.isWhitespace(text.charAt(index - 1))) index--;
        return text.substring(index);
    }

    static String detectMemberIndent(String source, int insertPos) {
        int lineStart = Math.max(0, source.lastIndexOf('\n', Math.max(0, insertPos - 1)) + 1);
        int i = lineStart;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c != ' ' && c != '\t') break;
            i++;
        }
        return source.substring(lineStart, i) + "    ";
    }

    static String reindentLikeOriginal(String newText, String originalText) {
        String baseIndent = firstNonEmptyIndent(originalText);
        return reindentBlock(newText, baseIndent);
    }

    static String reindentBlock(String block, String baseIndent) {
        if (block == null) return "";
        String normalized = normalizeNewlines(block).strip();
        if (normalized.isEmpty()) return "";

        String[] lines = normalized.split("\n", -1);
        int commonIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            commonIndent = Math.min(commonIndent, countIndent(line));
        }
        if (commonIndent == Integer.MAX_VALUE) commonIndent = 0;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) sb.append("\n");
            if (line.trim().isEmpty()) continue;
            int cut = Math.min(commonIndent, countIndent(line));
            sb.append(baseIndent == null ? "" : baseIndent).append(line.substring(cut));
        }
        return sb.toString();
    }

    static int countIndent(String line) {
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') break;
            i++;
        }
        return i;
    }

    static String firstNonEmptyIndent(String text) {
        if (text == null || text.isEmpty()) return "";
        String normalized = normalizeNewlines(text);
        String[] lines = normalized.split("\n", -1);
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                int indent = countIndent(line);
                return line.substring(0, indent);
            }
        }
        return "";
    }

    static String normalizeNewlines(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    static String normalizeLineForMatch(String line) {
        if (line == null) return "";
        return line.replaceAll("\\s+", " ").trim();
    }

    static NormalizedMatchText normalizeForWhitespaceInsensitiveMatch(String text) {
        String normalized = normalizeNewlines(text);
        StringBuilder sb = new StringBuilder(normalized.length());
        java.util.ArrayList<Integer> offsets = new java.util.ArrayList<>();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            sb.append(ch);
            offsets.add(i);
        }
        return new NormalizedMatchText(sb.toString(), offsets);
    }

    static String normalizeWhitespaceFree(String text) {
        if (text == null || text.isBlank()) return "";
        String normalized = normalizeNewlines(text);
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (!Character.isWhitespace(ch)) sb.append(ch);
        }
        return sb.toString();
    }

}
