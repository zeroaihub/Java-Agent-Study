package com.zero.ai.agentstudy.day4memory.chapter4;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 第四章：企业级长期 Memory 用户画像。
 *
 * 这是教学版领域模型，生产环境通常会映射到 MySQL/PostgreSQL。
 */
public class UserProfile {

    private String userId;
    private String name;
    private String profession;
    private List<String> skills = new ArrayList<>();
    private List<String> interests = new ArrayList<>();
    private List<String> learningGoals = new ArrayList<>();
    private List<String> preferences = new ArrayList<>();
    private List<String> recentProjects = new ArrayList<>();
    private double confidence = 0.8;
    private Instant updatedAt = Instant.now();

    public static UserProfile empty(String userId) {
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        return profile;
    }

    public void merge(UserProfilePatch patch) {
        if (hasText(patch.name())) {
            this.name = patch.name().trim();
        }
        if (hasText(patch.profession())) {
            this.profession = patch.profession().trim();
        }
        this.skills = mergeList(this.skills, patch.skills());
        this.interests = mergeList(this.interests, patch.interests());
        this.learningGoals = mergeList(this.learningGoals, patch.learningGoals());
        this.preferences = mergeList(this.preferences, patch.preferences());
        this.recentProjects = mergeList(this.recentProjects, patch.recentProjects());
        this.confidence = Math.max(this.confidence, patch.confidence());
        this.updatedAt = Instant.now();
    }

    public String promptText() {
        return """
                userId=%s
                name=%s
                profession=%s
                skills=%s
                interests=%s
                learningGoals=%s
                preferences=%s
                recentProjects=%s
                confidence=%.2f
                updatedAt=%s
                """.formatted(userId, name, profession, skills, interests,
                learningGoals, preferences, recentProjects, confidence, updatedAt);
    }

    private List<String> mergeList(List<String> oldValues, List<String> newValues) {
        Set<String> merged = new LinkedHashSet<>();
        if (oldValues != null) {
            oldValues.stream().filter(this::hasText).map(String::trim).forEach(merged::add);
        }
        if (newValues != null) {
            newValues.stream().filter(this::hasText).map(String::trim).forEach(merged::add);
        }
        return new ArrayList<>(merged);
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
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

    public List<String> getSkills() {
        return skills;
    }

    public void setSkills(List<String> skills) {
        this.skills = skills == null ? new ArrayList<>() : new ArrayList<>(skills);
    }

    public List<String> getInterests() {
        return interests;
    }

    public void setInterests(List<String> interests) {
        this.interests = interests == null ? new ArrayList<>() : new ArrayList<>(interests);
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

    public List<String> getRecentProjects() {
        return recentProjects;
    }

    public void setRecentProjects(List<String> recentProjects) {
        this.recentProjects = recentProjects == null ? new ArrayList<>() : new ArrayList<>(recentProjects);
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

