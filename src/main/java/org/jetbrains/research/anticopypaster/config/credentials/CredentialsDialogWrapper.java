package org.jetbrains.research.anticopypaster.config.credentials;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import org.jetbrains.research.anticopypaster.AntiCopyPasterBundle;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;

import javax.swing.*;

import static com.intellij.remoteServer.util.CloudConfigurationUtil.createCredentialAttributes;

public class CredentialsDialogWrapper extends DialogWrapper {

    Project project;
    private CredentialsComponent credentialsComponent;

    public CredentialsDialogWrapper(Project project) {
        super(true);
        this.project = project;

        setTitle(AntiCopyPasterBundle.message("settings.credentials.title"));
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

            // Securely store credentials.
            CredentialAttributes credentialAttributes = createCredentialAttributes("mongoDBStatistics", credentialsComponent.getUsername());
            if (credentialAttributes == null)   return;
            Credentials credentials = new Credentials(credentialsComponent.getUsername(), credentialsComponent.getPassword());
            PasswordSafe.getInstance().set(credentialAttributes, credentials);

            // Non-securely store username and the existence of a password.
            settings.statisticsUsername = credentialsComponent.getUsername();
            if (!credentialsComponent.getPassword().isEmpty()) { settings.statisticsPasswordIsSet = true; }
        }
    }

    // Pull from saved state to preset dialog state upon opening
    public void setFields() {
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
        credentialsComponent.setUsernameField(settings.statisticsUsername);
    }
}
