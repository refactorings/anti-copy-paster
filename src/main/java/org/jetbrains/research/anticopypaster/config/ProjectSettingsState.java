package org.jetbrains.research.anticopypaster.config;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.research.anticopypaster.config.advanced.AdvancedProjectSettingsComponent.JavaKeywords;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.EnumMap;

@Service(Service.Level.PROJECT)
@State(
        name = "org.jetbrains.research.anticopypaster.config.ProjectSettingsState",
        storages = @Storage("anticopypaster-plugin.xml")
)
public final class ProjectSettingsState implements PersistentStateComponent<ProjectSettingsState> {

    // PRIMARY SETTINGS STATES
    public int minimumDuplicateMethods = 2;
    public int timeBuffer = 10;
    public JudgementModel judgementModel = JudgementModel.TENSORFLOW;
    public ExtractionType extractionType = ExtractionType.TYPE_TWO;

    public int maxParams = 10;

    public boolean keywordsEnabled = true, couplingEnabled = true, sizeEnabled = true, complexityEnabled = true,
            keywordsRequired = false, couplingRequired = false, sizeRequired = false, complexityRequired = false;

    public int keywordsSensitivity = 50, couplingSensitivity = 50, sizeSensitivity = 50, complexitySensitivity = 50;
    public float modelSensitivity = .3f;

    // ADVANCED SETTINGS STATES
    // Each boolean array of two elements follow this scheme {boolean submetric_enabled, boolean submetric_required}

    // Keyword Metric
    public Boolean[] measureKeywordsTotal = {false, false}, measureKeywordsDensity = {true, true};
    public EnumMap<JavaKeywords, Boolean> activeKeywords = new EnumMap<>(JavaKeywords.class);
    {
        for (JavaKeywords keyword : JavaKeywords.values()) {
            activeKeywords.put(keyword, true);
        }
    }

    // Coupling Metric
    public Boolean[] measureCouplingTotal = {false, false}, measureCouplingDensity = {true, true};
    public Boolean[] measureTotalConnectivity = {true, true}, measureFieldConnectivity = {false, false}, measureMethodConnectivity = {false, false};

    // Complexity Metric
    public Boolean[] measureComplexityTotal = {false, false}, measureComplexityDensity = {true, true},
            measureMethodDeclarationArea = {false, false}, measureMethodDeclarationDepthPerLine = {false, false};

    // Size Metric
    public Boolean[] measureSizeByLines = {true, true}, measureSizeBySymbols = {false, false}, measureSizeBySymbolsPerLine = {false, false};
    public Boolean[] measureTotalSize = {true, true}, measureMethodDeclarationSize = {false, false};

    // Statistics collection database credentials
    public String statisticsUsername = null;
    public boolean statisticsPasswordIsSet = false;

    public int useNameRec = 1;
    public int numOfPreds = 3;

    // Aider settings
    public String aiderApiKey = "";
    public String aiderModel = "gemini-2.5-pro";
    public String aiderPath = "aider";

    public String llmProvider = "";

    public String filesPath = "";

    public ArrayList<JCheckBox> allFilesCheckboxes = new ArrayList<>();

    public String selectedAnalysisButton;

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

    public String getAiderApiKey() {
        return aiderApiKey;
    }

    public void setAiderApiKey(String apiKey) {
        this.aiderApiKey = apiKey;
    }

    public String getAiderModel() {
        return aiderModel;
    }

    public void setAiderModel(String model) {
        this.aiderModel = model;
    }

    public String getAiderPath() {
        return aiderPath;
    }

    public void setAiderPath(String path) {
        this.aiderPath = path;
    }

    public String getLlmprovider() {
        return llmProvider;
    }

    public void setLlmprovider(String provider) {
        this.llmProvider = provider;
    }

    public String getFilesPath() {
        return filesPath;
    }

    public void setFilesPath(String path) {
        this.filesPath = path;
    }

    public ArrayList<JCheckBox> getAllFilesCheckboxes() {
        return allFilesCheckboxes;
    }

    public void setAllFilesCheckboxes(ArrayList<JCheckBox> filesCheckboxes) {
        (this.allFilesCheckboxes).clear();
        (this.allFilesCheckboxes).addAll(filesCheckboxes);
    }

    public String getSelectedAnalysisButton() {
        return selectedAnalysisButton;
    }

    public void setSelectedAnalysisButton(String analysisButton) {
        this.selectedAnalysisButton = analysisButton;
    }

    public enum JudgementModel {
        TENSORFLOW(0),
        USER_SETTINGS(1),
        AIDER(2);
        private int idx;
        JudgementModel(int idx) {
            this.idx = idx;
        }
        public int getIdx() {
            return idx;
        }
    }

    public enum ExtractionType {
        TYPE_ONE(0),
        TYPE_TWO(1);

        private int idx;
        ExtractionType(int idx) {
            this.idx = idx;
        }
        public int getIdx() {
            return idx;
        }
    }
}
