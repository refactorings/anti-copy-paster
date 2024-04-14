package org.jetbrains.research.anticopypaster.ide;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.LightPlatformTestCase;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;
import java.io.IOException;


public class RefactoringEventTest extends LightPlatformTestCase {

    /**
     * Successful clone detection of basic Type 1 clones
     * TypeOneBasicExample.java contains 5 methods, 4 of which are Type 1 clones of each other.
     * testCode is the base code, and is copied exactly in TypeOneBasicExample.java's method sumProd()
     * Expected outcome is result
     * @throws IOException on failure to open project
     * @throws JDOMException on failure to open project
     */
    public void testResolveMethodType1() throws IOException, JDOMException {
                String testCode = """
                            float sum=0.0; //C1
                            float prod =1.0;
                            for (int i=1; i<=n; i++)
                                {sum=sum + i;
                                prod = prod * i;
                                foo(sum, prod); }
                            };
                        """;
                String targetMethodName = "duplicate1c"; //chosen arbitrarily. Any of the 4 clone methods works.
        DuplicatesInspection testDuplicates = new DuplicatesInspection();

        ProjectManager myProjectManager = ProjectManager.getInstance();
        assert myProjectManager != null;

        Project myProject = myProjectManager.loadAndOpenProject("./anti-copy-paster");
        assert myProject != null;

        PsiManager psiManager = PsiManager.getInstance(myProject);

        VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath("./src/test/resources/testdata/TypeOneBasicExample.java");
        assert virtualFile != null;

        PsiFile file = psiManager.findFile(virtualFile);
        assert file != null;

        //Obtains the PSI for targetMethodName
        //targetMethodName represents the method in which code was pasted
        final PsiMethod[] targetMethod = {null};
        file.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                if (method.getName().equals(targetMethodName)) {
                    targetMethod[0] = method;
                }
                super.visitMethod(method);
            }
        });

        //Invokes the AntiCopyPaster detection method
        DuplicatesInspection.InspectionResult result = testDuplicates.resolve(file, targetMethod[0], testCode);

        assertFalse(result.results().isEmpty());
        assertEquals(4, result.results().size());

        //Confirms that the detected methods are all the expected clones, in order they are listed in the file.
        //foo is correctly not detected
        String methodTextS = result.results().get(0).start().getParent().getParent().getText();
        String methodTextA = result.results().get(1).start().getParent().getParent().getText();
        String methodTextB = result.results().get(2).start().getParent().getParent().getText();
        String methodTextC = result.results().get(3).start().getParent().getParent().getText();

        assertEquals("""
                public void sumProd(int n) {
                        float sum=0.0; //C1
                        float prod =1.0;
                        for (int i=1; i<=n; i++)
                            {sum=sum + i;
                            prod = prod * i;
                            foo(sum, prod); }
                    }""", methodTextS);

        assertEquals("""
                public void duplicate1a(int n) {
                        float sum=0.0; //C1
                        float prod =1.0;
                        for (int i=1; i<=n; i++)
                            {sum=sum + i;
                            prod = prod * i;
                            foo(sum, prod); }
                    }""", methodTextA);

        assertEquals("""
                public void duplicate1b(int n) {
                        float sum=0.0; //C1'
                        float prod =1.0; //C
                        for (int i=1; i<=n; i++)
                            {sum=sum + i;
                            prod = prod * i;
                            foo(sum, prod); }
                    }""", methodTextB);

        assertEquals("""
                public void duplicate1c(int n) {
                        float sum=0.0; //C1
                        float prod =1.0;
                        for (int i=1; i<=n; i++) {
                            sum=sum + i;
                            prod = prod * i;
                            foo(sum, prod); }
                    }""", methodTextC);

    }

    /**
     * Successful clone detection of basic Type 2 clones
     * TypeTwoBasicExample.java contains 6 methods, 5 of which are Type 2 clones of each other.
     * testCode is the base code, and is copied exactly in TypeTwoBasicExample.java's method sumProd()
     * Expected outcome is result contains 5 Clone objects
     * These Clones should be the methods sumProd, duplicate2a, duplicate2b, duplicate2c, and duplicate2d
     * @throws IOException on failure to open project
     * @throws JDOMException on failure to open project
     */
    public void testResolveMethodType2() throws IOException, JDOMException {
        String testCode = """
                            float sum=0.0; //C1
                            float prod =1.0;
                            for (int i=1; i<=n; i++)
                                {sum=sum + i;
                                prod = prod * i;
                                foo(sum, prod); }
                            };
                        """;
        String targetMethodName = "duplicate2c"; //chosen arbitrarily between the 5 clone methods.
        DuplicatesInspection testDuplicates = new DuplicatesInspection();

        ProjectManager myProjectManager = ProjectManager.getInstance();
        assert myProjectManager != null;

        Project myProject = myProjectManager.loadAndOpenProject("./anti-copy-paster");
        assert myProject != null;

        PsiManager psiManager = PsiManager.getInstance(myProject);

        VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath("./src/test/resources/testdata/TypeTwoBasicExample.java");
        assert virtualFile != null;

        PsiFile file = psiManager.findFile(virtualFile);
        assert file != null;

        //Obtains the PSI for targetMethodName
        //targetMethodName represents the method in which code was pasted
        final PsiMethod[] targetMethod = {null};
        file.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                if (method.getName().equals(targetMethodName)) {
                    targetMethod[0] = method;
                }
                super.visitMethod(method);
            }
        });

        //Invokes the AntiCopyPaster detection method
        DuplicatesInspection.InspectionResult result = testDuplicates.resolve(file, targetMethod[0], testCode);

        assertFalse(result.results().isEmpty());
        assertEquals(5, result.results().size());

        //Confirms that the detected methods are all the expected clones, in order they are listed in the file.
        //foo is correctly not detected
        String methodTextS = result.results().get(0).start().getParent().getParent().getText();
        String methodTextA = result.results().get(1).start().getParent().getParent().getText();
        String methodTextB = result.results().get(2).start().getParent().getParent().getText();
        String methodTextC = result.results().get(3).start().getParent().getParent().getText();
        String methodTextD = result.results().get(4).start().getParent().getParent().getText();

        assertEquals("""
                public void sumProd(int n) {
                        float sum=0.0; //C1
                        float prod =1.0;
                        for (int i=1; i<=n; i++)
                            {sum=sum + i;
                            prod = prod * i;
                            foo(sum, prod); }
                    }""", methodTextS);

        assertEquals("""
                public void duplicate2a(int n) {
                        float s=0.0; //C1
                        float p =1.0;
                        for (int j=1; j<=n; j++)
                            {s=s + j;
                            p = p * j;
                            foo(s, p); }
                    }""", methodTextA);

        assertEquals("""
                public void duplicate2b(int n) {
                        float s=0.0; //C1
                        float p =1.0;
                        for (int j=1; j<=n; j++)
                            {s=s + j;
                            p = p * j;
                            foo(p, s); }
                    }""", methodTextB);

        assertEquals("""
                public void duplicate2c(int n) {
                        int sum=0; //C1
                        int prod =1;
                        for (int i=1; i<=n; i++)
                            {sum=sum + i;
                            prod = prod * i;
                            foo(sum, prod); }
                    }""", methodTextC);

        assertEquals("""
                public void duplicate2d(int n) {
                        int sum=0; //C1
                        int prod =1;
                        for (int i=1; i<=n; i++)
                            {sum=sum + (i*i);
                            prod = prod * (i*i);
                            foo(sum, prod); }
                    }""", methodTextD);

    }

    /**
     * Does not detect any clones
     * Calculator.java does not contain testCode in any capacity
     * Expected outcome is result contains 1 Clone object which contains the method arbitrarily chosen as targetMethod
     * @throws IOException on failure to open project
     * @throws JDOMException on failure to open project
     */
    public void testResolveMethodNoClones() throws IOException, JDOMException {
        String testCode = """
                     float sum=0.0; //C1
                            float prod =1.0;
                            for (int i=1; i<=n; i++)
                                {sum=sum + i;
                                prod = prod * i;
                                foo(sum, prod); }
                            };
                """;
        String targetMethodName = "add"; //chosen arbitrarily. Any method in Calculator.java works.
        DuplicatesInspection testDuplicates = new DuplicatesInspection();

        ProjectManager myProjectManager = ProjectManager.getInstance();
        assert myProjectManager != null;

        Project myProject = myProjectManager.loadAndOpenProject("./anti-copy-paster");
        assert myProject != null;

        PsiManager psiManager = PsiManager.getInstance(myProject);

        VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath("./src/test/resources/testdata/NoCloneExample.java");
        assert virtualFile != null;

        PsiFile file = psiManager.findFile(virtualFile);
        assert file != null;

        //Obtains the PSI for targetMethodName
        //targetMethodName represents the method in which code was pasted
        final PsiMethod[] targetMethod = {null};
        file.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                if (method.getName().equals(targetMethodName)) {
                    targetMethod[0] = method;
                }
                super.visitMethod(method);
            }
        });

        //Invokes the AntiCopyPaster detection method
        DuplicatesInspection.InspectionResult result = testDuplicates.resolve(file, targetMethod[0], testCode);
        assertFalse(result.results().isEmpty());
        assertEquals(1, result.results().size());

        String methodText = result.results().get(0).start().getParent().getParent().getText();
        //Checks to make sure the only returned method is targetMethodName
        assertEquals("""
                public int add(int toAdd){
                        this.currentNumber += toAdd;
                        return this.currentNumber;
                    }""", methodText);
    }
}
