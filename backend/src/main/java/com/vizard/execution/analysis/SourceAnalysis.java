package com.vizard.execution.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.vizard.api.dto.SourceProblem;

import java.util.List;

/**
 * What Vizard learned about the source before compiling it.
 *
 * @param compilationUnit the AST, or null if parsing failed
 * @param parseProblems   parser errors (empty when parsing succeeded)
 * @param fileClassName   name the .java file must have (the public top-level class, else the main class)
 * @param mainClassName   fully-qualified class that declares main(String[]), or null if none was found
 * @param violations      uses of features Vizard does not allow
 * @param packageName     declared package, or "" for none
 * @param topLevelClasses fully-qualified names of all top-level types in the file
 */
public record SourceAnalysis(
        CompilationUnit compilationUnit,
        List<SourceProblem> parseProblems,
        String fileClassName,
        String mainClassName,
        List<SourceProblem> violations,
        String packageName,
        List<String> topLevelClasses
) {

    public boolean parsed() {
        return compilationUnit != null;
    }
}
