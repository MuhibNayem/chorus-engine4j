package com.chorus.observe.persistence;

import com.chorus.observe.model.AlertEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class InMemoryAlertEventRepository extends AlertEventRepository {
    private final Map<String, Map<String, AlertEvent>> store = new HashMap<>();

    public InMemoryAlertEventRepository() {
        super(null, new ObjectMapper());
    }

    @Override
    public void save(AlertEvent event) {
        String tenantId = com.chorus.observe.security.TenantContext.getTenantId();
        store.computeIfAbsent(tenantId, k -> new HashMap<>()).put(event.eventId(), event);
    }

    @Override
    public Optional<AlertEvent> findById(String eventId, String tenantId) {
        Map<String, AlertEvent> tenantStore = store.get(tenantId);
        return Optional.ofNullable(tenantStore != null ? tenantStore.get(eventId) : null);
    }

    @Override
    public List<AlertEvent> findByRuleId(String ruleId, String tenantId) {
        return findAll(tenantId).stream().filter(e -> e.ruleId().equals(ruleId)).sorted(Comparator.comparing(AlertEvent::triggeredAt).reversed()).collect(Collectors.toList());
    }

    @Override
    public List<AlertEvent> findByRuleId(String ruleId, String tenantId, int limit, int offset) {
        return findByRuleId(ruleId, tenantId).stream().skip(offset).limit(limit).collect(Collectors.toList());
    }

    @Override
    public long countByRuleId(String ruleId, String tenantId) {
        return findByRuleId(ruleId, tenantId).size();
    }

    @Override
    public List<AlertEvent> findUnresolved(String tenantId) {
        return findAll(tenantId).stream().filter(e -> e.resolvedAt() == null).sorted(Comparator.comparing(AlertEvent::triggeredAt).reversed()).collect(Collectors.toList());
    }

    @Override
    public List<AlertEvent> findUnresolved(String tenantId, int limit, int offset) {
        return findUnresolved(tenantId).stream().skip(offset).limit(limit).collect(Collectors.toList());
    }

    @Override
    public long countUnresolved(String tenantId) {
        return findUnresolved(tenantId).size();
    }

    @Override
    public List<AlertEvent> findRecent(String tenantId, int limit) {
        return findAll(tenantId).stream().sorted(Comparator.comparing(AlertEvent::triggeredAt).reversed()).limit(limit).collect(Collectors.toList());
    }

    @Override
    public List<AlertEvent> findRecent(String tenantId, int limit, int offset) {
        return findAll(tenantId).stream().sorted(Comparator.comparing(AlertEvent::triggeredAt).reversed()).skip(offset).limit(limit).collect(Collectors.toList());
    }

    @Override
    public long count(String tenantId) {
        Map<String, AlertEvent> tenantStore = store.get(tenantId);
        return tenantStore != null ? tenantStore.size() : 0L;
    }

    @Override
    public Optional<AlertEvent> findMostRecentByRuleId(String ruleId, String tenantId) {
        return findAll(tenantId).stream()
            .filter(e -> e.ruleId().equals(ruleId))
            .max(Comparator.comparing(AlertEvent::triggeredAt));
    }

    @Override
    public List<AlertEvent> findRetryable(String tenantId) {
        return findAll(tenantId).stream()
            .filter(e -> e.nextRetryAt() != null && !e.nextRetryAt().isAfter(Instant.now()) && e.retryCount() < 3)
            .sorted(Comparator.comparing(AlertEvent::nextRetryAt))
            .collect(Collectors.toList());
    }

    private List<AlertEvent> findAll(String tenantId) {
        Map<String, AlertEvent> tenantStore = store.getOrDefault(tenantId, Map.of());
        return tenantStore.values().stream().sorted(Comparator.comparing(AlertEvent::triggeredAt).reversed()).collect(Collectors.toList());
    }
}
