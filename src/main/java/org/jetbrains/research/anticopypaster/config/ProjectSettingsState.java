package org.jetbrains.research.anticopypaster.config;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.research.anticopypaster.config.advanced.NewAdvancedProjectSettingsComponent.JavaKeywords;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.research.anticopypaster.config.advanced.NewAdvancedProjectSettingsComponent;

import java.util.EnumMap;

@State(
        name = "org.jetbrains.research.anticopypaster.config.ProjectSettingsState",
        storages = @Storage("anticopypaster-plugin.xml")
)
public class ProjectSettingsState implements PersistentStateComponent<ProjectSettingsState> {

    // PRIMARY SETTINGS STATES
    public boolean useMLModel = false;
    public boolean keywordsRequired = true, couplingRequired = true, sizeRequired = true, complexityRequired = true;
    public int keywordsSensitivity = 50, couplingSensitivity = 50, sizeSensitivity = 50, complexitySensitivity = 50;

    // ADVANCED SETTINGS STATES
    // Each boolean array of two elements follow this scheme {boolean submetric_enabled, boolean submetric_required}

    // Keyword Metric
    public boolean[] measureKeywordsTotal = {false, false}, measureKeywordsDensity = {true, true};
    public EnumMap<JavaKeywords, Boolean> activeKeywords = new EnumMap<>(JavaKeywords.class);
    {
        for (JavaKeywords keyword : JavaKeywords.values()) {
            activeKeywords.put(keyword, true);
        }
    }

    // Coupling Metric
    public boolean[] measureCouplingTotal = {false, false}, measureCouplingDensity = {true, true};
    public boolean[] measureTotalConnectivity = {true, true}, measureFieldConnectivity = {false, false}, measureMethodConnectivity = {false, false};

    // Complexity Metric
    public boolean[] measureComplexityTotal = {false, false}, measureComplexityDensity = {true, true},
            measureMethodDeclarationArea = {false, false}, measureMethodDeclarationDepthPerLine = {true, false};

    // Size Metric
    public boolean[] measureSizeByLines = {true, true}, measureSizeBySymbols = {false, false}, measureSizeBySymbolsPerLine = {false, false};
    public boolean[] measureTotalSize = {true, true}, measureMethodDeclarationSize = {false, false};


    public static ProjectSettingsState getInstance(Project project) {
        return project.getService(ProjectSettingsState.class);
    }

    @Nullable
    @Override
    public ProjectSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull ProjectSettingsState state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}