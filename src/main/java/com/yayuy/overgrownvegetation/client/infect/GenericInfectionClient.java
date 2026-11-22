package com.yayuy.overgrownvegetation.client.infect;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;

/**
 * 汎用の感染体(専用の G1 が無い Mob が生きたまま変わったもの。2026-11-15 ユーザー決定)の、クライアント側の印。
 * 見た目({@link InfectRender})はここだけを読む。入れるのはサーバーからの同期の受け手(土台の側)と、確かめ用の命令。
 */
public final class GenericInfectionClient {

    private static final Int2ObjectOpenHashMap<State> STATES = new Int2ObjectOpenHashMap<>();

    private GenericInfectionClient() {
    }

    /** 変わっていない Mob は null。 */
    @Nullable
    public static State of(Entity entity) {
        return STATES.isEmpty() ? null : STATES.get(entity.getId());
    }

    /** 同期の受け手が呼ぶ。startTick = 変わり始めたゲーム時刻、transformTicks = 変わる動きの長さ。 */
    public static void put(int entityId, long seed, long startTick, int transformTicks, boolean boss) {
        STATES.put(entityId, new State(seed, startTick, transformTicks, boss));
    }

    public static void remove(int entityId) {
        STATES.remove(entityId);
    }

    /** ワールドを出たとき・確かめ用。 */
    public static void clear() {
        STATES.clear();
    }

    public static int count() {
        return STATES.size();
    }

    public record State(long seed, long startTick, int transformTicks, boolean boss) {
        /** 変わる動きの進み 0〜1(1 = 変わり終えた)。 */
        public float progress(long gameTime, float partialTick) {
            if (transformTicks <= 0) {
                return 1.0F;
            }
            return Mth.clamp((gameTime - startTick + partialTick) / transformTicks, 0.0F, 1.0F);
        }
    }
}
