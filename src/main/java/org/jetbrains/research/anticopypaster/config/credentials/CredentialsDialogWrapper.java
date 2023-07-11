package org.jetbrains.research.anticopypaster.config.credentials;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;

import javax.swing.*;

public class CredentialsDialogWrapper extends DialogWrapper {

    Project project;
    private CredentialsComponent credentialsComponent;

    public CredentialsDialogWrapper(Project project) {
        super(true);
        this.project = project;

        setTitle("Statistics Collection Credentials");
        setResizable(true);

        init();
    }

    @Nullable
    @Override
    public JComponent createCenterPanel() {
        credentialsComponent = new CredentialsComponent();
        setFields();
        return credentialsComponent.getPanel();
    }

    @Nullable
    @Override
    public JComponent getPreferredFocusedComponent() {
        return credentialsComponent.getPreferredFocusedComponent();
    }

    public void saveSettings(boolean pressedOK) {
        if (pressedOK) {
            ProjectSettingsState settings = ProjectSettingsState.getInstance(project);

            settings.statisticsUsername = credentialsComponent.getUsername();
            settings.statisticsPassword = credentialsComponent.getPassword();
        }
    }

    public void setFields() {
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
        credentialsComponent.setUsernameField(settings.statisticsUsername);
    }

}
