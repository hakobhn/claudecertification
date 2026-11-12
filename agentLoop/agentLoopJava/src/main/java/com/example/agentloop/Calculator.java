package com.example.agentloop;

/**
 * Safe arithmetic {@link ExpressionEvaluator} for the {@code calculator} tool (no script engine, no eval).
 *
 * <p>Grammar (recursive descent, standard precedence):
 * <pre>
 *   expr   := term   (('+' | '-') term)*
 *   term   := unary  (('*' | '/' | '//' | '%') unary)*
 *   unary  := ('+' | '-') unary | power
 *   power  := atom ('**' unary)?          // right-associative, binds tighter than unary minus on the left
 *   atom   := number | '(' expr ')'
 * </pre>
 */
public final class Calculator implements ExpressionEvaluator {

    @Override
    public double evaluate(String expression) {
        Parser p = new Parser(expression);
        double value = p.expr();
        p.skipSpaces();
        if (p.pos != p.src.length()) {
            throw new IllegalArgumentException("Unexpected character '" + p.src.charAt(p.pos) + "' at position " + p.pos);
        }
        return value;
    }

    /** One parse of one expression: holds the input and the read position. */
    private static final class Parser {

        private final String src;
        private int pos;

        private Parser(String src) {
            this.src = src;
        }

        private double expr() {
            double value = term();
            while (true) {
                if (eat("+")) value += term();
                else if (eat("-")) value -= term();
                else return value;
            }
        }

        private double term() {
            double value = unary();
            while (true) {
                if (peek("**")) return value;                 // handled in power()
                if (eat("//")) value = Math.floor(value / nonZero(unary()));
                else if (eat("*")) value *= unary();
                else if (eat("/")) value /= nonZero(unary());
                else if (eat("%")) value %= nonZero(unary());
                else return value;
            }
        }

        private double unary() {
            if (eat("+")) return unary();
            if (eat("-")) return -unary();
            return power();
        }

        private double power() {
            double base = atom();
            if (eat("**")) return Math.pow(base, unary());
            return base;
        }

        private double atom() {
            skipSpaces();
            if (eat("(")) {
                double value = expr();
                if (!eat(")")) throw new IllegalArgumentException("Missing ')' at position " + pos);
                return value;
            }
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.' || src.charAt(pos) == '_')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException(pos < src.length()
                        ? "Unexpected character '" + src.charAt(pos) + "' at position " + pos
                        : "Unexpected end of expression");
            }
            return Double.parseDouble(src.substring(start, pos).replace("_", ""));
        }

        private static double nonZero(double d) {
            if (d == 0) throw new ArithmeticException("division by zero");
            return d;
        }

        private boolean peek(String token) {
            skipSpaces();
            return src.startsWith(token, pos);
        }

        private boolean eat(String token) {
            if (peek(token)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        private void skipSpaces() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }
    }
}
