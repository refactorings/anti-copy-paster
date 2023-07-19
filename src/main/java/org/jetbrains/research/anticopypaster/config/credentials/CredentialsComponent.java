package org.jetbrains.research.anticopypaster.config.credentials;

import com.intellij.icons.AllIcons;

import javax.swing.*;
import static org.jetbrains.research.anticopypaster.config.ProjectSettingsComponent.createLinkListener;

public class CredentialsComponent {
    private JPanel CredentialsPanel;
    private JPasswordField passwordField;
    private JTextField usernameField;
    private JPanel mainPanel;
    private JTextPane byEnteringYourCredentialsTextPane;
    private JLabel learnMore;
    private JLabel passwordLabel;
    private JLabel infoLabel;
    private JLabel usernameLabel;

    public CredentialsComponent() { createUIComponents(); }

    public JPanel getPanel() {
        return mainPanel;
    }
    public JComponent getPreferredFocusedComponent() {
        return usernameField;
    }

    public String getUsername() {
        return usernameField.getText();
    }
    public String getPassword() {
        return passwordField.getText();
    }

    public void setUsernameField(String username) {
        usernameField.setText(username);
    }

    private void createUIComponents() {
        // Add misc. icons
        usernameLabel.setIcon(AllIcons.General.User);
        passwordLabel.setIcon(AllIcons.Diff.Lock);

        // Initialize help interface
        //TODO: PlACEHOLDER - replace with help page on website
        createLinkListener(learnMore, "www.google.com");
        learnMore.setIcon(AllIcons.Ide.External_link_arrow);
        infoLabel.setIcon(AllIcons.General.Note);
    }
}
