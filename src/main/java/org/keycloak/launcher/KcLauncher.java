package org.keycloak.launcher;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Command(name = "kc", mixinStandardHelpOptions = true)
public class KcLauncher implements Runnable {

    @CommandLine.Option(names = {"debug"}, description = "Use this debug port.")
    Optional<Integer> debugPort;

    @CommandLine.Unmatched
    List<String> args;

    // @CommandLine.Spec CommandLine.Model.CommandSpec spec;

    public static File getExecutableLocation() {
        return new File(KcLauncher.class.getProtectionDomain().getCodeSource().getLocation().getPath());
    }

    @Override
    public void run() {
        File resolvedFile = getExecutableLocation();
        File kcDir = resolvedFile.getParentFile().getParentFile();

        List<String> serverOpts = new ArrayList<>();
        serverOpts.add("-Dkc.home.dir=" + kcDir.getAbsolutePath());
        serverOpts.add("-Djboss.server.config.dir=" + kcDir.toPath().resolve("conf").toFile().getAbsolutePath());
        serverOpts.add("-Djava.util.logging.manager=org.jboss.logmanager.LogManager");
        serverOpts.add("-Dquarkus-log-max-startup-records=10000");

        boolean debugMode = Optional.ofNullable(System.getenv("DEBUG")).map(Boolean::parseBoolean).orElse(false);
        debugMode = debugMode ? debugMode : this.debugPort.isPresent();

        int debugPort = Optional.ofNullable(System.getenv("DEBUG_PORT")).map(Integer::parseInt).orElse(8787);
        String debugSuspend = Optional.ofNullable(System.getenv("DEBUG_SUSPEND")).orElse("n");

        List<String> configArgs = new ArrayList<>();
        String[] originalConfigArgs = Optional.ofNullable(System.getenv("CONFIG_ARGS")).map(a -> a.split(" ")).orElse(new String[]{});
        for (String a: originalConfigArgs) {
            configArgs.add(a);
        }

        if (args == null) {
            args = new ArrayList<>();
        }
        for (String arg: args) {
            if (arg.equals("start-dev")) {
                configArgs.add("--profile=dev");
                configArgs.add("start-dev");
                configArgs.add("--auto-build");
            } else {
                serverOpts.add(arg);
            }
        }

        String javaCommand = Optional.ofNullable(System.getenv("JAVA")).or(() ->
                Optional.ofNullable(System.getenv("JAVA_HOME")).map(jh -> Path.of(jh, "bin", "java").toFile().getAbsolutePath())
        ).orElse("java");

        Optional<String> optJavaOpts = Optional.ofNullable(System.getenv("JAVA_OPTS"));
        optJavaOpts.ifPresent(javaOpts -> {
            System.out.println("JAVA_OPTS already set in environment; overriding default settings with values: " + javaOpts);
        });
        String javaOptions = optJavaOpts.orElse(
                "-Xms64m -Xmx512m -XX:MetaspaceSize=96M -XX:MaxMetaspaceSize=256m -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8"
        );

        Optional<String> optJavaOptsAppend = Optional.ofNullable(System.getenv("JAVA_OPTS_APPEND"));
        if (optJavaOptsAppend.isPresent()) {
            javaOptions = javaOptions + " " + optJavaOptsAppend.get();
        }

        // refactor me later on
        // # Set debug settings if not already set
        if (debugMode) {
            if (!javaOptions.contains("-agentlib:jdwp")) {
                javaOptions = javaOptions + "-agentlib:jdwp=transport=dt_socket,address=" + debugPort + ",server=y,suspend=" + debugSuspend;
            } else {
               System.out.println("Debug already enabled in JAVA_OPTS, ignoring --debug argument");
            }
        }


        String classPathOpts = Path.of(kcDir.getAbsolutePath(), "lib", "quarkus-run.jar").toFile().getAbsolutePath();
        String configArgsStr = configArgs.stream().collect(Collectors.joining(" "));

        String javaRunOpts =
                javaOptions + " " + serverOpts.stream().collect(Collectors.joining(" ")) + " -cp " + classPathOpts +
                        " io.quarkus.bootstrap.runner.QuarkusEntryPoint " + configArgs.stream().collect(Collectors.joining(" "));

        if (configArgsStr.contains("--auto-build")) {
            String buildCommand = javaCommand + " -Dkc.config.rebuild-and-exit=true " + javaRunOpts;
            try {
                Process buildProcess = Runtime.getRuntime().exec(buildCommand);
                int exitValue = buildProcess.exitValue();
                System.exit(exitValue);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        try {
            Process runProcess = Runtime.getRuntime().exec(javaCommand + " " + javaRunOpts);
            System.exit(runProcess.exitValue());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
