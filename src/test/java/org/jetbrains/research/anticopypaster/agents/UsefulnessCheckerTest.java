package org.jetbrains.research.anticopypaster.agents;

import com.intellij.testFramework.LightPlatformTestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class UsefulnessCheckerTest extends LightPlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        ensureCode2VecModelMarker();
        super.setUp();
    }

    private static void ensureCode2VecModelMarker() throws Exception {
        Path marker = Path.of(System.getProperty("user.dir"),
                "build",
                "idea-sandbox",
                "plugins-test",
                "AntiCopyPaster",
                "code2vec",
                "java14m_model",
                "models",
                "java14_model",
                "dictionaries.bin");
        Files.createDirectories(marker.getParent());
        if (Files.notExists(marker)) {
            Files.createFile(marker);
        }
    }

    public void testAnalyzeUsesExactTargetMethodSignatureForOverloads() {
        String beforeSource = """
                package org.jhotdraw.contrib;

                import org.jhotdraw.framework.DrawingView;
                import java.awt.*;

                public class Helper {

                    static public DrawingView getDrawingView(Container container) {
                        DrawingView oldDrawingView = null;
                        Component[] components = container.getComponents();
                        for (Component component : components) {
                            if (component instanceof DrawingView) {
                                return (DrawingView) component;
                            } else if (component instanceof Container) {
                                oldDrawingView = getDrawingView((Container) component);
                                if (oldDrawingView != null) {
                                    return oldDrawingView;
                                }
                            }
                        }
                        return null;
                    }

                    static public DrawingView getDrawingView_cloned(Container container) {
                        DrawingView oldDrawingView = null;
                        Component[] components = container.getComponents();
                        for (Component component : components) {
                            if (component instanceof DrawingView) {
                                return (DrawingView) component;
                            } else if (component instanceof Container) {
                                oldDrawingView = getDrawingView((Container) component);
                                if (oldDrawingView != null) {
                                    return oldDrawingView;
                                }
                            }
                        }
                        return null;
                    }

                    static public DrawingView getDrawingView(Component component) {
                        if (component instanceof Container) {
                            return getDrawingView((Container) component);
                        } else if (component instanceof DrawingView) {
                            return (DrawingView) component;
                        } else {
                            return null;
                        }
                    }
                }
                """;

        String afterSource = """
                package org.jhotdraw.contrib;

                import org.jhotdraw.framework.DrawingView;
                import java.awt.*;

                public class Helper {

                    static public DrawingView getDrawingView(Container container) {
                        return findDrawingViewInContainer(container);
                    }

                    static public DrawingView getDrawingView_cloned(Container container) {
                        return findDrawingViewInContainer(container);
                    }

                    static public DrawingView getDrawingView(Component component) {
                        if (component instanceof Container) {
                            return getDrawingView((Container) component);
                        } else if (component instanceof DrawingView) {
                            return (DrawingView) component;
                        } else {
                            return null;
                        }
                    }

                    private static DrawingView findDrawingViewInContainer(Container container) {
                        DrawingView oldDrawingView = null;
                        Component[] components = container.getComponents();
                        for (Component component : components) {
                            if (component instanceof DrawingView) {
                                return (DrawingView) component;
                            } else if (component instanceof Container) {
                                oldDrawingView = getDrawingView((Container) component);
                                if (oldDrawingView != null) {
                                    return oldDrawingView;
                                }
                            }
                        }
                        return null;
                    }
                }
                """;

        List<usefulnessChecker.TargetMethodHint> targetHints = List.of(
                new usefulnessChecker.TargetMethodHint(
                        "org.jhotdraw.contrib.Helper",
                        "getDrawingView",
                        1,
                        "org.jhotdraw.contrib.Helper#getDrawingView(java.awt.Container)"
                ),
                new usefulnessChecker.TargetMethodHint(
                        "org.jhotdraw.contrib.Helper",
                        "getDrawingView_cloned",
                        1,
                        "org.jhotdraw.contrib.Helper#getDrawingView_cloned(java.awt.Container)"
                )
        );

        usefulnessChecker.UsefulnessResult result = usefulnessChecker.analyze(
                getProject(),
                "Helper.java",
                beforeSource,
                afterSource,
                new usefulnessChecker.UsefulnessConfig(),
                targetHints
        );

        assertNotNull(result);
        assertTrue("Usefulness result was not useful: reasons=" + result.reasons + ", notes=" + result.notes, result.isUseful);
        assertEquals(List.of(usefulnessChecker.Reason.EXTRACT_METHOD_CONFIRMED), result.reasons);
        assertTrue(result.notes.contains("targetMethods=2"));
        assertTrue(result.notes.contains("targetPairs=1"));
    }
}
