package com.vizard.execution.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.Type;
import com.vizard.api.dto.SourceProblem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the user's source with JavaParser, finds the class to run, and applies
 * the {@link SafetyPolicy}. Later milestones use this AST to explain what a line does
 * (comparisons, swaps, loop conditions).
 */
@Component
public class SourceAnalyzer {

    private static final Pattern PUBLIC_CLASS =
            Pattern.compile("public\\s+(?:final\\s+|abstract\\s+)*class\\s+([A-Za-z_$][\\w$]*)");

    private final SafetyPolicy safetyPolicy;

    public SourceAnalyzer(SafetyPolicy safetyPolicy) {
        this.safetyPolicy = safetyPolicy;
    }

    public SourceAnalysis analyze(String source) {
        // JavaParser instances are not thread-safe, so create one per request (cheap).
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        ParseResult<CompilationUnit> result = parser.parse(source);

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            // Still guess the file name so javac can produce its (friendlier) error messages.
            return new SourceAnalysis(null, toProblems(result.getProblems()),
                    guessPublicClassName(source), null, List.of(), "", List.of());
        }

        CompilationUnit cu = result.getResult().get();
        String packageName = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString())
                .orElse("");
        String packagePrefix = packageName.isEmpty() ? "" : packageName + ".";
        List<String> topLevelClasses = new ArrayList<>();

        String publicClass = null;
        String mainClass = null;
        for (TypeDeclaration<?> type : cu.getTypes()) {
            topLevelClasses.add(packagePrefix + type.getNameAsString());
            if (type.isPublic() && publicClass == null) {
                publicClass = type.getNameAsString();
            }
            if (mainClass == null && hasMainMethod(type)) {
                mainClass = type.getNameAsString();
            }
        }

        String fileClass = publicClass != null ? publicClass
                : mainClass != null ? mainClass
                : "Main";
        String mainFqn = mainClass == null ? null : packagePrefix + mainClass;

        return new SourceAnalysis(cu, List.of(), fileClass, mainFqn, safetyPolicy.check(cu),
                packageName, List.copyOf(topLevelClasses));
    }

    private static boolean hasMainMethod(TypeDeclaration<?> type) {
        for (MethodDeclaration m : type.getMethodsByName("main")) {
            if (!m.isStatic() || !m.getType().isVoidType() || m.getParameters().size() != 1) {
                continue;
            }
            Parameter p = m.getParameter(0);
            Type t = p.getType();
            boolean stringArray = t.isArrayType()
                    && isString(t.asArrayType().getComponentType());
            boolean stringVarArgs = p.isVarArgs() && isString(t);
            if (stringArray || stringVarArgs) {
                return true;
            }
        }
        return false;
    }

    private static boolean isString(Type t) {
        String s = t.asString();
        return s.equals("String") || s.equals("java.lang.String");
    }

    private static List<SourceProblem> toProblems(List<Problem> problems) {
        List<SourceProblem> out = new ArrayList<>();
        for (Problem p : problems) {
            int line = 0;
            int column = 0;
            if (p.getLocation().isPresent()) {
                var range = p.getLocation().get().getBegin().getRange();
                if (range.isPresent()) {
                    line = range.get().begin.line;
                    column = range.get().begin.column;
                }
            }
            out.add(SourceProblem.error(line, column, p.getVerboseMessage()));
        }
        return out;
    }

    static String guessPublicClassName(String source) {
        Matcher m = PUBLIC_CLASS.matcher(source);
        return m.find() ? m.group(1) : "Main";
    }
}
