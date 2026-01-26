package org.jetbrains.research.anticopypaster.rag;

import com.intellij.openapi.project.Project;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full RAG service:
 *  - Detection few-shot from clone DB (labeled examples)
 *  - Refactor few-shot from refactor DB (BEFORE -> AFTER)
 *  - Retrieval: sparse(BM25-like token overlap) + dense(embeddings) + RRF fusion + top-k
 *
 * Supports loading CSV from:
 *  - classpath resources (src/main/resources)
 *  - filesystem: absolute or relative to project root
 */
public final class RagService {

    private RagService() {}

    // ------------------------------
    // Configuration (you can expose setters if needed)
    // ------------------------------

    /** Enable dense retrieval. If false => sparse only. */
    public static boolean ENABLE_DENSE_RETRIEVAL = true;

    /** Dense embedding provider: "HF" | "Ollama" | "OpenAI" | "Azure" */
    public static String EMBED_PROVIDER = "HF";

    /** For HF: a model name. Example: "microsoft/codebert-base" */
    public static String EMBED_MODEL = "microsoft/codebert-base";

    /** Candidate limit for dense step (compute embeddings only for top-N sparse results) */
    public static int DENSE_CANDIDATES_LIMIT = 64;

    /** RRF constant */
    public static int RRF_K = 60;

    /** HTTP client shared */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // ------------------------------
    // Public APIs
    // ------------------------------

    /**
     * Build detection prompt with few-shot examples from clone database.
     *
     * @param project IntelliJ Project (used for project root path when file system loading)
     * @param cloneDbPathOrResource e.g. "combined_clone_database_cleaned.csv" (classpath) or "resources/combined_clone_database_cleaned.csv"
     * @param k how many few-shot examples to include (top-k by selection logic)
     * @param maxChars truncate each code snippet
     */
    public static String buildDetectionPromptWithFewShot(Project project,
                                                         String cloneDbPathOrResource,
                                                         int k,
                                                         int maxChars) {
        String base =
                "Please detect any clones in this file. " +
                        "Respond with either 'clones found' or 'no clones found' ONLY.";

        List<CloneFewShot> pool = loadCloneFewShot(cloneDbPathOrResource, project, maxChars);
        if (pool.isEmpty() || k <= 0) return base;

        // Selection strategy (same spirit as你旧代码): 优先覆盖 Type1..4 + 适量 No clone
        List<CloneFewShot> selected = selectDetectionExamples(pool, k);

        StringBuilder sb = new StringBuilder();
        sb.append(base).append("\n\n");
        sb.append("Here are ").append(selected.size()).append(" labeled examples.\n")
                .append("Each example contains Code A, Code B, and the correct label.\n")
                .append("Use them as guidance. Do NOT copy the code.\n")
                .append("Final answer must be exactly one of: 'clones found' or 'no clones found'.\n\n");

        int idx = 1;
        for (CloneFewShot ex : selected) {
            sb.append("Example ").append(idx++).append(":\n");
            sb.append("Code A:\n```\n").append(ex.codeA).append("\n```\n");
            sb.append("Code B:\n```\n").append(ex.codeB).append("\n```\n");
            sb.append("Label: ").append(ex.label).append("\n");
            if (ex.cloneType != null && !ex.cloneType.isBlank()) {
                sb.append("CloneType: ").append(ex.cloneType).append("\n");
            }
            sb.append("\n");
        }
        sb.append("Now answer with exactly one of: 'clones found' or 'no clones found'.");
        return sb.toString();
    }

