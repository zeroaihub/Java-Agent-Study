package com.zero.ai.agentstudy.day4memory.chapter8;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 最终 Memory Agent 用户画像。
 */
public class FinalUserProfile {

    private String userId;
    private String name;
    private String profession;
    private List<String> learningGoals = new ArrayList<>();
    private List<String> preferences = new ArrayList<>();
    private Instant updatedAt = Instant.now();

    public static FinalUserProfile empty(String userId) {
        FinalUserProfile profile = new FinalUserProfile();
        profile.setUserId(userId);
        return profile;
    }

    public void mergeGoals(List<String> goals) {
        this.learningGoals = merge(this.learningGoals, goals);
        this.updatedAt = Instant.now();
    }

    public void mergePreferences(List<String> newPreferences) {
        this.preferences = merge(this.preferences, newPreferences);
        this.updatedAt = Instant.now();
    }

    public String promptText() {
        return """
                userId=%s
                name=%s
                profession=%s
                learningGoals=%s
                preferences=%s
                updatedAt=%s
                """.formatted(userId, name, profession, learningGoals, preferences, updatedAt);
    }

    private List<String> merge(List<String> oldValues, List<String> newValues) {
        Set<String> merged = new LinkedHashSet<>();
        if (oldValues != null) {
            oldValues.stream().filter(this::hasText).map(String::trim).forEach(merged::add);
        }
        if (newValues != null) {
            newValues.stream().filter(this::hasText).map(String::trim).forEach(merged::add);
        }
        return new ArrayList<>(merged);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProfession() {
        return profession;
    }

    public void setProfession(String profession) {
        this.profession = profession;
    }

    public List<String> getLearningGoals() {
        return learningGoals;
    }

    public void setLearningGoals(List<String> learningGoals) {
        this.learningGoals = learningGoals == null ? new ArrayList<>() : new ArrayList<>(learningGoals);
    }

    public List<String> getPreferences() {
        return preferences;
    }

    public void setPreferences(List<String> preferences) {
        this.preferences = preferences == null ? new ArrayList<>() : new ArrayList<>(preferences);
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

