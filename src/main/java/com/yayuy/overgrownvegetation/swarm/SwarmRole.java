package com.yayuy.overgrownvegetation.swarm;

import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;

/**
 * 群れの中の<b>役割</b>(群れ v2、2026-09-25)。種で決まる。
 *
 * <p>ユーザー指定(2026-09-15、v2 でも維持):「アルボラ・スカトゥリウムを前に、スポロフィル・フィロセンスを後方に」。
 * 陣形の並び順は {@link #order}(小さいほど前)。ヴェロフィルは空の群れを別に作る(ユーザー指定 2026-09-25)。
 * ペディキュラは群れに入らない({@link #of} が null)。
 */
public enum SwarmRole {
    /** 前衛(アルボラ・過繁茂したスカトゥリウム)。先導役はこの中から選ぶ。 */
    FRONT(0),
    /** 支援(スポロフィル)。標的を<b>一切</b>受け取らない(攻撃手段が無い)。 */
    SUPPORT(1),
    /** 遠距離・索敵(フィロセンス)。ビームの射程で標的を受け取る。包絡には入れない。 */
    RANGED_SCOUT(2),
    /** 空(ヴェロフィル)。空の群れだけを作る。 */
    AIR(3);

    /** 陣形の並び順(小さいほど前)。 */
    public final int order;

    SwarmRole(int order) {
        this.order = order;
    }

    /** 地上の役割か。 */
    public boolean isGround() {
        return this != AIR;
    }

    /** その種の役割。群れに入らない種は null。 */
    @Nullable
    public static SwarmRole of(EntityType<?> type) {
        if (type == ModEntities.ARBORA.get() || type == ModEntities.OVERGROWN_SHIELDER.get()) {
            return FRONT;
        }
        if (type == ModEntities.SPOROPHYLL.get()) {
            return SUPPORT;
        }
        if (type == ModEntities.PHYLLOSENS.get()) {
            return RANGED_SCOUT;
        }
        if (type == ModEntities.VELOPHYLL.get()) {
            return AIR;
        }
        return null;
    }

    @Nullable
    public static SwarmRole of(Entity entity) {
        return of(entity.getType());
    }

    /** アルボラか(先導役の選び方で前衛の中でも先に選ぶ。「アルボラが先導」ユーザー指定)。 */
    public static boolean isArbora(EntityType<?> type) {
        return type == ModEntities.ARBORA.get();
    }

    /** 過繁茂したスカトゥリウムか(標的を渡す距離が迫撃砲の射程になる)。 */
    public static boolean isShielder(EntityType<?> type) {
        return type == ModEntities.OVERGROWN_SHIELDER.get();
    }

    /**
     * 壁を登れる種か。いまはアルボラだけ({@code ArboraEntity#canClimbWalls})。
     * 群れの体の包絡が壁を登る辺を使ってよいのは、地上の全員がこれに当てはまるときだけ。
     */
    public static boolean canClimb(EntityType<?> type) {
        return type == ModEntities.ARBORA.get();
    }
}
