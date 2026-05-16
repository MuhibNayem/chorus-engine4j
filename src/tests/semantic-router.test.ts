import { describe, it, expect } from "vitest";
import { SemanticTaskRouter, routeTaskSemantic } from "../index.js";

describe("SemanticTaskRouter — enterprise intent classification", () => {
  const router = new SemanticTaskRouter({ confidenceThreshold: 0.5 });

  it("classifies research queries with high confidence", async () => {
    const result = await router.route({
      text: "What is the latest version of React?",
      expandedText: "",
    });

    expect(result.kind).toBe("research");
    expect(result.method).toBe("semantic");
    expect(result.confidence).toBeGreaterThan(0.5);
    expect(result.requiresResearch).toBe(true);
  });

  it("classifies debugging queries", async () => {
    const result = await router.route({
      text: "Fix the null pointer exception in utils.js",
      expandedText: "",
    });

    expect(result.kind).toBe("debug");
    expect(result.method).toBe("semantic");
    expect(result.confidence).toBeGreaterThan(0.5);
  });

  it("classifies simple questions as answer_only", async () => {
    const result = await router.route({
      text: "What is 2+2?",
      expandedText: "",
    });

    expect(result.kind).toBe("answer_only");
    expect(result.method).toBe("semantic");
    expect(result.usesCheapTriage).toBe(true);
  });

  it("classifies multi-file edits", async () => {
    const result = await router.route({
      text: "Refactor the authentication module across the codebase",
      expandedText: "",
    });

    expect(result.kind).toBe("multi_file_edit");
    expect(result.canParallelize).toBe(true);
    expect(result.method).toBe("semantic");
  });

  it("classifies project-wide tasks", async () => {
    const result = await router.route({
      text: "Audit the entire codebase for security issues",
      expandedText: "",
    });

    expect(result.kind).toBe("project_phase");
    expect(result.method).toBe("semantic");
  });

  it("falls back to regex for ambiguous queries", async () => {
    const result = await router.route({
      text: "asdfghjkl random noise",
      expandedText: "",
    });

    expect(result.method).toBe("fallback");
    expect(result.confidence).toBe(0);
  });

  it("score() returns ranked confidence for all routes", async () => {
    const scores = await router.score({
      text: "Search for best practices on error handling",
      expandedText: "",
    });

    expect(scores.length).toBeGreaterThan(0);
    expect(scores[0].label).toBe("research");
    expect(scores[0].confidence).toBeGreaterThan(scores[1]?.confidence ?? 0);
  });

  it("convenience function routeTaskSemantic works", async () => {
    const result = await routeTaskSemantic({
      text: "How do I use async/await?",
      expandedText: "",
    });

    expect(result.kind).toBeDefined();
    expect(result.lane).toBeDefined();
    expect(result.path).toBeDefined();
    expect(result.confidence).toBeGreaterThanOrEqual(0);
  });

  it("handles empty input gracefully", async () => {
    const result = await router.route({ text: "", expandedText: "" });
    expect(result.method).toBeDefined();
    expect(result.kind).toBeDefined();
  });
});
