package com.zalando.onboarding.api;

import java.util.Map;

/** @param data the stored values plus the {@code completedAt} stamp the server added */
public record SavedSection(String id, Map<String, Object> data) {
}
