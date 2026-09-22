package dev.compatfoundry;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Runs the in-process JVM scanner; findings warn unless explicitly gated. */
@Mojo(name = "scan", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public final class ScanMojo extends AbstractMojo {
    /** Project or supplied effective production POM. */
    @Parameter(property = "compat.pom", defaultValue = "${project.file}", required = true)
    private File pom;
    /** Production source and resource root. Missing source stays unknown. */
    @Parameter(property = "compat.source", defaultValue = "${project.basedir}/src/main")
    private File source;
    /** Per-module report directory. */
    @Parameter(property = "compat.outputDirectory", defaultValue = "${project.build.directory}/compat-foundry", required = true)
    private File outputDirectory;
    /** Target Boot version within the catalog's reviewed boundary. */
    @Parameter(property = "compat.toVersion", defaultValue = "3.0.13")
    private String toVersion;
    /** Fail verify when potential risks are present. */
    @Parameter(property = "compat.failOnFindings", defaultValue = "false")
    private boolean failOnFindings;
    /** Fail verify when scanning cannot complete. Independent of findings. */
    @Parameter(property = "compat.failOnError", defaultValue = "false")
    private boolean failOnError;
    /** Cooperative deadline checked between bounded files and rule evaluations. */
    @Parameter(property = "compat.timeoutSeconds", defaultValue = "60")
    private int timeoutSeconds;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path output = outputDirectory.toPath().toAbsolutePath();
        boolean findings;
        try {
            ObjectNode report = new JvmScanner(timeoutSeconds).scan(pom.toPath().toAbsolutePath(),
                source != null && source.exists() ? source.toPath().toAbsolutePath() : null, toVersion);
            String text = ScanReport.write(output, report);
            findings = !report.path("findings").isEmpty();
            if (findings) getLog().warn(text); else getLog().info(text);
            getLog().info("JSON report: " + output.resolve("report.json"));
        } catch (Exception error) {
            recordError(output);
            if (failOnError) throw new MojoExecutionException("Compat Foundry scan unavailable", error);
            getLog().warn("Compat Foundry scan unavailable; hit count: unknown. " + error.getMessage());
            return;
        }
        if (findings && failOnFindings)
            throw new MojoFailureException("Compat Foundry found potential behavior drift; see " + output.resolve("report.json"));
    }

    private void recordError(Path output) {
        try {
            Files.createDirectories(output);
            Files.writeString(output.resolve("report.json"),
                "{\"schema_version\":\"1\",\"status\":\"error\",\"summary\":{\"hit_count\":\"unknown\"},"
                + "\"error\":\"Scanner unavailable; see Maven output\"}\n");
            Files.writeString(output.resolve("report.txt"), "Compat Foundry scan unavailable; hit count: unknown\n");
            Files.writeString(output.resolve("repair-request.md"), "Scan unavailable; repair scope: unknown. No request sent.\n");
        } catch (IOException error) { getLog().warn("Could not write error report: " + error.getMessage()); }
    }
}
