package org.jetbrains.research.anticopypaster.models;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

public class CopilotModel extends PredictionModel {
    private final Project project;
    private final PsiFile file;

    public CopilotModel(Project project, PsiFile file) {
        this.project = project;
        this.file = file;
    }


    @Override
    public float predict(FeaturesVector featuresVector) {
        // Placeholder implementation for Copilot model prediction
        return 1.0f;
    }
}
