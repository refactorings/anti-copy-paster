package org.jetbrains.research.anticopypaster.config.advanced;

import javax.swing.*;
import java.util.EnumMap;

public class AdvancedProjectSettingsComponent {

    private JPanel mainPanel;
    private JRadioButton measureTotalKeywordCountRadioButton;
    private JCheckBox continueCheckBox;
    private JCheckBox forCheckBox;
    private JCheckBox newCheckBox;
    private JCheckBox switchCheckBox;
    private JCheckBox assertCheckBox;
    private JCheckBox synchronizedCheckBox;
    private JCheckBox booleanCheckBox;
    private JCheckBox doCheckBox;
    private JCheckBox ifCheckBox;
    private JCheckBox throwCheckBox;
    private JCheckBox instanceofCheckBox;
    private JCheckBox intCheckBox;
    private JCheckBox finalCheckBox;
    private JCheckBox floatCheckBox;
    private JCheckBox thisCheckBox;
    private JCheckBox byteCheckBox;
    private JCheckBox returnCheckBox;
    private JCheckBox shortCheckBox;
    private JCheckBox finallyCheckBox;
    private JCheckBox superCheckBox;
    private JCheckBox breakCheckBox;
    private JCheckBox elseCheckBox;
    private JCheckBox transientCheckBox;
    private JCheckBox tryCheckBox;
    private JCheckBox longCheckBox;
    private JCheckBox whileCheckBox;
    private JCheckBox doubleCheckBox;
    private JCheckBox strictfpCheckBox;
    private JCheckBox charCheckBox;
    private JCheckBox catchCheckBox;
    private JCheckBox caseCheckBox;
    private JRadioButton measurePerLineKeywordRadioButton;
    private JRadioButton useTotalConnectivityRadioButton;
    private JRadioButton useFieldConnectivityRadioButton;
    private JRadioButton useMethodConnectivityRadioButton;
    private JRadioButton defineSizeByNumberOfLines;
    private JRadioButton defineSizeByNumberOfSymbols;
    private JRadioButton measureTotalComplexityRadioButton;
    private JRadioButton measureComplexityPerLineRadioButton;
    private JRadioButton measureTotalConnectivityRadioButton;
    private JRadioButton measureConnectivityPerLineRadioButton;


    public JPanel getPanel() {
        return mainPanel;
    }

    public JComponent getPreferredFocusedComponent() {
        return defineSizeByNumberOfLines;
    }

    public void setDefineSizeByLines(boolean byLines) {
        defineSizeByNumberOfLines.setSelected(byLines);
        defineSizeByNumberOfSymbols.setSelected(!byLines);
    }

    public void setDefineKeywordsByTotal(boolean totalMode) {
        measureTotalKeywordCountRadioButton.setSelected(totalMode);
        measurePerLineKeywordRadioButton.setSelected(!totalMode);
    }

    public enum JavaKeywords {
        CONTINUE, FOR, NEW, SWITCH, ASSERT, SYNCHRONIZED, BOOLEAN, DO, IF, THIS, BREAK, DOUBLE, THROW, BYTE, ELSE,
        CASE, INSTANCEOF, RETURN, TRANSIENT, CATCH, INT, SHORT, TRY, CHAR, FINAL, FINALLY, LONG, STRICTFP, FLOAT, SUPER, WHILE
    }

