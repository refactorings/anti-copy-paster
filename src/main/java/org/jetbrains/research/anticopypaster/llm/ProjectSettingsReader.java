package org.jetbrains.research.anticopypaster.llm;

import com.intellij.openapi.project.Project;

public final class ProjectSettingsReader {

    private ProjectSettingsReader() {}

    public static LlmConfig read(Project project) {
        try {
            Class<?> cls = Class.forName("org.jetbrains.research.anticopypaster.config.ProjectSettingsState");

            Object inst = null;
            // try getInstance(Project)
            try {
                var m = cls.getMethod("getInstance", Project.class);
                inst = m.invoke(null, project);
            } catch (Throwable ignored) {}
            // try getInstance()
            if (inst == null) {
                try {
                    var m = cls.getMethod("getInstance");
                    inst = m.invoke(null);
                } catch (Throwable ignored) {}
            }
            // try project service
            if (inst == null && project != null) {
                try { inst = project.getService(cls); } catch (Throwable ignored) {}
            }
            if (inst == null) return null;

            // ✅ 这些名字你需要按你 ProjectSettingsState 的真实 getter/field 对齐
            String provider   = reflectString(inst, "getLlmProvider", "llmProvider", "provider");
            String model      = reflectString(inst, "getSelectedAiderModel", "getModel", "model", "deployment");
            String apiKey     = reflectString(inst, "getAiderApiKey", "getApiKey", "apiKey", "key");
            String apiBase    = reflectString(inst, "getApiBase", "apiBase", "baseUrl", "endpoint");
            String apiVersion = reflectString(inst, "getApiVersion", "apiVersion", "version");
            String ollamaModel= reflectString(inst, "getOllamaModel", "ollamaModel");

            return new LlmConfig(provider, model, apiKey, apiBase, apiVersion, ollamaModel);

        } catch (Throwable t) {
            return null;
        }
    }

    private static String reflectString(Object obj, String... candidates) {
        if (obj == null || candidates == null) return "";
        Class<?> c = obj.getClass();

        for (String name : candidates) {
            if (name == null || name.isBlank()) continue;

            // method
            try {
                var m = c.getMethod(name);
                Object v = m.invoke(obj);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {}

            // public field
            try {
                var f = c.getField(name);
                Object v = f.get(obj);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {}

            // declared field
            try {
                var f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {}
        }
        return "";
    }
}