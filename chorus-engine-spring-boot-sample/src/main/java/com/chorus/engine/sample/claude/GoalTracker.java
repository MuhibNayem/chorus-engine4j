package com.chorus.engine.sample.claude;

import com.chorus.engine.agent.middleware.Middleware;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.result.Result;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

final class GoalTracker {

    private String goalCondition = null;
    private BiFunction<String, List<Message>, Boolean> evaluator = null;
    private int maxIterations = 10;
    private int currentIteration = 0;

    void setGoal(String condition, BiFunction<String, List<Message>, Boolean> evaluator) {
        this.goalCondition = condition;
        this.evaluator = evaluator;
        this.currentIteration = 0;
    }

    void setMaxIterations(int max) { this.maxIterations = max; }

    boolean isActive() { return goalCondition != null; }

    String getGoal() { return goalCondition; }

    void clear() { goalCondition = null; evaluator = null; currentIteration = 0; }

    boolean checkGoal(List<Message> history) {
        if (!isActive() || evaluator == null) return true;
        currentIteration++;
        if (currentIteration >= maxIterations) {
            clear();
            return true;
        }
        return evaluator.apply(goalCondition, history);
    }

    Middleware toMiddleware() {
        return new GoalMiddleware();
    }

    private class GoalMiddleware implements Middleware {
        @Override public int priority() { return 998; }

        @Override
        public Result<String, MiddlewareError> extraSystemPrompt(
                String runId, List<Message> history, Map<String, Object> context) {
            if (goalCondition != null) {
                return Result.ok("IMPORTANT: You must continue working until this goal is achieved: "
                        + goalCondition + ". Check after each round if the goal is met. "
                        + "If not met, continue working. Iteration " + currentIteration
                        + " of " + maxIterations + ".");
            }
            return Result.ok("");
        }
    }
}
