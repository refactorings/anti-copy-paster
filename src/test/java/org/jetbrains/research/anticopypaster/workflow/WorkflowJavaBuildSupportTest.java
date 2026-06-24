package org.jetbrains.research.anticopypaster.workflow;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.research.anticopypaster.agents.testing;

public class WorkflowJavaBuildSupportTest extends LightJavaCodeInsightFixtureTestCase {

    public void testFindExistingTestTargetUsesConventionalTestFile() throws Exception {
        Path baseDir = Files.createTempDirectory("acp-existing-test-target");
        Path testFile = baseDir.resolve("src/test/java/demo/FooTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package demo;

                public class FooTest {
                    public void testValue() {
                        new Foo().value();
                    }
                }
                """, StandardCharsets.UTF_8);

        WorkflowJavaBuildSupport support = new WorkflowJavaBuildSupport(
                getProject(),
                null,
                new AtomicReference<>(),
                () -> false
        );

        WorkflowJavaBuildSupport.ExistingTestTarget target =
                support.findExistingTestTarget("demo.Foo", baseDir.toFile());

        assertNotNull(target);
        assertEquals("demo.FooTest", target.fqn);
        assertEquals(testFile.toFile().getAbsolutePath(), target.file.getAbsolutePath());
    }

    public void testClassBytecodeJavaMajorOnClasspathReadsJava11Class() throws Exception {
        Path baseDir = Files.createTempDirectory("acp-class-version");
        Path classFile = baseDir.resolve("demo/Foo.class");
        writeMinimalClassHeader(classFile, 55);

        WorkflowJavaBuildSupport support = new WorkflowJavaBuildSupport(
                getProject(),
                null,
                new AtomicReference<>(),
                () -> false
        );

        assertEquals(11, support.classBytecodeJavaMajorOnClasspath("demo.Foo", baseDir.toString()));
        assertEquals(11, WorkflowJavaBuildSupport.javaMajorFromClassFileMajor(55));
        assertEquals(8, WorkflowJavaBuildSupport.javaMajorFromClassFileMajor(52));
    }

    public void testCrossFileTestingRecognizesJavaVersionMismatchSkip() {
        testing.TestResult result = new testing.TestResult();
        result.raw = """
                [TEST_SKIPPED]
                status=tests_skipped
                reason=java_version_mismatch
                targetJavaMajor=11
                runtimeJavaMajor=8
                BUILD SUCCESS
                """;

        assertTrue(CrossFileTestingSupport.isJavaVersionMismatchSkip(result));
    }

    public void testCrossFileTestingRecognizesUnsupportedClassVersionSkip() {
        testing.TestResult result = new testing.TestResult();
        result.raw = """
                Exception in thread "main" java.lang.UnsupportedClassVersionError:
                class file version 55.0, this version of the Java Runtime only recognizes class file versions up to 52.0
                BUILD FAILED
                """;

        assertTrue(CrossFileTestingSupport.isJavaVersionMismatchSkip(result));
    }

    public void testSelectCompatibleJavaExecutableSkipsJava8ForJava11Target() {
        String selected = WorkflowJavaBuildSupport.selectCompatibleJavaExecutable(
                List.of(
                        new WorkflowJavaBuildSupport.JavaRuntimeCandidate("/jdk8/bin/java", 8),
                        new WorkflowJavaBuildSupport.JavaRuntimeCandidate("/jdk11/bin/java", 11),
                        new WorkflowJavaBuildSupport.JavaRuntimeCandidate("/jdk17/bin/java", 17)
                ),
                11
        );

        assertEquals("/jdk11/bin/java", selected);
    }

    private static void writeMinimalClassHeader(Path classFile, int classFileMajor) throws Exception {
        Files.createDirectories(classFile.getParent());
        byte[] header = new byte[] {
                (byte) 0xCA,
                (byte) 0xFE,
                (byte) 0xBA,
                (byte) 0xBE,
                0,
                0,
                (byte) ((classFileMajor >> 8) & 0xFF),
                (byte) (classFileMajor & 0xFF)
        };
        Files.write(classFile, header);
    }
}
