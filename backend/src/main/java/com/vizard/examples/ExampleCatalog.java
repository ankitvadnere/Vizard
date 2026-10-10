package com.vizard.examples;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vizard.api.dto.Example;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The example programs, loaded once from {@code resources/examples/}: {@code index.json} lists
 * them and each program is a real {@code <id>.java} file. Keeping them in the backend means the
 * tests can compile and trace exactly the programs the UI offers.
 */
@Component
public class ExampleCatalog {

    private static final String FOLDER = "examples/";

    private record Entry(String id, String title, String group, String stdin, List<String> algorithms) {
    }

    private final List<Example> examples;

    public ExampleCatalog() {
        this.examples = load();
    }

    public List<Example> all() {
        return examples;
    }

    private static List<Example> load() {
        List<Entry> entries;
        try (InputStream index = resource("index.json")) {
            entries = new ObjectMapper().readValue(index, new TypeReference<List<Entry>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read examples/index.json", e);
        }
        List<Example> result = new ArrayList<>();
        for (Entry e : entries) {
            try (InputStream code = resource(e.id() + ".java")) {
                String source = new String(code.readAllBytes(), StandardCharsets.UTF_8);
                result.add(new Example(e.id(), e.title(), e.group(), source, e.stdin(),
                        e.algorithms() == null ? List.of() : List.copyOf(e.algorithms())));
            } catch (IOException ex) {
                throw new UncheckedIOException("Could not read example " + e.id(), ex);
            }
        }
        return List.copyOf(result);
    }

    private static InputStream resource(String name) throws IOException {
        InputStream in = ExampleCatalog.class.getClassLoader().getResourceAsStream(FOLDER + name);
        if (in == null) {
            throw new IOException("Missing resource " + FOLDER + name);
        }
        return in;
    }
}
