package dev.compat.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the small, constructed Spring Boot migration example. */
@SpringBootApplication
public class DemoApplication {
    /** Launches the application with the caller's configuration arguments. */
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
