package com.chorus.engine.sample;

import com.chorus.engine.sample.claude.ClaudeCodeApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
class SpringCliRunner implements CommandLineRunner {

    @Override
    public void run(String... args) throws Exception {
        ClaudeCodeApplication.main(args);
    }
}
