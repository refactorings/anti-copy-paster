package org.jetbrains.research.anticopypaster.workflow;

final class WorkflowReasonSupport {
    private WorkflowReasonSupport() {}

    static String extractUsefulnessDebugText(Object ur) {
        try {
            if (ur == null) return "";

            String[] fieldNames = new String[]{"debug", "debugLines", "debugLine", "pairDebug", "details", "debugInfo"};
            for (String fn : fieldNames) {
                try {
                    java.lang.reflect.Field f = ur.getClass().getField(fn);
                    Object v = f.get(ur);
                    String s = stringifyDebugValue(v);
                    if (s != null && !s.isBlank()) return s;
                } catch (Throwable ignored) {
                }
                try {
                    java.lang.reflect.Field f = ur.getClass().getDeclaredField(fn);
                    f.setAccessible(true);
                    Object v = f.get(ur);
                    String s = stringifyDebugValue(v);
                    if (s != null && !s.isBlank()) return s;
                } catch (Throwable ignored) {
                }
            }

            String[] getterNames = new String[]{"getDebug", "getDebugLines", "getDetails", "debug", "debugLines"};
            for (String mn : getterNames) {
                try {
                    java.lang.reflect.Method m = ur.getClass().getMethod(mn);
                    Object v = m.invoke(ur);
                    String s = stringifyDebugValue(v);
                    if (s != null && !s.isBlank()) return s;
                } catch (Throwable ignored) {
                }
            }

            try {
                java.lang.reflect.Field f = ur.getClass().getField("notes");
                Object v = f.get(ur);
                String s = stringifyDebugValue(v);
                if (s != null && !s.isBlank()) return s;
            } catch (Throwable ignored) {
            }
            try {
                java.lang.reflect.Field f = ur.getClass().getDeclaredField("notes");
                f.setAccessible(true);
                Object v = f.get(ur);
                String s = stringifyDebugValue(v);
                if (s != null && !s.isBlank()) return s;
            } catch (Throwable ignored) {
            }

            return String.valueOf(ur);
        } catch (Throwable t) {
            return "";
        }
    }

    static String previewOneLine(String s, int max) {
        if (s == null) return "";
        String v = s.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n").strip();
        if (v.length() > max) v = v.substring(0, max) + "...";
        return v;
    }

    static String[] parseWrapperNamesFromUsefulnessDebug(String debugText) {
        try {
            if (debugText == null || debugText.isBlank()) return null;

            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("#([A-Za-z_][\\w$]*)\\s*\\([^)]*\\)\\s*<->\\s*[^#]*#([A-Za-z_][\\w$]*)\\s*\\(")
                    .matcher(debugText);

            if (!m.find()) return null;

            String a = m.group(1);
            String b = m.group(2);
            if (a == null || a.isBlank() || b == null || b.isBlank()) return null;

            return new String[]{a, b};
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean containsReasonName(Object reasonObj, String reasonName) {
        if (reasonObj == null || reasonName == null || reasonName.isBlank()) return false;
        if (reasonObj instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (reasonName.equals(String.valueOf(item))) return true;
            }
            return false;
        }
        return reasonName.equals(String.valueOf(reasonObj));
    }

    static String definitionForReason(Object reasonObj) {
        if (reasonObj == null) return "No definition available.";

        String reason;
        try {
            if (reasonObj instanceof java.util.List) {
                java.util.List<?> lst = (java.util.List<?>) reasonObj;
                StringBuilder sb = new StringBuilder();
                for (Object it : lst) {
                    if (it == null) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(String.valueOf(it));
                }
                reason = sb.toString();
            } else {
                reason = String.valueOf(reasonObj);
            }
        } catch (Throwable t) {
            reason = String.valueOf(reasonObj);
        }

        if (reason == null || reason.isBlank()) return "No definition available.";
        String r = reason.toLowerCase(java.util.Locale.ROOT);

        if (r.contains("incomplete")) {
            return "The refactoring modifies the code, but most of the duplicated logic still remains in the original methods.";
        }
        if ((r.contains("non") && r.contains("target")) || r.contains("non_target")) {
            return "The refactoring appears to change some other clone in the file, while the intended target clone remains essentially unchanged.";
        }
        if ((r.contains("without") && r.contains("replacement")) || r.contains("extraction_without_clone_replacement")) {
            return "A helper method was extracted, but the original clone body was not replaced by calls to that helper and still remains duplicated.";
        }
        if ((r.contains("extract") && r.contains("not") && r.contains("found")) || r.contains("extract_method_not_found")) {
            return "No valid Extract Method refactoring was found: the target clone remains essentially unchanged and no extracted helper replaced it.";
        }
        if (r.contains("excessive")) {
            return "The extracted method includes statements beyond the intended cloned fragment.";
        }
        if ((r.contains("post") && r.contains("deletion")) || r.contains("post-extraction") || r.contains("post_extraction")) {
            return "A helper method is introduced, but only one clone is replaced by a call, while the other clone is deleted.";
        }
        if ((r.contains("direct") && r.contains("removal")) || r.contains("delete_clone") || r.contains("delete clone")) {
            return "One clone instance is deleted without introducing a shared abstraction.";
        }
        if ((r.contains("call") && r.contains("substitution")) || r.contains("existing method") || r.contains("reuse")) {
            return "One clone is replaced by a call to an existing method instead of extracting a new shared abstraction.";
        }
        if (r.contains("fragment")) {
            return "The duplicated logic is split into several small methods without consolidating it into one shared abstraction.";
        }
        if (r.contains("delegation") || r.contains("delegate")) {
            return "The original method is modified to delegate behavior, and the clone calls this modified method instead of a new abstraction.";
        }

        return "The refactoring does not properly remove duplication or introduce a correct shared abstraction. Please ensure both clones delegate to a newly extracted helper method.";
    }

    private static String stringifyDebugValue(Object v) {
        if (v == null) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof java.util.List) {
            java.util.List<?> lst = (java.util.List<?>) v;
            if (lst.isEmpty()) return "";
            Object first = lst.get(0);
            return first == null ? "" : String.valueOf(first);
        }
        return String.valueOf(v);
    }
}
