package org.jetbrains.research.anticopypaster.config.credentials;

import javax.swing.*;

public class CredentialsComponent {
    private JPanel CredentialsPanel;
    private JPasswordField passwordField;
    private JTextField usernameField;
    private JPanel mainPanel;
    private JTextPane byEnteringYourCredentialsTextPane;

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
}
