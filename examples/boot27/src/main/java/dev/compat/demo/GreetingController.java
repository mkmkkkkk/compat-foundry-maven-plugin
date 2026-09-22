package dev.compat.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes one legacy route and one route using the catalog's explicit-pair fix. */
@RestController
public class GreetingController {
    /** Returns a stable body from the route whose implicit slash behavior changes. */
    @GetMapping("/api/greeting")
    public String greeting() {
        return "hello";
    }

    /** Demonstrates the exact explicit-route compatibility technique in the catalog. */
    @GetMapping({"/api/paired", "/api/paired/"})
    public String paired() {
        return "paired";
    }
}
