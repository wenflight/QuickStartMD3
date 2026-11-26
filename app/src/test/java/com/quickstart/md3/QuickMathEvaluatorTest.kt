package com.quickstart.md3

import com.quickstart.md3.data.QuickMathEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickMathEvaluatorTest {

    @Test
    fun evaluatesBasicAndPrecedenceExpressions() {
        assertEquals("7", QuickMathEvaluator.evaluate("1+2*3"))
        assertEquals("9", QuickMathEvaluator.evaluate("(1+2)×3"))
        assertEquals("2.5", QuickMathEvaluator.evaluate("5÷2"))
        assertEquals("-4", QuickMathEvaluator.evaluate("-6+2"))
        assertEquals("14", QuickMathEvaluator.evaluate(" 10 + 8 / 2 "))
    }

    @Test
    fun rejectsPlainNumbersLettersAndInvalidSyntax() {
        assertNull(QuickMathEvaluator.evaluate("1234"))
        assertNull(QuickMathEvaluator.evaluate("微信"))
        assertNull(QuickMathEvaluator.evaluate("1+"))
        assertNull(QuickMathEvaluator.evaluate("(1+2"))
        assertNull(QuickMathEvaluator.evaluate("5/0"))
    }
}
