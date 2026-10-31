package com.agentflow.tool;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CalculatorToolTest {
    private final CalculatorTool calculator = new CalculatorTool();

    @Test void evaluatesArithmeticWithPrecedenceAndParentheses() {
        assertEquals(14.0, calculator.calculate("2 + 3 * 4"));
        assertEquals(20.0, calculator.calculate("(2 + 3) * 4"));
        assertEquals(-2.0, calculator.calculate("1 - 3"));
    }

    @Test void rejectsExpressionsThatCouldEscapeTheArithmeticGrammar() {
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate("1 / 0"));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate("1; Runtime.exec()"));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate("(1 + 2"));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate("9".repeat(201)));
    }
}
