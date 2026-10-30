package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 胞子警報器({@code block/SporeAlarmBlock})の台帳と、警告の配信(2026-09-19)。
 *
 * <p>ロード済みの警報器の座標をワールドごとに覚えておき({@link SporeAlarmBlockEntity} が
 * 置かれた/読み込まれたときに登録、壊れた/アンロードで解除)、
 * {@link #warn} が呼ばれたら<b>どれかの警報器から {@code sporeAlarm.radius} 以内に居るプレイヤー</b>へ
 * チャットで警告を流し、その警報器で鐘を鳴らす。
 *
 * <p>警告を出す出来事:
 * <ul>
 *     <li>フェーズの移り変わり(上がった瞬間 + 次のフェーズまでの達成率が {@code phaseWarnFraction} に達したとき)
 *     — {@code phase/PhaseData}</li>
 *     <li>レイド(レイドビーコンの出現。方角と距離つき)— {@code raid/RaidController}</li>
 * </ul>
 * 警報器が 1 台も無ければ何も出ない(= 警報器を作る動機)。
 */
public final class SporeAlarms {

    private static final Map<ResourceKey<Level>, Set<BlockPos>> ALARMS = new ConcurrentHashMap<>();

    public static void register(Level level, BlockPos pos) {
        ALARMS.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet()).add(pos.immutable());
    }

    public static void unregister(Level level, BlockPos pos) {
        Set<BlockPos> set = ALARMS.get(level.dimension());
        if (set != null) {
            set.remove(pos);
        }
    }

    public static Set<BlockPos> alarms(Level level) {
        Set<BlockPos> set = ALARMS.get(level.dimension());
        return set != null ? Collections.unmodifiableSet(set) : Set.of();
    }

    /** ワールドの読み直しで古い座標が残らないように(サーバー停止時)。 */
    public static void clearAll() {
        ALARMS.clear();
    }

    /**
     * 警告を流す。{@code source} があれば、受け取る各プレイヤーからの方角と距離を文末に足す。
     *
     * @param message 本文(翻訳済みコンポーネント)
     * @param source  出来事の場所(方角・距離の計算用)。無ければ null
     */
    public static void warn(ServerLevel level, Component message, @Nullable Vec3 source) {
        Set<BlockPos> alarms = alarms(level);
        if (alarms.isEmpty()) {
            return;
        }
        double radius = DesignData.defense().alarmRadius();
        double radiusSq = radius * radius;
        Set<BlockPos> rung = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            BlockPos nearest = null;
            double bestSq = radiusSq;
            for (BlockPos pos : alarms) {
                double sq = player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
                if (sq <= bestSq) {
                    bestSq = sq;
                    nearest = pos;
                }
            }
            if (nearest == null) {
                continue;
            }
            Component line = source != null
                    ? Component.translatable("message.overgrown_vegetation.alarm.prefix_with_bearing",
                            message, bearing(player.position(), source), (int) Math.round(player.position().distanceTo(source)))
                    : Component.translatable("message.overgrown_vegetation.alarm.prefix", message);
            player.sendSystemMessage(line);
            if (rung.add(nearest)) {
                level.playSound(null, nearest, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2.0F, 0.8F);
            }
        }
    }

    /** {@code from} から見た {@code to} の 8 方位(翻訳キー経由で「北西」など)。 */
    public static Component bearing(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        if (Math.abs(dx) < 1.0E-3D && Math.abs(dz) < 1.0E-3D) {
            return Component.translatable("message.overgrown_vegetation.bearing.here");
        }
        // Minecraft: -Z = 北、+X = 東。
        double angle = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = 北、90 = 東
        int octant = (int) Math.floor(((angle % 360.0D + 360.0D) % 360.0D + 22.5D) / 45.0D) % 8;
        String[] keys = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
        return Component.translatable("message.overgrown_vegetation.bearing." + keys[octant]);
    }

    private SporeAlarms() {
    }
}
