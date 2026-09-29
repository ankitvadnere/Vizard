package com.vizard.execution.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.vizard.api.dto.SourceProblem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static check that rejects programs using features outside Vizard's teaching subset
 * (files, network, processes, threads, reflection...).
 *
 * <p>This is a <b>first line of defence and a clear error message</b>, not the security
 * boundary. The real protection is the separate, resource-limited process in
 * {@code LocalProcessSandbox}; a container-based sandbox can replace it later.
 */
@Component
public class SafetyPolicy {

    /** Packages that may be imported. Nothing else is allowed. */
    private static final Set<String> ALLOWED_PACKAGES = Set.of(
            "java.util", "java.util.function", "java.util.stream", "java.math");

    /** Classes whose static members may be imported (import static ...). */
    private static final Set<String> ALLOWED_STATIC_IMPORT_CLASSES = Set.of(
            "java.lang.Math", "java.util.Arrays", "java.util.Collections");

    /** Type names that are never allowed, with the feature they represent. */
    private static final Map<String, String> FORBIDDEN_TYPES = Map.ofEntries(
            Map.entry("File", "File I/O"), Map.entry("Files", "File I/O"),
            Map.entry("Path", "File I/O"), Map.entry("Paths", "File I/O"),
            Map.entry("FileReader", "File I/O"), Map.entry("FileWriter", "File I/O"),
            Map.entry("FileInputStream", "File I/O"), Map.entry("FileOutputStream", "File I/O"),
            Map.entry("RandomAccessFile", "File I/O"), Map.entry("FileSystems", "File I/O"),
            Map.entry("Runtime", "Process control"), Map.entry("ProcessBuilder", "Process control"),
            Map.entry("Process", "Process control"), Map.entry("ProcessHandle", "Process control"),
            Map.entry("Thread", "Threads"), Map.entry("ThreadGroup", "Threads"),
            Map.entry("Timer", "Threads"), Map.entry("TimerTask", "Threads"),
            Map.entry("ClassLoader", "Reflection / class loading"),
            Map.entry("ServiceLoader", "Reflection / class loading"),
            Map.entry("ModuleLayer", "Reflection / class loading"),
            Map.entry("StackWalker", "Reflection / class loading"),
            Map.entry("SecurityManager", "Security manager"),
            Map.entry("Socket", "Network access"), Map.entry("ServerSocket", "Network access"),
            Map.entry("URL", "Network access"), Map.entry("URI", "Network access"),
            Map.entry("HttpClient", "Network access"), Map.entry("InetAddress", "Network access"));

    /** Methods that exist only for reflection / dynamic loading. */
    private static final Set<String> REFLECTION_METHODS = Set.of(
            "forName", "getClassLoader", "getDeclaredMethod", "getDeclaredMethods",
            "getDeclaredField", "getDeclaredFields", "getDeclaredConstructor",
            "getDeclaredConstructors", "getMethod", "getMethods", "getField", "getFields",
            "getConstructor", "getConstructors", "setAccessible", "newInstance", "loadLibrary");

    /** System.xxx(...) calls that are not allowed. System.out / System.in stay available. */
    private static final Set<String> FORBIDDEN_SYSTEM_METHODS = Set.of(
            "exit", "getenv", "getProperty", "getProperties", "setProperty", "setProperties",
            "clearProperty", "setIn", "setOut", "setErr", "load", "loadLibrary", "console",
            "inheritedChannel", "setSecurityManager", "getLogger");

    /** Roots of fully-qualified names like java.io.File, which would bypass import checks. */
    private static final Set<String> QUALIFIED_ROOTS = Set.of("java", "javax", "jdk", "sun", "com", "org");

    public List<SourceProblem> check(CompilationUnit cu) {
        // Keyed by line+message so one bad token reported by several node types appears once.
        Map<String, SourceProblem> found = new LinkedHashMap<>();

        for (ImportDeclaration imp : cu.getImports()) {
            if (!isAllowedImport(imp)) {
                add(found, imp, "Import '" + imp.getNameAsString() + (imp.isAsterisk() ? ".*" : "")
                        + "' is not supported. Allowed: java.util, java.util.function, java.util.stream, java.math.");
            }
        }

        for (ClassOrInterfaceType type : cu.findAll(ClassOrInterfaceType.class)) {
            checkName(found, type, type.getNameAsString());
            String root = type.getNameWithScope().split("\\.")[0];
            if (type.getScope().isPresent() && QUALIFIED_ROOTS.contains(root)) {
                add(found, type, "Fully-qualified name '" + type.getNameWithScope()
                        + "' is not supported. Use an import instead.");
            }
        }

        for (NameExpr name : cu.findAll(NameExpr.class)) {
            checkName(found, name, name.getNameAsString());
        }

        for (FieldAccessExpr access : cu.findAll(FieldAccessExpr.class)) {
            checkName(found, access, access.getNameAsString());
            Expression root = access;
            while (root instanceof FieldAccessExpr fa) {
                root = fa.getScope();
            }
            if (root instanceof NameExpr n && QUALIFIED_ROOTS.contains(n.getNameAsString())) {
                add(found, access, "Fully-qualified name '" + access + "' is not supported. Use an import instead.");
            }
        }

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String method = call.getNameAsString();
            if (REFLECTION_METHODS.contains(method)) {
                add(found, call, "Detected: Reflection. '" + method + "(...)' is not supported by Vizard.");
            }
            boolean onSystem = call.getScope().map(s -> s.toString().equals("System")).orElse(false);
            if (onSystem && FORBIDDEN_SYSTEM_METHODS.contains(method)) {
                add(found, call, "Detected: System access. 'System." + method + "(...)' is not supported by Vizard.");
            }
        }

        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            if (m.hasModifier(Modifier.Keyword.NATIVE)) {
                add(found, m, "Detected: Native code. 'native' methods are not supported by Vizard.");
            }
        }

        return new ArrayList<>(found.values());
    }

    private static boolean isAllowedImport(ImportDeclaration imp) {
        String name = imp.getNameAsString();
        if (imp.isStatic()) {
            String owner = imp.isAsterisk() ? name : name.substring(0, name.lastIndexOf('.'));
            return ALLOWED_STATIC_IMPORT_CLASSES.contains(owner);
        }
        if (imp.isAsterisk()) {
            return ALLOWED_PACKAGES.contains(name);
        }
        // "java.util.Scanner" or nested "java.util.Map.Entry": the part after the package
        // must start with a class name (uppercase), which rules out sub-packages like java.util.concurrent.
        for (String pkg : ALLOWED_PACKAGES) {
            if (name.startsWith(pkg + ".") && Character.isUpperCase(name.charAt(pkg.length() + 1))) {
                return true;
            }
        }
        return false;
    }

    private static void checkName(Map<String, SourceProblem> found, Node node, String name) {
        String feature = FORBIDDEN_TYPES.get(name);
        if (feature != null) {
            add(found, node, "Detected: " + feature + ". '" + name + "' is not supported by Vizard.");
        }
    }

    private static void add(Map<String, SourceProblem> found, Node node, String message) {
        int line = node.getBegin().map(p -> p.line).orElse(0);
        int column = node.getBegin().map(p -> p.column).orElse(0);
        found.putIfAbsent(line + "|" + message, SourceProblem.error(line, column, message));
    }
}
