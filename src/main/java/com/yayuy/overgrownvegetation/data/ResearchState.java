package com.yayuy.overgrownvegetation.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * プレイヤーごとの研究の進捗 — 貯めた研究ポイントと開放済みノード id の集合。
 *
 * <p>{@link com.yayuy.overgrownvegetation.registry.ModAttachments#RESEARCH} で
 * プレイヤーに付随し、ワールドセーブに保存される(死亡しても保持)。
 */
public final class ResearchState {

    public static final Codec<ResearchState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("points", 0).forGetter(ResearchState::points),
            Codec.STRING.listOf().optionalFieldOf("unlocked", List.of())
                    .forGetter(s -> List.copyOf(s.unlocked))
    ).apply(i, ResearchState::new));

    private int points;
    private final Set<String> unlocked = new LinkedHashSet<>();

    public ResearchState() {
    }

    public ResearchState(int points, List<String> unlocked) {
        this.points = points;
        this.unlocked.addAll(unlocked);
    }

    public int points() {
        return points;
    }

    public void addPoints(int delta) {
        points = Math.max(0, points + delta);
    }

    public void spendPoints(int cost) {
        points = Math.max(0, points - cost);
    }

    public Set<String> unlocked() {
        return unlocked;
    }

    public boolean isUnlocked(String nodeId) {
        return unlocked.contains(nodeId);
    }

    public void unlock(String nodeId) {
        unlocked.add(nodeId);
    }

    public ResearchState copy() {
        return new ResearchState(points, List.copyOf(unlocked));
    }
}
