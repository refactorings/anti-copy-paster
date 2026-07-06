package org.jetbrains.research.anticopypaster.workflow;

import com.intellij.openapi.project.Project;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jetbrains.research.anticopypaster.agents.detection;

final class CrossFileMetricsGateSupport {
    static final class Result {
        final List<CrossFileClone> passedClones;
        final WorkflowCloneMetricsGate.Result metricsGate;

        Result(List<CrossFileClone> passedClones, WorkflowCloneMetricsGate.Result metricsGate) {
            this.passedClones = passedClones == null ? java.util.Collections.emptyList() : passedClones;
            this.metricsGate = metricsGate;
        }

        boolean hasPassedClones() {
            return !passedClones.isEmpty();
        }

        boolean hasEvaluatedClones() {
            return metricsGate != null && metricsGate.hasEvaluatedClones();
        }
    }

    private CrossFileMetricsGateSupport() {}

    static Result filter(Project project, List<CrossFileClone> clones, Consumer<String> viewer) {
        if (clones == null || clones.isEmpty()) {
            WorkflowCloneMetricsGate.Result empty = WorkflowCloneMetricsGate.filter(
                    project,
                    java.util.Collections.emptyList(),
                    viewer,
                    clone -> java.util.Collections.emptyList(),
                    (range, code) -> null,
                    ranges -> ""
            );
            return new Result(java.util.Collections.emptyList(), empty);
        }

        Map<detection.DetectedClone, CrossFileClone> originalByDetected = new IdentityHashMap<>();
        Map<detection.CloneRange, CrossFileOccurrence> occurrenceByRange = new IdentityHashMap<>();
        ArrayList<detection.DetectedClone> detectedClones = new ArrayList<>();

        for (CrossFileClone crossFileClone : clones) {
            detection.DetectedClone detectedClone = toDetectedClone(crossFileClone, occurrenceByRange);
            detectedClones.add(detectedClone);
            originalByDetected.put(detectedClone, crossFileClone);
        }

        WorkflowCloneMetricsGate.Result metricsGate = WorkflowCloneMetricsGate.filter(
                project,
                detectedClones,
                viewer,
                clone -> WorkflowCloneSelectionSupport.getDetectedCloneCodes(clone, null),
                (range, code) -> {
                    CrossFileOccurrence occurrence = occurrenceByRange.get(range);
                    if (occurrence == null || occurrence.source == null) {
                        return null;
                    }
                    return WorkflowCloneSelectionSupport.calculateCloneOccurrenceFeatures(
                            project,
                            occurrence.source.vf,
                            occurrence.source.source,
                            range,
                            code
                    );
                },
                ranges -> summarizeRanges(ranges, occurrenceByRange)
        );

        ArrayList<CrossFileClone> passedClones = new ArrayList<>();
        if (metricsGate != null && metricsGate.passedClones != null) {
            for (detection.DetectedClone passed : metricsGate.passedClones) {
                CrossFileClone original = originalByDetected.get(passed);
                if (original != null) {
                    passedClones.add(original);
                }
            }
        }
        return new Result(passedClones, metricsGate);
    }

    private static detection.DetectedClone toDetectedClone(CrossFileClone clone,
                                                          Map<detection.CloneRange, CrossFileOccurrence> occurrenceByRange) {
        detection.DetectedClone out = new detection.DetectedClone();
        out.id = clone == null ? "cross_clone" : clone.displayId();
        out.refactorType = clone == null ? "Extract Method" : clone.refactorType;
        out.reason = clone == null ? "" : clone.reason;
        out.ranges = new ArrayList<>();
        out.cloneCodes = new ArrayList<>();

        if (clone != null) {
            for (CrossFileOccurrence occurrence : clone.occurrences) {
                if (occurrence == null) continue;
                detection.CloneRange range = new detection.CloneRange();
                range.startLine = occurrence.startLine;
                range.endLine = occurrence.endLine;
                out.ranges.add(range);
                out.cloneCodes.add(occurrence.snippet == null ? "" : occurrence.snippet);
                occurrenceByRange.put(range, occurrence);
            }
        }

        out.cloneCodeA = out.cloneCodes.isEmpty() ? "" : out.cloneCodes.get(0);
        out.cloneCodeB = out.cloneCodes.size() > 1 ? out.cloneCodes.get(1) : "";
        return out;
    }

    private static String summarizeRanges(List<detection.CloneRange> ranges,
                                          Map<detection.CloneRange, CrossFileOccurrence> occurrenceByRange) {
        if (ranges == null || ranges.isEmpty()) {
            return "";
        }
        ArrayList<String> parts = new ArrayList<>();
        for (detection.CloneRange range : ranges) {
            if (range == null) continue;
            CrossFileOccurrence occurrence = occurrenceByRange.get(range);
            String path = occurrence == null || occurrence.source == null ? "" : occurrence.source.relativePath;
            if (path == null || path.isBlank()) {
                path = "<unknown>";
            }
            parts.add(path + ":" + range.startLine + "-" + range.endLine);
        }
        return String.join(", ", parts);
    }
}
