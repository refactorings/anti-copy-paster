package org.jetbrains.research.anticopypaster.config;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(
        name = "org.jetbrains.research.anticopypaster.config.ProjectSettingsState",
        storages = @Storage("anticopypaster-plugin.xml")
)
public class ProjectSettingsState implements PersistentStateComponent<ProjectSettingsState> {

    public boolean useMLModel = false;
    public boolean keywordsRequired = true, couplingRequired = true, sizeRequired = true, complexityRequired = true;
    public int keywordsSensitivity = 50, couplingSensitivity = 50, sizeSensitivity = 50, complexitySensitivity = 50;

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
