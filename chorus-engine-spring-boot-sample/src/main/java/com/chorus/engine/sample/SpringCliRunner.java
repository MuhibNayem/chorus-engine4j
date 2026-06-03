package com.chorus.engine.sample;

import com.chorus.engine.sample.claude.ClaudeCodeApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
class SpringCliRunner implements CommandLineRunner {

    private final Environment environment;

    SpringCliRunner(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(String... args) throws Exception {
        // Web profile: embedded server is running — skip CLI loop.
        if (environment.matchesProfiles("web")) {
            return;
        }
        ClaudeCodeApplication.main(args);
    }
}
