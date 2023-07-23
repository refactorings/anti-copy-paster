package org.jetbrains.research.anticopypaster;

import com.intellij.AbstractBundle;
import com.intellij.reference.SoftReference;
import org.jetbrains.annotations.PropertyKey;

import java.lang.ref.Reference;
import java.util.Locale;
import java.util.ResourceBundle;

public final class AntiCopyPasterBundle {
    private static final String BUNDLE = "AntiCopyPasterBundle";
    private static Reference<ResourceBundle> INSTANCE;

    private AntiCopyPasterBundle() {
    }

    public static String message(@PropertyKey(resourceBundle = BUNDLE) String key, Object... params) {
        return AbstractBundle.message(getBundle(), key, params);
    }

    private static ResourceBundle getBundle() {
        ResourceBundle bundle = SoftReference.dereference(INSTANCE);
        if (bundle == null) {
            //Locale locale = new Locale("es");
            //System.out.println("Searching for resource bundle with locale: " + locale);
            //bundle = ResourceBundle.getBundle(BUNDLE, new Locale("es"));
            bundle = ResourceBundle.getBundle(BUNDLE);
            INSTANCE = new SoftReference<>(bundle);
        }
        return bundle;
    }
}