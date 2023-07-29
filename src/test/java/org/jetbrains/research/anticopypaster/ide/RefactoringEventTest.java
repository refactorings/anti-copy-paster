package org.jetbrains.research.anticopypaster.ide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RefactoringEventTest {

    private RefactoringEvent refactoringEvent;

    @BeforeEach
    public void beforeTest() {
        refactoringEvent = new RefactoringEvent(null, null, null, 0, null, null, 0);
    }

    @Test
    public void testPrintMessage() {
        System.out.println("Message = Test 1");
        assertTrue(true);
    }

    @Test
    public void testPrintMessage2() {
        System.out.println("Message2 = Test 2");
        assertTrue(true);
    }

    @Test
    public void testNullRefactorEvent() {
        //Baseline "null" test to see that our testing folder can access src files
        assertNull(refactoringEvent.getFile());
    }
}
