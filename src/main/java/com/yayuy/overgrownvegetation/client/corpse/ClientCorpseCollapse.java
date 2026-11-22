package com.yayuy.overgrownvegetation.client.corpse;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.CorpseCollapsePayload;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.LevelEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * クライアント: いま死体の姿勢へ崩れ落ちている Mob(2026-10-09)。{@link CorpseCollapsePayload} で入り、Mob が消えたら出る。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class ClientCorpseCollapse {

    private ClientCorpseCollapse() {
    }

    /** 崩れ落ち 1 件: 姿勢と、死んだときの体の向き(サーバーの値。描画の向きをこれに固定する)。 */
    public record Collapse(CorpsePoses.Pose pose, float yaw) {
    }

    private static final Map<Integer, Collapse> ACTIVE = new HashMap<>();

    public static void onPayload(CorpseCollapsePayload payload) {
        CorpsePoses.Pose pose = CorpsePoses.get(payload.species());
        if (pose != null) {
            ACTIVE.put(payload.entityId(), new Collapse(pose, payload.yaw()));
        }
    }

    /** 崩れ落ちの途中なら、その姿勢。生きている Mob は null(同じ id が使い回されたときの取り違えを防ぐ)。 */
    @Nullable
    public static Collapse get(LivingEntity entity) {
        if (ACTIVE.isEmpty() || entity.isAlive()) {
            return null;
        }
        return ACTIVE.get(entity.getId());
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() && !ACTIVE.isEmpty()) {
            ACTIVE.remove(event.getEntity().getId());
            HANDED_OFF.remove(event.getEntity().getId());
        }
    }

    /**
     * 切断・次元の移動で ClientLevel が捨てられるときは全部空にする(2026-10-10)。捨てられるワールドの Mob には
     * {@link EntityLeaveLevelEvent} が来ないので、今までは前のワールドの崩れ落ちが残り、同じ id の Mob に当たりえた。
     * 次元の移動では、Unload が来てから新しいワールドの Mob と崩れ落ちの知らせが届く(同じスレッドで順番に処理される)ので、
     * 新しいワールドの崩れ落ちを消すことは無い。統合サーバーのワールドの Unload はサーバーのスレッドから来るので、
     * クライアントのワールドのときだけ空にする。
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            clear();
        }
    }

    private static void clear() {
        ACTIVE.clear();
        HANDED_OFF.clear();
        DRAWN.clear();
    }

    // ------------------------------------------------------------------ 死体ブロックへの引き継ぎ
    /** 崩れ落ちから置かれた死体が描かれ始めた場所(死体の原点 = 死んだ Mob の足元)。描いた時刻(ゲーム時間)つき。 */
    private record Drawn(double x, double y, double z, long time) {
    }

    private static final java.util.List<Drawn> DRAWN = new java.util.ArrayList<>();

    /**
     * 一度死体に引き継いだ Mob(id)。消えるまで二度と描かない(10-09: 置いてすぐ孵すと死体が無くなり、まだ残っている
     * 崩れ落ちた Mob がまた見えた。羊の赤い毛が起き上がる感染体の横に出た)。Mob が消えたら外す。
     */
    private static final java.util.Set<Integer> HANDED_OFF = new java.util.HashSet<>();

    /**
     * 死体ブロックの描画が呼ぶ: この原点に死体を描いた(崩れ落ちた Mob はもう描かなくてよい)。描くたびに時刻を新しくする。
     * 崩れ落ちている Mob がいないときは何もしない(ふだんの死体の描画に負担を掛けない)。
     */
    public static void corpseDrawn(double x, double y, double z, long gameTime) {
        if (ACTIVE.isEmpty()) {
            DRAWN.clear();
            return;
        }
        DRAWN.removeIf(d -> (Math.abs(d.x() - x) < 1.0E-3 && Math.abs(d.z() - z) < 1.0E-3 && Math.abs(d.y() - y) < 1.0E-3)
                || gameTime - d.time() > 20 || d.time() > gameTime);
        DRAWN.add(new Drawn(x, y, z, gameTime));
    }

    /**
     * 崩れ落ちた Mob を描くのをやめるか: その足元に、崩れ落ちから置かれた死体が<b>いま</b>描かれている(直近 2 tick 以内)なら true
     * (サーバーは死体を置いた後も {@code CorpseCollapse.HANDOFF_TICKS} の間 Mob を残すので、その間は死体だけを見せる)。
     * 前に同じ場所で描いた死体の記録で消さないよう、時刻を見る(10-09 に真上から見て Mob が消えていた原因)。
     */
    public static boolean shouldHide(LivingEntity entity) {
        if (get(entity) == null) {
            return false;
        }
        if (HANDED_OFF.contains(entity.getId())) {
            return true;
        }
        if (DRAWN.isEmpty()) {
            return false;
        }
        long now = entity.level().getGameTime();
        for (Drawn d : DRAWN) {
            if (now - d.time() > 2 || d.time() > now) {
                continue;
            }
            double dx = d.x() - entity.getX();
            double dy = d.y() - entity.getY();
            double dz = d.z() - entity.getZ();
            if (dx * dx + dy * dy + dz * dz < 0.36) {
                HANDED_OFF.add(entity.getId());
                return true;
            }
        }
        return false;
    }
}