    public void setActiveKeywords(EnumMap <JavaKeywords, Boolean> active) {
        continueCheckBox.setSelected(active.get(JavaKeywords.CONTINUE));
        forCheckBox.setSelected(active.get(JavaKeywords.FOR));
        newCheckBox.setSelected(active.get(JavaKeywords.NEW));
        switchCheckBox.setSelected(active.get(JavaKeywords.SWITCH));
        assertCheckBox.setSelected(active.get(JavaKeywords.ASSERT));
        synchronizedCheckBox.setSelected(active.get(JavaKeywords.SYNCHRONIZED));
        booleanCheckBox.setSelected(active.get(JavaKeywords.BOOLEAN));
        doCheckBox.setSelected(active.get(JavaKeywords.DO));
        ifCheckBox.setSelected(active.get(JavaKeywords.IF));
        thisCheckBox.setSelected(active.get(JavaKeywords.THIS));
        breakCheckBox.setSelected(active.get(JavaKeywords.BREAK));
        doubleCheckBox.setSelected(active.get(JavaKeywords.DOUBLE));
        throwCheckBox.setSelected(active.get(JavaKeywords.THROW));
        byteCheckBox.setSelected(active.get(JavaKeywords.BYTE));
        elseCheckBox.setSelected(active.get(JavaKeywords.ELSE));
        caseCheckBox.setSelected(active.get(JavaKeywords.CASE));
        instanceofCheckBox.setSelected(active.get(JavaKeywords.INSTANCEOF));
        returnCheckBox.setSelected(active.get(JavaKeywords.RETURN));
        transientCheckBox.setSelected(active.get(JavaKeywords.TRANSIENT));
        catchCheckBox.setSelected(active.get(JavaKeywords.CATCH));
        intCheckBox.setSelected(active.get(JavaKeywords.INT));
        shortCheckBox.setSelected(active.get(JavaKeywords.SHORT));
        tryCheckBox.setSelected(active.get(JavaKeywords.TRY));
        charCheckBox.setSelected(active.get(JavaKeywords.CHAR));
        finalCheckBox.setSelected(active.get(JavaKeywords.FINAL));
        finallyCheckBox.setSelected(active.get(JavaKeywords.FINALLY));
        longCheckBox.setSelected(active.get(JavaKeywords.LONG));
        strictfpCheckBox.setSelected(active.get(JavaKeywords.STRICTFP));
        floatCheckBox.setSelected(active.get(JavaKeywords.FLOAT));
        superCheckBox.setSelected(active.get(JavaKeywords.SUPER));
        whileCheckBox.setSelected(active.get(JavaKeywords.WHILE));
    }


    public void setDefineComplexityByTotal(boolean totalMode) {
        measureTotalComplexityRadioButton.setSelected(totalMode);
        measureComplexityPerLineRadioButton.setSelected(totalMode);
    }

    public void setDefineCouplingByTotal(boolean totalMode) {
        measureTotalConnectivityRadioButton.setSelected(totalMode);
        measureConnectivityPerLineRadioButton.setSelected(!totalMode);
    }

    // Pass 0, 1, and 2 for total, field, and method connectivity respectively.
    public void setDefineConnectivityType(int connectivityType) {
        useTotalConnectivityRadioButton.setSelected(0 == connectivityType);
        useFieldConnectivityRadioButton.setSelected(1 == connectivityType);
        useMethodConnectivityRadioButton.setSelected(2 == connectivityType);
    }


    public boolean getSizeDeterminedByLineCount() { return defineSizeByNumberOfLines.isSelected(); }

    public boolean getKeywordsDefinedByTotal() { return measureTotalKeywordCountRadioButton.isSelected(); }

    public EnumMap<JavaKeywords, Boolean> getActiveKeywords() {
        EnumMap<JavaKeywords, Boolean> activeKeywords = new EnumMap<>(JavaKeywords.class);

        activeKeywords.put(JavaKeywords.CONTINUE, continueCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.FOR, forCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.NEW, newCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.SWITCH, switchCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.ASSERT, assertCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.SYNCHRONIZED, synchronizedCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.BOOLEAN, booleanCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.DO, doCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.IF, ifCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.THIS, thisCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.BREAK, breakCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.DOUBLE, doubleCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.THROW, throwCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.BYTE, byteCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.ELSE, elseCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.CASE, caseCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.INSTANCEOF, instanceofCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.RETURN, returnCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.TRANSIENT, transientCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.CATCH, catchCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.INT, intCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.SHORT, shortCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.TRY, tryCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.CHAR, charCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.FINAL, finalCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.FINALLY, finallyCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.LONG, longCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.STRICTFP, strictfpCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.FLOAT, floatCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.SUPER, superCheckBox.isSelected());
        activeKeywords.put(JavaKeywords.WHILE, whileCheckBox.isSelected());

        return activeKeywords;
    }

    public boolean getComplexityDefinedByTotal() { return measureTotalComplexityRadioButton.isSelected(); }

    public boolean getCouplingDefinedByTotal() { return measureTotalConnectivityRadioButton.isSelected(); }

    public int getConnectivityType() {
        if (useFieldConnectivityRadioButton.isSelected())   return 1;
        if (useMethodConnectivityRadioButton.isSelected())  return 2;
        return 0;
    }
}
