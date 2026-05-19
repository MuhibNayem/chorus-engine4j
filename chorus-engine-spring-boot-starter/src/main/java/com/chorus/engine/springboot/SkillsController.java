package com.chorus.engine.springboot;

import com.chorus.engine.skills.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for skill registry and execution.
 */
@RestController
@RequestMapping("/api/skills")
public class SkillsController {

    private final SkillRegistry skillRegistry;
    private final SkillRouter skillRouter;

    public SkillsController(SkillRegistry skillRegistry, SkillRouter skillRouter) {
        this.skillRegistry = skillRegistry;
        this.skillRouter = skillRouter;
    }

    @GetMapping
    public ResponseEntity<List<SkillDto>> listSkills() {
        List<SkillDto> skills = skillRegistry.allSkills().stream()
            .map(s -> new SkillDto(s.id(), s.name(), s.description(), s.tags()))
            .toList();
        return ResponseEntity.ok(skills);
    }

    @GetMapping("/{skillId}")
    public ResponseEntity<SkillDto> getSkill(@PathVariable String skillId) {
        return skillRegistry.findById(skillId)
            .map(s -> ResponseEntity.ok(new SkillDto(s.id(), s.name(), s.description(), s.tags())))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<SkillDto> registerSkill(@RequestBody RegisterSkillRequest request) {
        Skill skill = new Skill(
            request.id(), request.name(), request.description(),
            request.systemPrompt(), request.toolNames() != null ? request.toolNames() : List.of(),
            request.config() != null ? request.config() : Map.of(),
            request.tags() != null ? request.tags() : List.of()
        );
        skillRegistry.register(skill);
        return ResponseEntity.ok(new SkillDto(skill.id(), skill.name(), skill.description(), skill.tags()));
    }

    @PostMapping("/route")
    public ResponseEntity<RouteResponse> route(@RequestBody RouteRequest request) {
        var result = skillRouter.route(request.input(), skillRegistry.allSkills());
        if (result.isOk()) {
            Skill s = result.unwrap();
            return ResponseEntity.ok(new RouteResponse(s.id(), s.name(), true, null));
        }
        return ResponseEntity.ok(new RouteResponse(null, null, false, result.unwrapErr()));
    }

    @GetMapping("/search")
    public ResponseEntity<List<SkillDto>> search(@RequestParam String query) {
        List<SkillDto> skills = skillRegistry.search(query).stream()
            .map(s -> new SkillDto(s.id(), s.name(), s.description(), s.tags()))
            .toList();
        return ResponseEntity.ok(skills);
    }

    public record SkillDto(String id, String name, String description, List<String> tags) {}
    public record RegisterSkillRequest(String id, String name, String description,
                                       String systemPrompt, List<String> toolNames,
                                       Map<String, Object> config, List<String> tags) {}
    public record RouteRequest(String input) {}
    public record RouteResponse(String skillId, String skillName, boolean matched, String error) {}
}
