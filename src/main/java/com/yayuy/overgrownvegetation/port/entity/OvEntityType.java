package com.yayuy.overgrownvegetation.port.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【1.20.1 だけの物・互換の層】1.20.5 の {@code EntityType.Builder} の書き方({@code eyeHeight}・{@code spawnDimensionsScale})を 1.20.1 で受ける。
 * 本流の {@code EntityType.Builder} は port-rewrite.pl が {@code OvEntityType.Builder} に書き換える(ほかのメソッドはそのまま中の 1.20.1 の Builder へ)。
 *
 * <ul>
 *   <li>{@code eyeHeight(h)}: 1.20.1 では Forge の {@link EntityEvent.Size} で目の高さを入れる。当たり判定が拡大・縮小したら同じ割合で動かす
 *       (1.20.5 の {@code EntityDimensions#scale} と同じ)。</li>
 *   <li>{@code spawnDimensionsScale(s)}: 1.20.1 には湧く場所の箱の倍率が無い。値は控えるだけ({@link #spawnScale})。湧く場所の空きは素の箱で見る
 *       (1.20.1 で動きが変わる所。PORTING.md の一覧)。</li>
 * </ul>
 */
public final class OvEntityType {

    private OvEntityType() {
    }

    private record Eye(float eyeHeight, float baseHeight) {
    }

    private static final Map<EntityType<?>, Eye> EYE = new ConcurrentHashMap<>();
    private static final Map<EntityType<?>, Float> SPAWN_SCALE = new ConcurrentHashMap<>();

    /** {@code PortSetup} が起動のときに呼ぶ。 */
    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(OvEntityType::onSize);
    }

    private static void onSize(EntityEvent.Size e) {
        Eye eye = EYE.get(e.getEntity().getType());
        if (eye == null || eye.baseHeight() <= 0.0F) {
            return;
        }
        e.setNewEyeHeight(eye.eyeHeight() * (e.getNewSize().height / eye.baseHeight()));
    }

    /** 本流の {@code spawnDimensionsScale} の値(1.20.1 では使っていない。控えだけ)。 */
    public static float spawnScale(EntityType<?> type) {
        return SPAWN_SCALE.getOrDefault(type, 1.0F);
    }

    public static final class Builder<T extends Entity> {
        private final EntityType.Builder<T> inner;
        private float height = 1.8F;
        private Float eyeHeight;
        private float spawnScale = 1.0F;

        private Builder(EntityType.Builder<T> inner) {
            this.inner = inner;
        }

        public static <T extends Entity> Builder<T> of(EntityType.EntityFactory<T> factory, MobCategory category) {
            return new Builder<>(EntityType.Builder.of(factory, category));
        }

        public static <T extends Entity> Builder<T> createNothing(MobCategory category) {
            return new Builder<>(EntityType.Builder.createNothing(category));
        }

        public Builder<T> sized(float width, float height) {
            inner.sized(width, height);
            this.height = height;
            return this;
        }

        public Builder<T> eyeHeight(float eyeHeight) {
            this.eyeHeight = eyeHeight;
            return this;
        }

        public Builder<T> spawnDimensionsScale(float scale) {
            this.spawnScale = scale;
            return this;
        }

        public Builder<T> clientTrackingRange(int range) {
            inner.clientTrackingRange(range);
            return this;
        }

        public Builder<T> updateInterval(int interval) {
            inner.updateInterval(interval);
            return this;
        }

        public Builder<T> fireImmune() {
            inner.fireImmune();
            return this;
        }

        public Builder<T> noSummon() {
            inner.noSummon();
            return this;
        }

        public Builder<T> noSave() {
            inner.noSave();
            return this;
        }

        public Builder<T> canSpawnFarFromPlayer() {
            inner.canSpawnFarFromPlayer();
            return this;
        }

        public Builder<T> immuneTo(Block... blocks) {
            inner.immuneTo(blocks);
            return this;
        }

        public Builder<T> setShouldReceiveVelocityUpdates(boolean value) {
            inner.setShouldReceiveVelocityUpdates(value);
            return this;
        }

        public EntityType<T> build(String id) {
            EntityType<T> type = inner.build(id);
            if (eyeHeight != null) {
                EYE.put(type, new Eye(eyeHeight, height));
            }
            if (spawnScale != 1.0F) {
                SPAWN_SCALE.put(type, spawnScale);
            }
            return type;
        }
    }
}
