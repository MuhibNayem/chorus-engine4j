package com.chorus.engine.sample.claude;

import com.chorus.engine.core.context.Message;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class SkillCommands {

    private final CliRenderer renderer;
    private final CliSession session;
    private final Consumer<String> chatInvoker;
    private final AtomicInteger reviewCount   = new AtomicInteger(0);
    private final AtomicInteger debugCount    = new AtomicInteger(0);
    private final AtomicInteger testCount     = new AtomicInteger(0);
    private final AtomicInteger refactorCount = new AtomicInteger(0);
    private final AtomicInteger docsCount     = new AtomicInteger(0);

    SkillCommands(CliRenderer renderer, CliSession session, Consumer<String> chatInvoker) {
        this.renderer    = renderer;
        this.session     = session;
        this.chatInvoker = chatInvoker;
    }

    void review(String args) {
        reviewCount.incrementAndGet();
        renderer.info("Code Review (review #" + reviewCount.get() + ")");
        renderer.dim("  I will analyze changed files for bugs, security issues, and code quality.");
        handleChatDirect(args.isEmpty()
            ? "/review the current changes in the workspace. Check for bugs, security issues, "
                + "code quality problems, and adherence to conventions. "
                + "Be thorough and provide specific recommendations."
            : "/review " + args);
    }

    void debug(String args) {
        debugCount.incrementAndGet();
        renderer.info("Debug Session (debug #" + debugCount.get() + ")");
        renderer.dim("  I will systematically investigate the issue using the scientific method.");
        handleChatDirect(args.isEmpty()
            ? "/debug Please describe the issue you are encountering. "
                + "I will help you reproduce it, identify the root cause, and fix it."
            : "/debug " + args);
    }

    void test(String args) {
        testCount.incrementAndGet();
        renderer.info("Test Runner (test #" + testCount.get() + ")");
        renderer.dim("  I will find untested code, write tests, run them, and fix any failures.");
        handleChatDirect(args.isEmpty()
            ? "/test Find functions without test coverage, write comprehensive tests following "
                + "existing patterns, run them with the project's test command, and fix any failures."
            : "/test " + args);
    }

    void refactor(String args) {
        refactorCount.incrementAndGet();
        renderer.info("Refactoring Session (refactor #" + refactorCount.get() + ")");
        renderer.dim("  I will analyze, propose, and apply safe refactoring changes.");
        handleChatDirect(args.isEmpty()
            ? "/refactor Analyze the codebase for opportunities to improve: remove duplication, "
                + "simplify complex methods, improve naming, extract interfaces, and apply modern "
                + "Java patterns. Propose specific changes with justification."
            : "/refactor " + args);
    }

    void docs(String args) {
        docsCount.incrementAndGet();
        renderer.info("Documentation Generator (docs #" + docsCount.get() + ")");
        renderer.dim("  I will find undocumented code and generate comprehensive documentation.");
        handleChatDirect(args.isEmpty()
            ? "/docs Find public APIs, classes, and methods without documentation. Generate Javadoc "
                + "comments following the project's documentation standards. Include @param, "
                + "@return, @throws, and usage examples where appropriate."
            : "/docs " + args);
    }

    private void handleChatDirect(String prompt) {
        session.clearHistory();
        session.addToHistory(Message.user(prompt));
        chatInvoker.accept(prompt.isBlank() ? "help" : prompt);
    }
}
