package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 忍び足(2026-10-16 ユーザー指定「忍び足の意味」)。シダの茂み({@link ModTags#SPORE_PUFF_PLANTS})を普通に歩いて・走って抜けると
 * 光る胞子が舞い({@code client/FernSporePuffs})、近くの過繁茂の Mob に気付かれやすくなる。しゃがんで歩けば舞わない。
 *
 * <p>気付かれやすさは「被検知距離のボーナス」({@link SporeInfectionDetection#bonusFor(LivingEntity, LivingEntity)})に足す ——
 * 香ばしいにおいと同じく、どこかのグループに属する Mob なら誰でも聞き付ける(バニラの Mob には効かない)。
 * 歩くと +{@link #WALK_BONUS}、走ると +{@link #SPRINT_BONUS} ブロック、茂みを出てから {@link #LINGER_TICKS} tick 残る。
 * 数値は Claude の仮決め。しゃがむとバニラの決まりで見つかる距離が 0.8 倍になるのはそのまま。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FernRustle {

    /** 歩いて・走って抜けたときの被検知距離のボーナス(ブロック)。 */
    public static final double WALK_BONUS = 8.0D;
    public static final double SPRINT_BONUS = 16.0D;
    /** 茂みを出てからボーナスが残る時間(tick)。 */
    static final int LINGER_TICKS = 60;
    /** 調べる間隔(tick)と、その間に動いたとみなす水平の距離(ブロック。毎秒 0.75 ブロックほど)。 */
    static final int INTERVAL = 4;
    static final double MIN_MOVE = 0.15D;

    /** プレイヤーごとの {ボーナス, 期限(ゲームの時刻), 前に見た x, 前に見た z}。 */
    private static final Map<UUID, double[]> STATE = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || player.tickCount % INTERVAL != 0) {
            return;
        }
        update(player);
    }

    /** 1 回ぶん調べる({@link #INTERVAL} tick ごと。GameTest からも呼ぶ —— 偽プレイヤーは tick しない)。 */
    public static void update(Player player) {
        Level level = player.level();
        double[] s = STATE.get(player.getUUID());
        if (player.isSpectator() || !player.isAlive()) {
            if (s != null) {
                STATE.remove(player.getUUID());
            }
            return;
        }
        double x = player.getX();
        double z = player.getZ();
        if (s == null) {
            s = new double[] {0.0D, 0.0D, x, z};
            STATE.put(player.getUUID(), s);
        }
        double dx = x - s[2];
        double dz = z - s[3];
        s[2] = x;
        s[3] = z;
        if (dx * dx + dz * dz < MIN_MOVE * MIN_MOVE || player.isCrouching() || !inPlants(level, player)) {
            return;
        }
        double bonus = player.isSprinting() ? SPRINT_BONUS : WALK_BONUS;
        long now = level.getGameTime();
        if (now >= (long) s[1] || bonus >= s[0]) {
            s[0] = bonus;
        }
        s[1] = now + LINGER_TICKS;
    }

    /** {@code entity} がシダの茂みの中にいるか(足元か、その 1 つ上 —— 高さ 2 の植物の上の段)。 */
    public static boolean inPlants(Level level, LivingEntity entity) {
        BlockPos feet = entity.blockPosition();
        return level.getBlockState(feet).is(ModTags.SPORE_PUFF_PLANTS)
                || entity.getBbHeight() > 1.0F && level.getBlockState(feet.above()).is(ModTags.SPORE_PUFF_PLANTS);
    }

    /** 今の葉擦れのボーナス(ブロック。プレイヤー以外・期限切れは 0)。 */
    public static double bonusFor(LivingEntity candidate) {
        if (!(candidate instanceof Player)) {
            return 0.0D;
        }
        double[] s = STATE.get(candidate.getUUID());
        if (s == null || candidate.level().getGameTime() >= (long) s[1]) {
            return 0.0D;
        }
        return s[0];
    }

    /** 今だれかに付いている葉擦れのボーナスのいちばん大きい値(索敵箱を広げる分。だれにも無ければ 0)。 */
    public static double activeMax(long gameTime) {
        double max = 0.0D;
        for (double[] s : STATE.values()) {
            if (gameTime < (long) s[1]) {
                max = Math.max(max, s[0]);
            }
        }
        return max;
    }

    /** GameTest 用: 葉擦れの記録を消す。 */
    public static void clear(Player player) {
        STATE.remove(player.getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATE.remove(event.getEntity().getUUID());
    }

    private FernRustle() {
    }
}
