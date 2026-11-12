package com.example.agentloop;

/** Evaluates arithmetic expressions for the {@code calculator} tool. */
public interface ExpressionEvaluator {

    /**
     * Evaluates an expression such as {@code (8849 - 6190) * 2}.
     *
     * @param expression arithmetic using {@code + - * / // % **} and parentheses
     * @return the numeric value
     * @throws IllegalArgumentException if the expression is malformed
     * @throws ArithmeticException      on division by zero
     */
    double evaluate(String expression);
}