    /**
     * Build a compact RAG guidance bundle for the refactor agent (BEFORE->AFTER examples).
     * Retrieval = sparse + dense + RRF + top-k.
     *
     * @param project IntelliJ Project
     * @param refactorDbPathOrResource e.g. "refactor_database.csv" or "resources/refactor_database.csv"
     * @param queryText Usually: (cloneSnippetA + "\n" + cloneSnippetB) or (file chunk)
     * @param k top-k examples
     * @param maxChars truncate each example
     * @return a guidance text you can pass into refactor agent as extra context
     */
    public static String buildRefactorRagGuidance(Project project,
                                                  String refactorDbPathOrResource,
                                                  String queryText,
                                                  int k,
                                                  int maxChars) {
        List<RefactorExample> pool = loadRefactorExamples(refactorDbPathOrResource, project, maxChars);
        if (pool.isEmpty() || k <= 0) return "";

        // 1) sparse rank (BM25-like overlap)
        List<Scored<RefactorExample>> sparse = rankByOverlap(queryText, pool);

        // If sparse is empty, fallback random
        if (sparse.isEmpty()) {
            Collections.shuffle(pool, new Random());
            List<RefactorExample> fallback = pool.subList(0, Math.min(k, pool.size()));
            return formatRefactorGuidance(fallback, Collections.emptyMap(), "fallback_random");
        }

        // 2) dense rank (embeddings) for top-N sparse candidates
        List<Scored<RefactorExample>> dense = Collections.emptyList();
        if (ENABLE_DENSE_RETRIEVAL) {
            List<RefactorExample> candidates = takeTopItems(sparse, Math.min(DENSE_CANDIDATES_LIMIT, sparse.size()));
            dense = rankByEmbedding(queryText, candidates, project);
        }

        // 3) RRF fuse
        List<Scored<RefactorExample>> fused = reciprocalRankFusion(sparse, dense);

        // 4) take top-k
        List<Scored<RefactorExample>> top = fused.subList(0, Math.min(k, fused.size()));
        List<RefactorExample> examples = new ArrayList<>();
        Map<RefactorExample, Double> scoreMap = new IdentityHashMap<>();
        for (Scored<RefactorExample> s : top) {
            examples.add(s.item);
            scoreMap.put(s.item, s.score);
        }

        return formatRefactorGuidance(examples, scoreMap,
                ENABLE_DENSE_RETRIEVAL ? "sparse+dense+rrf" : "sparse_only");
    }

    // ------------------------------
    // Formatting helpers
    // ------------------------------

