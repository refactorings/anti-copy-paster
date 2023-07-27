package org.jetbrains.research.anticopypaster.config.credentials;

import javax.swing.*;

public class CredentialsComponent {
    private JPasswordField passwordField;
    private JTextField usernameField;
    private JPanel mainPanel;

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
        return String.valueOf(passwordField.getPassword());
    }

    public void setUsernameField(String username) {
        usernameField.setText(username);
    }
}
