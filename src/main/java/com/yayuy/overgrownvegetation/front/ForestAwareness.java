package com.yayuy.overgrownvegetation.front;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.FrontTable;
import com.yayuy.overgrownvegetation.director.ForestVision;
import com.yayuy.overgrownvegetation.organ.ForestOrgans;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <b>森の中で気づかれる</b>(2026-11-22 ユーザー決定 B 案。依頼書 storage-and-stealth-brief.md の 2)。
 * 管理された森の中にいるプレイヤーは、行動で「気づき」がたまる。awareThreshold を超えたら noticedSeconds の間「見つかった」になり、
 * 今の狩り({@link FrontHunts})と同じ形で近くの部隊が狩りに来る。気づきは時間で減る。見つかった後は calmSeconds の間たまりにくい。
 * <ul>
 *   <li>気づかれる行動(ユーザー決定): 火と爆発 / 器官や巨大樹を叩く / 環境生物以外(植物側の Mob)との戦闘 / 器官・巨大樹のまわりでだけ走る。</li>
 *   <li>気づかれない: 汚染の植物を壊す・刈る・ふつうに歩く・しゃがむ・(器官・巨大樹のまわり以外で)走る。</li>
 *   <li>奥ほどたまりやすい: 縁(過繁茂でないが管理された森の中)× awareEdgeFactor・過繁茂の林床 × awareFloorFactor・巨大樹・器官のそば × awareNearFactor。</li>
 * </ul>
 * 数は全部【仮】(front.json の aware*)。保存しない(再起動で 0 から)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ForestAwareness {

    /** プレイヤーごとの気づき。 */
    static final class State {
        double value;
        long noticedUntil = Long.MIN_VALUE;
        long calmUntil = Long.MIN_VALUE;
        long lastHitTick = Long.MIN_VALUE;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    /** これまで(保存しない): 見つかった回数。 */
    public static long noticed;

    private ForestAwareness() {
    }

    /** 行動の種類。 */
    public enum Act {
        FIRE, EXPLOSION, HIT_ORGAN, HIT_TREE, FIGHT_HIT, FIGHT_KILL, RUN
    }

    static double amount(FrontTable t, Act a) {
        return switch (a) {
            case FIRE -> t.awareFire;
            case EXPLOSION -> t.awareExplosion;
            case HIT_ORGAN -> t.awareHitOrgan;
            case HIT_TREE -> t.awareHitTree;
            case FIGHT_HIT -> t.awareFightHit;
            case FIGHT_KILL -> t.awareFightKill;
            case RUN -> t.awareRunPerSecond;
        };
    }

    /** そこが森のどこか: 0 = 森の外(たまらない)・1 = 縁・2 = 過繁茂の林床・3 = 巨大樹・器官のそば。 */
    public static int depth(ServerLevel level, double x, double z) {
        if (level.dimension() != Level.OVERWORLD) return 0;
        FrontTable t = DesignData.front();
        if (nearTreeOrOrgan(level, x, z, t.awareNearBlocks)) return 3;
        if (!FrontTrees.managed(level, x, z)) return 0;
        boolean overgrown = PhaseData.get(level).isOvergrownKey(ChunkPos.asLong(net.minecraft.util.Mth.floor(x) >> 4, net.minecraft.util.Mth.floor(z) >> 4));
        if (overgrown) return 2;
        return ForestVision.nearForest(level, x, z, DesignData.director().vision.forestEdgeBlocks) ? 1 : 0;
    }

    static boolean nearTreeOrOrgan(ServerLevel level, double x, double z, double r) {
        double r2 = r * r;
        for (long k : PhaseData.get(level).giantTreeOriginSnapshot()) {
            double dx = (int) (k >> 32) + 0.5D - x, dz = (int) k + 0.5D - z;
            if (dx * dx + dz * dz <= r2) return true;
        }
        for (ForestOrgans.Organ o : ForestOrgans.get(level).organs()) {
            if (!o.active()) continue;
            double dx = o.x + 0.5D - x, dz = o.z + 0.5D - z;
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    static double depthFactor(FrontTable t, int depth) {
        return switch (depth) {
            case 1 -> t.awareEdgeFactor;
            case 2 -> t.awareFloorFactor;
            case 3 -> t.awareNearFactor;
            default -> 0.0D;
        };
    }

    /** プレイヤー p に行動 a の気づきを足す(times 回分)。足した量を返す。 */
    public static double add(ServerPlayer p, Act a, double times) {
        FrontTable t = DesignData.front();
        if (!t.awareEnabled || !FrontDirector.enabled() || p.isCreative() || p.isSpectator()) return 0.0D;
        if (!(p.level() instanceof ServerLevel level)) return 0.0D;
        int depth = depth(level, p.getX(), p.getZ());
        if (depth <= 0) return 0.0D;
        State s = STATES.computeIfAbsent(p.getUUID(), u -> new State());
        long now = level.getGameTime();
        double gain = amount(t, a) * times * depthFactor(t, depth);
        if (now < s.calmUntil) gain *= t.awareCalmFactor;
        if (gain <= 0.0D) return 0.0D;
        s.value += gain;
        if (s.value >= t.awareThreshold && now >= s.noticedUntil) {
            s.noticedUntil = now + (long) (t.awareNoticedSeconds * 20.0D);
            s.calmUntil = s.noticedUntil + (long) (t.awareCalmSeconds * 20.0D);
            s.value = 0.0D;
            noticed++;
        }
        return gain;
    }

    /** 気づきで見つかっている間か(狩りの「見られている」に足す)。 */
    public static boolean noticed(ServerLevel level, ServerPlayer p) {
        State s = STATES.get(p.getUUID());
        return s != null && level.getGameTime() < s.noticedUntil;
    }

    /** 今の気づき(確かめ用)。 */
    public static double value(ServerPlayer p) {
        State s = STATES.get(p.getUUID());
        return s == null ? 0.0D : s.value;
    }

    /** テスト用: 消す。 */
    public static void clearForTests() {
        STATES.clear();
        noticed = 0L;
    }

    /** テスト用: 時間を進めたことにする(見つかった・たまりにくい間を ticks だけ早める)。 */
    public static void ageForTests(UUID u, long ticks) {
        State s = STATES.get(u);
        if (s == null) return;
        s.noticedUntil -= ticks;
        s.calmUntil -= ticks;
    }

    // ---------------------------------------------------------------- 減る・走る

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 11) return;
        tick(server.overworld(), 1.0D);
    }

    /** seconds 秒ぶん: 気づきを減らし、器官・巨大樹のまわりで走っている人に足す。 */
    public static void tick(ServerLevel level, double seconds) {
        FrontTable t = DesignData.front();
        if (!t.awareEnabled) return;
        for (State s : STATES.values()) s.value = Math.max(0.0D, s.value - t.awareDecayPerSecond * seconds);
        for (ServerPlayer p : level.players()) {
            if (p.isSprinting() && depth(level, p.getX(), p.getZ()) == 3) add(p, Act.RUN, seconds);
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }

    // ---------------------------------------------------------------- 行動

    /** 火: 火打ち石・ファイアチャージを使った。 */
    @SubscribeEvent
    public static void onUseItemOnBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        ItemStack st = event.getItemStack();
        if (st.is(Items.FLINT_AND_STEEL) || st.is(Items.FIRE_CHARGE)) add(p, Act.FIRE, 1.0D);
    }

    /** 火: 火を置いた(溶岩を流す・燃える物を置く MOD の道具など)。 */
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        BlockState s = event.getPlacedBlock();
        if (s.is(BlockTags.FIRE)) add(p, Act.FIRE, 1.0D);
    }

    /** 爆発: まわりにいる人に足す(誰が起こしても。森の中で爆発が起きれば気づく)。 */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        FrontTable t = DesignData.front();
        Vec3 c = event.getExplosion().center();
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(c) <= t.awareExplosionBlocks * t.awareExplosionBlocks) add(p, Act.EXPLOSION, 1.0D);
        }
    }

    /** 器官・巨大樹を叩く(掘り始め)。汚染の植物を壊すのは数えない。続けて叩いても 10 tick に 1 回。 */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) return;
        BlockState s = p.level().getBlockState(event.getPos());
        Act a = s.is(ModTags.GIANT_WOOD) ? Act.HIT_TREE : isOrganBlock(s) ? Act.HIT_ORGAN : null;
        if (a == null) return;
        State st = STATES.computeIfAbsent(p.getUUID(), u -> new State());
        long now = p.level().getGameTime();
        if (now - st.lastHitTick < 10L) return;
        st.lastHitTick = now;
        add(p, a, 1.0D);
    }

    static boolean isOrganBlock(BlockState s) {
        ResourceLocation k = BuiltInRegistries.BLOCK.getKey(s.getBlock());
        return OvergrownVegetation.MODID.equals(k.getNamespace()) && k.getPath().startsWith("organ_");
    }

    /** 叩いた相手が気づきの戦闘か: 巨大樹(ハイヴノード)= 巨大樹を叩く・環境生物でない植物側 = 戦闘。それ以外は null。 */
    static Act fightKind(LivingEntity target) {
        if (target instanceof com.yayuy.overgrownvegetation.entity.HiveNodeEntity) return Act.HIT_TREE;
        if (!com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(target)) return null;
        MobGroup g = MobGroup.of(target.getType());
        if (g == MobGroup.CRITTER_PREY || g == MobGroup.CRITTER_HUNTER) return null;
        return Act.FIGHT_HIT;
    }

    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        ServerPlayer p = attacker(event.getSource().getEntity());
        if (p == null) return;
        Act a = fightKind(event.getEntity());
        if (a != null) add(p, a, 1.0D);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        ServerPlayer p = attacker(event.getSource().getEntity());
        if (p == null) return;
        if (fightKind(event.getEntity()) == Act.FIGHT_HIT) add(p, Act.FIGHT_KILL, 1.0D);
    }

    static ServerPlayer attacker(Entity e) {
        return e instanceof ServerPlayer p ? p : null;
    }

    /** 確かめ用: 1 行の様子。 */
    public static String describe(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level)) return "";
        State s = STATES.get(p.getUUID());
        long now = level.getGameTime();
        return String.format(java.util.Locale.ROOT, "気づき %.1f / %.0f・森の奥行き %d・見つかっている %s・たまりにくい残り %.0f 秒(これまで見つかった %d 回)",
                s == null ? 0.0D : s.value, DesignData.front().awareThreshold, depth(level, p.getX(), p.getZ()),
                s != null && now < s.noticedUntil ? "はい" : "いいえ", s == null ? 0.0D : Math.max(0L, s.calmUntil - now) / 20.0D, noticed);
    }

    /** テスト用: その位置に BlockPos を渡す版。 */
    public static int depth(ServerLevel level, BlockPos pos) {
        return depth(level, pos.getX() + 0.5D, pos.getZ() + 0.5D);
    }
}
