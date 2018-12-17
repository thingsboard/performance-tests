/**
 * Copyright © 2017 Supertech Agroline
 */
package org.thingsboard.tools;

import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.SecurityAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;

@SpringBootApplication(exclude = {SecurityAutoConfiguration.class })
public class PerformanceTestsApplication {

    private static final String SPRING_CONFIG_NAME_KEY = "--spring.config.name";
    private static final String DEFAULT_SPRING_CONFIG_PARAM = SPRING_CONFIG_NAME_KEY + "=" + "tb-performance-tests";

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(PerformanceTestsApplication.class, updateArguments(args));
        int exitCode = SpringApplication.exit(ctx, (ExitCodeGenerator) () -> 0);
        System.exit(exitCode);
    }

    private static String[] updateArguments(String[] args) {
        if (Arrays.stream(args).noneMatch(arg -> arg.startsWith(SPRING_CONFIG_NAME_KEY))) {
            String[] modifiedArgs = new String[args.length + 1];
            System.arraycopy(args, 0, modifiedArgs, 0, args.length);
            modifiedArgs[args.length] = DEFAULT_SPRING_CONFIG_PARAM;
            return modifiedArgs;
        }
        return args;
    }

}
