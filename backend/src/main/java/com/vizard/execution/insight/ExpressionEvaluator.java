package com.vizard.execution.insight;

import com.vizard.api.dto.trace.HeapObjectSnapshot;
import com.vizard.api.dto.trace.ValueSnapshot;
import com.vizard.api.dto.trace.VariableSnapshot;

import java.util.Set;

/**
 * Evaluates side-effect-free expressions against a recorded step, e.g. {@code arr[j + 1]} → 2.
 * It never runs user code: it only reads values the debugger already captured.
 *
 * <p>Values are Long (all integral types), Double, Boolean, Character, String, {@link HeapRef}
 * or {@link #NULL}. A Java {@code null} return means "unknown" (opaque expression, missing
 * variable, index out of range, division by zero...).
 */
public final class ExpressionEvaluator {

    /** A reference to an array or object in the step's heap. */
    public record HeapRef(long id) {
    }

    /** The Java value null (distinct from "unknown"). */
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    private static final Set<String> COMPARISONS = Set.of("<", ">", "<=", ">=", "==", "!=");

    private ExpressionEvaluator() {
    }

    public static Object evaluate(Expr expr, EvalContext ctx) {
        try {
            return eval(expr, ctx);
        } catch (ArithmeticException | ClassCastException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    /** The condition with every comparison's operands replaced by their values: "arr[j] > arr[j + 1]" → "5 > 2". */
    public static String explain(Expr expr, EvalContext ctx) {
        if (expr instanceof Expr.Binary b && COMPARISONS.contains(b.operator())) {
            return valueOrText(b.left(), ctx) + " " + b.operator() + " " + valueOrText(b.right(), ctx);
        }
        if (expr instanceof Expr.Binary b && (b.operator().equals("&&") || b.operator().equals("||"))) {
            return explain(b.left(), ctx) + " " + b.operator() + " " + explain(b.right(), ctx);
        }
        if (expr instanceof Expr.Unary u && u.operator().equals("!")) {
            return "!" + explain(u.operand(), ctx);
        }
        if (expr instanceof Expr.Paren p) {
            return "(" + explain(p.inner(), ctx) + ")";
        }
        return valueOrText(expr, ctx);
    }

    /** Java-style text for a value: 5, 2.5, 'a', "hi", null, int[]. */
    public static String display(Object value, EvalContext ctx) {
        if (value == NULL) return "null";
        if (value instanceof Character c) return "'" + c + "'";
        if (value instanceof String s) return "\"" + s + "\"";
        if (value instanceof HeapRef ref) {
            HeapObjectSnapshot object = ctx.heapObject(ref.id());
            return object == null ? "object" : object.type();
        }
        return String.valueOf(value);
    }

    private static String valueOrText(Expr expr, EvalContext ctx) {
        if (expr instanceof Expr.Literal) {
            return expr.text();
        }
        Object value = evaluate(expr, ctx);
        return value == null ? expr.text() : display(value, ctx);
    }

    // ---------------------------------------------------------------------------------------

    private static Object eval(Expr expr, EvalContext ctx) {
        return switch (expr) {
            case Expr.Literal l -> l.value();
            case Expr.Name n -> n.name().equals("this") ? thisRef(ctx) : fromSnapshot(ctx.lookup(n.name()));
            case Expr.Paren p -> eval(p.inner(), ctx);
            case Expr.ArrayAccess a -> arrayElement(a, ctx);
            case Expr.FieldAccess f -> field(f, ctx);
            case Expr.Unary u -> unary(u.operator(), eval(u.operand(), ctx));
            case Expr.Binary b -> binary(b, ctx);
            case Expr.Conditional c -> {
                Object test = eval(c.condition(), ctx);
                yield test instanceof Boolean t ? eval(t ? c.whenTrue() : c.whenFalse(), ctx) : null;
            }
            case Expr.Cast c -> cast(c.type(), eval(c.operand(), ctx));
            case Expr.Opaque o -> null;
        };
    }

    private static Object thisRef(EvalContext ctx) {
        return fromSnapshot(ctx.lookup("this"));
    }

    static Object fromSnapshot(ValueSnapshot v) {
        if (v == null) {
            return null;
        }
        return switch (v.kind()) {
            case "null" -> NULL;
            case "string" -> v.value();
            case "ref" -> new HeapRef(v.ref());
            case "primitive" -> switch (v.type()) {
                case "char", "Character" -> v.value() instanceof String s && s.length() == 1 ? s.charAt(0) : null;
                case "boolean", "Boolean" -> v.value();
                case "double", "float", "Double", "Float" -> v.value() instanceof Number n ? n.doubleValue() : null;
                default -> v.value() instanceof Number n ? n.longValue() : null;
            };
            default -> null;
        };
    }

    private static Object arrayElement(Expr.ArrayAccess access, EvalContext ctx) {
        Object array = eval(access.array(), ctx);
        Object index = eval(access.index(), ctx);
        if (!(array instanceof HeapRef ref) || !(asLong(index) instanceof Long i)) {
            return null;
        }
        HeapObjectSnapshot object = ctx.heapObject(ref.id());
        if (object == null || !"array".equals(object.kind()) || i < 0 || i >= object.elements().size()) {
            return null;
        }
        return fromSnapshot(object.elements().get(i.intValue()));
    }

    private static Object field(Expr.FieldAccess access, EvalContext ctx) {
        // ClassName.staticField
        if (access.target() instanceof Expr.Name n && ctx.lookup(n.name()) == null) {
            return fromSnapshot(ctx.lookupStatic(n.name(), access.field()));
        }
        Object target = eval(access.target(), ctx);
        if (!(target instanceof HeapRef ref)) {
            return null;
        }
        HeapObjectSnapshot object = ctx.heapObject(ref.id());
        if (object == null) {
            return null;
        }
        if ("array".equals(object.kind())) {
            return access.field().equals("length") ? (Object) (long) object.length() : null;
        }
        for (VariableSnapshot f : object.fields()) {
            if (f.name().equals(access.field())) {
                return fromSnapshot(f.value());
            }
        }
        return null;
    }

    private static Object unary(String operator, Object value) {
        if (value == null) {
            return null;
        }
        return switch (operator) {
            case "!" -> value instanceof Boolean b ? !b : null;
            case "-" -> value instanceof Double d ? (Object) (-d) : negate(asLong(value));
            case "+" -> value instanceof Double ? value : asLong(value);
            case "~" -> asLong(value) instanceof Long l ? ~l : null;
            default -> null;
        };
    }

    private static Object negate(Long value) {
        return value == null ? null : -value;
    }

    private static Object binary(Expr.Binary b, EvalContext ctx) {
        String op = b.operator();
        // Short-circuit exactly like Java, so "j >= 0 && arr[j] > key" never reads arr[-1].
        if (op.equals("&&") || op.equals("||")) {
            Object left = eval(b.left(), ctx);
            if (!(left instanceof Boolean l)) {
                return null;
            }
            if (op.equals("&&") && !l) return false;
            if (op.equals("||") && l) return true;
            Object right = eval(b.right(), ctx);
            return right instanceof Boolean r ? r : null;
        }

        Object left = eval(b.left(), ctx);
        Object right = eval(b.right(), ctx);
        if (left == null || right == null) {
            return null;
        }

        if (op.equals("+") && (left instanceof String || right instanceof String)) {
            return text(left) + text(right);
        }
        if (op.equals("==") || op.equals("!=")) {
            Boolean equal = equalValues(left, right);
            return equal == null ? null : op.equals("==") == equal;
        }
        if (left instanceof Boolean l && right instanceof Boolean r) {
            return switch (op) {
                case "&" -> l & r;
                case "|" -> l | r;
                case "^" -> l ^ r;
                default -> null;
            };
        }

        boolean floating = left instanceof Double || right instanceof Double;
        if (floating) {
            Double l = asDouble(left);
            Double r = asDouble(right);
            if (l == null || r == null) return null;
            return switch (op) {
                case "+" -> l + r;
                case "-" -> l - r;
                case "*" -> l * r;
                case "/" -> l / r;
                case "%" -> l % r;
                case "<" -> l < r;
                case ">" -> l > r;
                case "<=" -> l <= r;
                case ">=" -> l >= r;
                default -> null;
            };
        }

        Long l = asLong(left);
        Long r = asLong(right);
        if (l == null || r == null) return null;
        return switch (op) {
            case "+" -> l + r;
            case "-" -> l - r;
            case "*" -> l * r;
            case "/" -> l / r;   // throws ArithmeticException on /0 → unknown
            case "%" -> l % r;
            case "<" -> l < r;
            case ">" -> l > r;
            case "<=" -> l <= r;
            case ">=" -> l >= r;
            case "&" -> l & r;
            case "|" -> l | r;
            case "^" -> l ^ r;
            default -> null;     // shifts depend on int vs long width; not worth guessing
        };
    }

    private static Boolean equalValues(Object left, Object right) {
        if (left == NULL || right == NULL) {
            return left == right;
        }
        if (left instanceof HeapRef || right instanceof HeapRef) {
            return left.equals(right);
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            return left.equals(right);
        }
        if (left instanceof String || right instanceof String) {
            return null; // == on strings compares identity, which we can't know
        }
        if (left instanceof Double || right instanceof Double) {
            Double l = asDouble(left);
            Double r = asDouble(right);
            return l == null || r == null ? null : l.doubleValue() == r.doubleValue();
        }
        Long l = asLong(left);
        Long r = asLong(right);
        return l == null || r == null ? null : l.longValue() == r.longValue();
    }

    private static Object cast(String type, Object value) {
        if (value == null) {
            return null;
        }
        return switch (type) {
            case "int", "long", "short", "byte" -> value instanceof Double d ? (Object) (long) d.doubleValue() : asLong(value);
            case "double", "float" -> asDouble(value);
            case "char" -> asLong(value) instanceof Long l ? (Object) (char) l.longValue() : null;
            default -> value;
        };
    }

    /** Integral value, with char promoted to its code like Java does. */
    private static Long asLong(Object value) {
        if (value instanceof Long l) return l;
        if (value instanceof Character c) return (long) c;
        return null;
    }

    private static Double asDouble(Object value) {
        if (value instanceof Double d) return d;
        Long l = asLong(value);
        return l == null ? null : l.doubleValue();
    }

    private static String text(Object value) {
        if (value == NULL) return "null";
        return String.valueOf(value);
    }
}
