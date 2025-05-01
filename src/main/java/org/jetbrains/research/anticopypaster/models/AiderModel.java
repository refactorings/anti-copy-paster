package org.jetbrains.research.anticopypaster.models;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

public class AiderModel extends PredictionModel {
    private final Project project;
    private final PsiFile file;

    public AiderModel(Project project, PsiFile file) {
        this.project = project;
        this.file = file;
    }

    @Override
    public float predict(FeaturesVector features) {

        // Always return 1.0f to indicate "recommend refactor" regardless of detection result for now
        return 1.0f;
    }
}