    private static String formatRefactorGuidance(List<RefactorExample> examples,
                                                 Map<RefactorExample, Double> scores,
                                                 String mode) {
        StringBuilder sb = new StringBuilder();
        sb.append("[RAG_EXAMPLES]\n");
        sb.append("mode=").append(mode)
                .append(", denseEnabled=").append(ENABLE_DENSE_RETRIEVAL)
                .append(", embedProvider=").append(EMBED_PROVIDER)
                .append(", embedModel=").append(EMBED_MODEL)
                .append(", rrfK=").append(RRF_K)
                .append("\n\n");

        int idx = 1;
        for (RefactorExample ex : examples) {
            double sc = scores.getOrDefault(ex, 0.0);
            sb.append("Example ").append(idx++).append(" (score ")
                    .append(String.format(Locale.US, "%.6f", sc)).append(")\n");
            sb.append("BEFORE:\n```java\n").append(ex.before).append("\n```\n");
            if (ex.after != null && !ex.after.isBlank()) {
                sb.append("AFTER:\n```java\n").append(ex.after).append("\n```\n");
            } else {
                sb.append("AFTER: (empty)\n");
            }
            if (ex.rationale != null && !ex.rationale.isBlank()) {
                sb.append("NOTE: ").append(ex.rationale).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ------------------------------
    // Detection selection logic
    // ------------------------------

    private static List<CloneFewShot> selectDetectionExamples(List<CloneFewShot> pool, int k) {
        Map<String, List<CloneFewShot>> byType = new HashMap<>();
        for (CloneFewShot e : pool) {
            String t = (e.cloneType == null ? "" : e.cloneType.trim());
            if (t.isBlank()) t = "__NO_TYPE__";
            byType.computeIfAbsent(t, x -> new ArrayList<>()).add(e);
        }
        Random rnd = new Random();
        List<CloneFewShot> selected = new ArrayList<>();

        // Prefer one from each Type 1..4
        String[] types = {"Type 1", "Type 2", "Type 3", "Type 4"};
        for (String t : types) {
            List<CloneFewShot> bucket = byType.get(t);
            if (bucket != null && !bucket.isEmpty()) {
                selected.add(bucket.get(rnd.nextInt(bucket.size())));
            }
        }

        // Add "No clone" examples
        List<CloneFewShot> noClone = byType.get("No clone");
        if (noClone != null && !noClone.isEmpty()) {
            List<CloneFewShot> copy = new ArrayList<>(noClone);
            Collections.shuffle(copy, rnd);
            for (int i = 0; i < Math.min(4, copy.size()); i++) selected.add(copy.get(i));
        }

        // If still not enough, fill random
        if (selected.size() < k) {
            List<CloneFewShot> copy = new ArrayList<>(pool);
            Collections.shuffle(copy, rnd);
            for (CloneFewShot e : copy) {
                if (selected.size() >= k) break;
                selected.add(e);
            }
        }

        // Trim
        if (selected.size() > k) return selected.subList(0, k);
        return selected;
    }

    // ------------------------------
    // Sparse ranking (BM25-like overlap)
    // ------------------------------

    private static List<Scored<RefactorExample>> rankByOverlap(String query, List<RefactorExample> pool) {
        Map<String, Integer> qtf = tf(tokenizeIdentifiers(query));
        List<Scored<RefactorExample>> scored = new ArrayList<>();
        for (RefactorExample ex : pool) {
            Map<String, Integer> dtf = tf(tokenizeIdentifiers(ex.before));
            double score = 0.0;
            for (Map.Entry<String, Integer> e : qtf.entrySet()) {
                Integer f = dtf.get(e.getKey());
                if (f != null) score += Math.min(e.getValue(), f);
            }
            if (score > 0) scored.add(new Scored<>(ex, score));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored;
    }

    // ------------------------------
    // Dense ranking (embeddings + cosine)
    // ------------------------------

    private static List<Scored<RefactorExample>> rankByEmbedding(String query, List<RefactorExample> candidates, Project project) {
        try {
            double[] q = embedText(query);
            if (q == null) return Collections.emptyList();

            List<Scored<RefactorExample>> out = new ArrayList<>();
            for (RefactorExample ex : candidates) {
                double[] v = embedText(ex.before);
                if (v == null) continue;
                out.add(new Scored<>(ex, cosine(q, v)));
            }
            out.sort((a, b) -> Double.compare(b.score, a.score));
            return out;
        } catch (Throwable t) {
            System.err.println("[RAG] rankByEmbedding failed: " + t.getMessage());
            return Collections.emptyList();
        }
    }

    private static double[] embedText(String text) throws Exception {
        if (text == null || text.isBlank()) return null;

        String p = EMBED_PROVIDER == null ? "" : EMBED_PROVIDER.trim();
        if (p.equalsIgnoreCase("HF")) {
            return embedHuggingFace(text);
        }
        if (p.equalsIgnoreCase("Ollama")) {
            return embedOllama(text);
        }
        if (p.equalsIgnoreCase("OpenAI")) {
            return embedOpenAI(text);
        }
        if (p.equalsIgnoreCase("Azure")) {
            return embedAzure(text);
        }

        System.err.println("[RAG] Unknown EMBED_PROVIDER: " + EMBED_PROVIDER);
        return null;
    }

    /** Hugging Face Inference API: feature-extraction */
    private static double[] embedHuggingFace(String text) throws Exception {
        String token = System.getenv("HUGGINGFACE_API_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("[RAG] HUGGINGFACE_API_TOKEN is not set; dense retrieval disabled.");
            return null;
        }
        String url = "https://api-inference.huggingface.co/pipeline/feature-extraction/" + EMBED_MODEL;
        String body = "{\"inputs\":" + jsonString(text) + ",\"options\":{\"wait_for_model\":true}}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return parseHfFeatureExtraction(resp.body());
    }

    /** OpenAI embeddings (uses env OPENAI_API_KEY + optional OPENAI_API_BASE) */
    private static double[] embedOpenAI(String text) throws Exception {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) return null;

        String base = System.getenv().getOrDefault("OPENAI_API_BASE", "https://api.openai.com");
        String url = (base.endsWith("/") ? base + "v1/embeddings" : base + "/v1/embeddings");
        String body = "{\"model\":\"text-embedding-3-small\",\"input\":" + jsonString(text) + "}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return parseOpenAIEmbedding(resp.body());
    }

    /** Azure OpenAI embeddings (uses env AZURE_API_KEY / AZURE_API_BASE / AZURE_API_VERSION) */
    private static double[] embedAzure(String text) throws Exception {
        String key = System.getenv("AZURE_API_KEY");
        String base = System.getenv("AZURE_API_BASE");
        String ver  = System.getenv("AZURE_API_VERSION");
        if (key == null || base == null || ver == null || key.isBlank() || base.isBlank() || ver.isBlank()) return null;

        // Deployment name here uses text-embedding-3-small (adjust if your Azure deployment differs)
        String url = base + "/openai/deployments/text-embedding-3-small/embeddings?api-version=" + ver;
        String body = "{\"input\":" + jsonString(text) + "}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("api-key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return parseOpenAIEmbedding(resp.body());
    }

    /** Ollama embeddings (uses env OLLAMA_API_BASE, default http://localhost:11434) */
    private static double[] embedOllama(String text) throws Exception {
        String base = System.getenv("OLLAMA_API_BASE");
        if (base == null || base.isBlank()) base = "http://localhost:11434";
        String url = base + "/api/embeddings";

        // Model can be changed, e.g. "nomic-embed-text"
        String body = "{\"model\":\"nomic-embed-text\",\"prompt\":" + jsonString(text) + "}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return parseOllamaEmbedding(resp.body());
    }

    // ------------------------------
    // RRF fusion
    // ------------------------------

    private static List<Scored<RefactorExample>> reciprocalRankFusion(List<Scored<RefactorExample>> sparse,
                                                                      List<Scored<RefactorExample>> dense) {
        Map<RefactorExample, Double> fused = new IdentityHashMap<>();
        addRrf(fused, sparse);
        addRrf(fused, dense);

        List<Scored<RefactorExample>> out = new ArrayList<>();
        for (Map.Entry<RefactorExample, Double> e : fused.entrySet()) {
            out.add(new Scored<>(e.getKey(), e.getValue()));
        }
        out.sort((a, b) -> Double.compare(b.score, a.score));
        return out;
    }

    private static void addRrf(Map<RefactorExample, Double> map, List<Scored<RefactorExample>> ranking) {
        if (ranking == null) return;
        for (int i = 0; i < ranking.size(); i++) {
            int rank = i + 1; // 1-based
            double inc = 1.0 / (RRF_K + rank);
            RefactorExample ex = ranking.get(i).item;
            map.put(ex, map.getOrDefault(ex, 0.0) + inc);
        }
    }

    // ------------------------------
    // CSV loading
    // ------------------------------

    /**
     * Clone DB expected headers:
     *  - code1/code_1, code2/code_2, output|response|label
     * Optional: clone_type|type
     */
    private static List<CloneFewShot> loadCloneFewShot(String pathOrResource, Project project, int maxChars) {
        List<CloneFewShot> out = new ArrayList<>();
        try (BufferedReader br = openCsvReader(pathOrResource, project)) {
            if (br == null) return out;

            String header = readNextCsvRecord(br);
            if (header == null) return out;
            header = stripBom(header);

            String[] hdr = splitCsvLine(header);
            if (hdr == null || hdr.length == 0) return out;

            int idxC1 = -1, idxC2 = -1, idxLabel = -1, idxType = -1;
            for (int i = 0; i < hdr.length; i++) {
                String h = hdr[i].trim().toLowerCase();
                if (h.equals("code1") || h.equals("code_1")) idxC1 = i;
                else if (h.equals("code2") || h.equals("code_2")) idxC2 = i;
                else if (h.equals("output") || h.equals("response") || h.equals("label")) idxLabel = i;
                else if (h.equals("clone_type") || h.equals("type")) idxType = i;
            }
            if (idxC1 < 0 || idxC2 < 0 || idxLabel < 0) return out;

            String rec;
            while ((rec = readNextCsvRecord(br)) != null) {
                if (rec.isBlank()) continue;
                String[] cols = splitCsvLine(rec);
                if (cols.length <= Math.max(idxLabel, Math.max(idxC1, idxC2))) continue;

                String c1 = safeTruncate(cols[idxC1], maxChars);
                String c2 = safeTruncate(cols[idxC2], maxChars);
                String label = cols[idxLabel] == null ? "" : cols[idxLabel].trim();
                String type = (idxType >= 0 && idxType < cols.length) ? (cols[idxType] == null ? "" : cols[idxType].trim()) : "";

                if (!c1.isBlank() && !c2.isBlank() && !label.isBlank()) {
                    out.add(new CloneFewShot(c1, c2, label, type));
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /**
     * Refactor DB expected schemas:
     *  Preferred:
     *    - code_1/code1, code_2/code2, refactor|refactored_code|after|code_after|dest|target
     *    Optional: rationale|note|comment
     *  Legacy:
     *    - before|code_before|src|original, after|refactored_code|code_after|dest|target
     */
    private static List<RefactorExample> loadRefactorExamples(String pathOrResource, Project project, int maxChars) {
        List<RefactorExample> out = new ArrayList<>();
        try (BufferedReader br = openCsvReader(pathOrResource, project)) {
            if (br == null) return out;

            String header = readNextCsvRecord(br);
            if (header == null) return out;
            header = stripBom(header);

            String[] hdr = splitCsvLine(header);
            if (hdr == null || hdr.length == 0) return out;

            int idxC1 = -1, idxC2 = -1, idxRef = -1;
            int idxBefore = -1, idxAfter = -1, idxRat = -1;

            for (int i = 0; i < hdr.length; i++) {
                String h = hdr[i].trim().toLowerCase();
                if (h.equals("code_1") || h.equals("code1")) idxC1 = i;
                else if (h.equals("code_2") || h.equals("code2")) idxC2 = i;
                else if (h.equals("refactor") || h.equals("refactoring") || h.equals("refactored_code")
                        || h.equals("after") || h.equals("code_after") || h.equals("dest") || h.equals("target")) {
                    idxRef = i;
                }

                if (h.equals("rationale") || h.equals("note") || h.equals("comment")) idxRat = i;

                if (h.equals("before") || h.equals("code_before") || h.equals("src") || h.equals("original")) idxBefore = (idxBefore == -1 ? i : idxBefore);
                if (h.equals("after") || h.equals("refactored_code") || h.equals("code_after") || h.equals("dest") || h.equals("target")) idxAfter = (idxAfter == -1 ? i : idxAfter);
            }

            boolean useTriplet = (idxC1 >= 0 && idxC2 >= 0 && idxRef >= 0);

            String rec;
            while ((rec = readNextCsvRecord(br)) != null) {
                if (rec.isBlank()) continue;
                String[] cols = splitCsvLine(rec);

                if (useTriplet) {
                    if (cols.length <= Math.max(idxRef, Math.max(idxC1, idxC2))) continue;
                    String c1 = safeTruncate(cols[idxC1], maxChars);
                    String c2 = safeTruncate(cols[idxC2], maxChars);
                    String ref = safeTruncate(cols[idxRef], maxChars);
                    String rat = (idxRat >= 0 && idxRat < cols.length) ? (cols[idxRat] == null ? "" : cols[idxRat]) : "";

                    if (!c1.isBlank() && !c2.isBlank()) {
                        String before = c1 + "\n/* --- second snippet --- */\n" + c2;
                        String after = (ref == null ? "" : ref.trim());
                        out.add(new RefactorExample(before, after, rat));
                    }
                } else {
                    if (idxBefore < 0 || idxAfter < 0) continue;
                    if (cols.length <= Math.max(idxAfter, idxBefore)) continue;

                    String before = safeTruncate(cols[idxBefore], maxChars);
                    String after  = safeTruncate(cols[idxAfter], maxChars);
                    String rat    = (idxRat >= 0 && idxRat < cols.length) ? (cols[idxRat] == null ? "" : cols[idxRat]) : "";

                    if (!before.isBlank()) {
                        out.add(new RefactorExample(before, after == null ? "" : after, rat));
                    }
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /**
     * Open CSV reader by trying:
     *  1) classpath resource (as given)
     *  2) classpath resource (tail name)
     *  3) filesystem absolute / relative to project root
     */
    private static BufferedReader openCsvReader(String pathOrResource, Project project) throws IOException {
        // 1) classpath as given
        InputStream is = RagService.class.getClassLoader().getResourceAsStream(pathOrResource);
        if (is != null) return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));

        // 2) classpath tail
        int slash = pathOrResource.lastIndexOf('/');
        if (slash >= 0) {
            String tail = pathOrResource.substring(slash + 1);
            InputStream is2 = RagService.class.getClassLoader().getResourceAsStream(tail);
            if (is2 != null) return new BufferedReader(new InputStreamReader(is2, StandardCharsets.UTF_8));
        }

        // 3) filesystem
        File f = new File(pathOrResource);
        if (!f.isAbsolute()) {
            String base = (project != null ? project.getBasePath() : null);
            if (base != null && !base.isBlank()) {
                f = new File(base, pathOrResource);
            }
        }
        if (f.exists() && f.isFile()) {
            return new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
        }

        return null;
    }

    // ------------------------------
    // Utilities
    // ------------------------------

    private static List<RefactorExample> takeTopItems(List<Scored<RefactorExample>> scored, int n) {
        List<RefactorExample> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, scored.size()); i++) out.add(scored.get(i).item);
        return out;
    }

    private static List<String> tokenizeIdentifiers(String s) {
        List<String> toks = new ArrayList<>();
        if (s == null) return toks;
        Matcher m = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*").matcher(s);
        while (m.find()) toks.add(m.group().toLowerCase());
        return toks;
    }

    private static Map<String, Integer> tf(List<String> toks) {
        Map<String, Integer> map = new HashMap<>();
        for (String t : toks) map.put(t, map.getOrDefault(t, 0) + 1);
        return map;
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String stripBom(String s) {
        if (s == null) return null;
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') return s.substring(1);
        return s;
    }

    private static String safeTruncate(String s, int maxChars) {
        if (s == null) return "";
        String t = s.trim();
        if (maxChars <= 0) return t;
        if (t.length() <= maxChars) return t;
        return t.substring(0, maxChars) + "\n...<truncated>...";
    }

    /**
     * Reads a CSV record allowing newlines inside quoted fields.
     */
    private static String readNextCsvRecord(BufferedReader br) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        boolean inQuotes = false;
        while (true) {
            line = br.readLine();
            if (line == null) {
                if (sb.length() == 0) return null;
                break;
            }
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);

            // Update quote state
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '"') {
                    // escaped quote ""
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        i++;
                    } else {
                        inQuotes = !inQuotes;
                    }
                }
            }
            if (!inQuotes) break;
        }
        return sb.toString();
    }

    /**
     * Split CSV line by comma or tab, honoring quotes.
     */
    private static String[] splitCsvLine(String line) {
        if (line == null) return null;
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if ((ch == ',' || ch == '\t') && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    // ------------------------------
    // Embedding JSON helpers
    // ------------------------------

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private static double[] parseOpenAIEmbedding(String json) {
        if (json == null) return null;
        int idx = json.indexOf("\"embedding\"");
        if (idx < 0) return null;
        int lb = json.indexOf('[', idx);
        int rb = json.indexOf(']', lb);
        if (lb < 0 || rb < 0) return null;

        String[] parts = json.substring(lb + 1, rb).split(",");
        double[] v = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { v[i] = Double.parseDouble(parts[i].trim()); } catch (Exception e) { v[i] = 0; }
        }
        return v;
    }

    private static double[] parseOllamaEmbedding(String json) {
        // same format: {"embedding":[...]}
        return parseOpenAIEmbedding(json);
    }

    private static double[] parseHfFeatureExtraction(String json) {
        if (json == null) return null;

        // Usually shape: [[...],[...],...]
        int start = json.indexOf("[[");
        int end = json.indexOf("]]", start + 2);
        if (start < 0 || end < 0) return null;

        String core = json.substring(start + 2, end);
        List<double[]> rows = new ArrayList<>();
        int pos = 0;
        while (pos < core.length()) {
            int next = core.indexOf("],[", pos);
            String row = (next == -1) ? core.substring(pos) : core.substring(pos, next);
            String[] parts = row.split(",");
            double[] vec = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try { vec[i] = Double.parseDouble(parts[i].trim()); } catch (Exception e) { vec[i] = 0; }
            }
            rows.add(vec);
            if (next == -1) break;
            pos = next + 3;
        }
        if (rows.isEmpty()) return null;

        // Mean pooling
        int dim = rows.get(0).length;
        double[] mean = new double[dim];
        for (double[] r : rows) {
            for (int i = 0; i < Math.min(dim, r.length); i++) mean[i] += r[i];
        }
        for (int i = 0; i < dim; i++) mean[i] /= rows.size();
        return mean;
    }

    // ------------------------------
    // Data classes
    // ------------------------------

    private static final class CloneFewShot {
        final String codeA, codeB, label, cloneType;
        CloneFewShot(String a, String b, String l, String t) {
            this.codeA = a; this.codeB = b; this.label = l; this.cloneType = (t == null ? "" : t);
        }
    }

    public static final class RefactorExample {
        public final String before;
        public final String after;
        public final String rationale;
        public RefactorExample(String b, String a, String r) {
            this.before = b;
            this.after = a;
            this.rationale = (r == null ? "" : r);
        }
    }

    private static final class Scored<T> {
        final T item;
        final double score;
        Scored(T i, double s) { item = i; score = s; }
    }
}