package io.github.maxdac.marknotes.ui.editor;

import io.noties.prism4j.GrammarLocator;
import io.noties.prism4j.annotations.PrismBundle;

/**
 * Prism4j grammars used for syntax highlighting fenced code blocks in the preview. The bundler is a javac
 * annotation processor, so this lives in Java and exposes the generated locator to Kotlin via {@link #create()}.
 */
@PrismBundle(
        include = {
            "c", "clike", "clojure", "cpp", "csharp", "css", "dart", "git", "go", "groovy", "java",
            "javascript", "json", "kotlin", "latex", "makefile", "markdown", "markup", "python", "scala",
            "sql", "swift", "yaml"
        },
        grammarLocatorClassName = ".CodeGrammarLocator"
)
public final class CodeGrammars {
    private CodeGrammars() {
    }

    public static GrammarLocator create() {
        return new CodeGrammarLocator();
    }
}
