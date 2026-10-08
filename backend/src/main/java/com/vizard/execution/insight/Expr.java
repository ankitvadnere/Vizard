package com.vizard.execution.insight;

/**
 * A side-effect-free view of a Java expression, built from the JavaParser AST.
 * Anything Vizard can't evaluate safely (method calls, assignments, i++) becomes {@link Opaque}.
 * Keeping this separate from JavaParser lets the evaluator and annotator be tested on their own.
 */
public sealed interface Expr {

    /** The expression as written in the source. */
    String text();

    record Literal(Object value, String text) implements Expr {
    }

    record Name(String name) implements Expr {
        @Override
        public String text() {
            return name;
        }
    }

    record ArrayAccess(Expr array, Expr index, String text) implements Expr {
    }

    record FieldAccess(Expr target, String field, String text) implements Expr {
    }

    record Binary(String operator, Expr left, Expr right, String text) implements Expr {
    }

    record Unary(String operator, Expr operand, String text) implements Expr {
    }

    record Paren(Expr inner, String text) implements Expr {
    }

    record Conditional(Expr condition, Expr whenTrue, Expr whenFalse, String text) implements Expr {
    }

    record Cast(String type, Expr operand, String text) implements Expr {
    }

    record Opaque(String text) implements Expr {
    }
}
