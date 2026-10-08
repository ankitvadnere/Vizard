package com.vizard.api;

import com.vizard.api.dto.Example;
import com.vizard.examples.ExampleCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ExampleController {

    private final ExampleCatalog catalog;

    public ExampleController(ExampleCatalog catalog) {
        this.catalog = catalog;
    }

    /** Example programs for the editor's Example menu. */
    @GetMapping("/examples")
    public List<Example> examples() {
        return catalog.all();
    }
}
