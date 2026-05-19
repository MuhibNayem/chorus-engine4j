package com.chorus.engine.a2a;

import com.chorus.engine.a2a.card.AgentCard;
import com.chorus.engine.a2a.card.Authentication;
import com.chorus.engine.a2a.card.Capabilities;
import com.chorus.engine.a2a.card.Skill;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AgentCardTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serialization_roundTrip() throws Exception {
        AgentCard card = new AgentCard(
            "research-agent",
            "Performs web research",
            "https://agent.example.com/",
            "1.0.0",
            new Capabilities(true, false),
            new Authentication(List.of("OAuth2")),
            List.of(new Skill("web-search", "Web Search", "Search the web for information", List.of("search", "web")))
        );

        String json = mapper.writeValueAsString(card);
        AgentCard parsed = mapper.readValue(json, AgentCard.class);

        assertThat(parsed.name()).isEqualTo("research-agent");
        assertThat(parsed.description()).isEqualTo("Performs web research");
        assertThat(parsed.url()).isEqualTo("https://agent.example.com/");
        assertThat(parsed.version()).isEqualTo("1.0.0");
        assertThat(parsed.capabilities().streaming()).isTrue();
        assertThat(parsed.capabilities().pushNotifications()).isFalse();
        assertThat(parsed.authentication().schemes()).containsExactly("OAuth2");
        assertThat(parsed.skills()).hasSize(1);
        assertThat(parsed.skills().get(0).id()).isEqualTo("web-search");
        assertThat(parsed.skills().get(0).tags()).containsExactly("search", "web");
    }

    @Test
    void compactConstructor_defensivelyCopiesSkills() {
        List<Skill> skills = new ArrayList<>();
        skills.add(new Skill("s1", "Skill 1", "Desc", List.of("tag1")));
        AgentCard card = new AgentCard("n", "d", "u", "v",
            new Capabilities(false, false),
            new Authentication(List.of()),
            skills);
        skills.add(new Skill("s2", "Skill 2", "Desc", List.of("tag2")));
        assertThat(card.skills()).hasSize(1);
    }

    @Test
    void authentication_defensivelyCopiesSchemes() {
        List<String> schemes = new ArrayList<>();
        schemes.add("OAuth2");
        Authentication auth = new Authentication(schemes);
        schemes.add("Basic");
        assertThat(auth.schemes()).containsExactly("OAuth2");
    }

    @Test
    void skill_defensivelyCopiesTags() {
        List<String> tags = new ArrayList<>();
        tags.add("search");
        Skill skill = new Skill("id", "name", "desc", tags);
        tags.add("extra");
        assertThat(skill.tags()).containsExactly("search");
    }
}
