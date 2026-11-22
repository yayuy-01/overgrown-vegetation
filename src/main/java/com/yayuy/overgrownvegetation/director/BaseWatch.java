package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.BaseMemoryTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.survival.room.RoomCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import com.yayuy.overgrownvegetation.port.event.tick.ServerTickEvent;

import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 拠点の記憶の見張り(2026-11-14、司令塔 第 2 段 区切り 1)。{@code sampleTicks}(既定 20 秒【仮】)ごとに、オーバーワールドの
 * サバイバル・アドベンチャーのプレイヤーがいる升へ「いた時間」を足し(閉じた部屋の中なら多め。11-14 ユーザー決定 Q2 = 3)、
 * その升の置き物(タグ {@link ModTags#BASE_STRUCTURE})を数え直し、最後に {@link BaseMemory#update} で拠点をまとめ直す。
 *
 * <p>重さ: プレイヤー 1 人につき、その升の足元から上下 {@code scanHalfHeight} の区画のうち、タグの物を含みうる区画だけをなめる
 * (区画のパレットで先に弾く)。20 秒に 1 回。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class BaseWatch {

    private BaseWatch() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        BaseMemoryTable t = DesignData.director().base;
        if (!DesignData.director().enabled || !t.enabled) return;
        if (server.getTickCount() % t.sampleTicks != 0) return;
        sample(server);
    }

    /** 1 回ぶん見張る(確かめ用のコマンドからも呼ぶ)。 */
    public static void sample(MinecraftServer server) {
        BaseMemoryTable t = DesignData.director().base;
        ServerLevel level = server.overworld();
        BaseMemory memory = BaseMemory.get(server);
        double day = BaseMemory.today(server);
        double hours = t.sampleTicks / 1000.0D;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            observe(level, memory, p.blockPosition(), hours, day);
        }
        // 覚える・忘れるは F1 から裏で回す。認識は F5 から、偵察は F4 から、送り込みは F5 から。大きな襲撃の前触れの間は偵察・送り込みを止める(DirectorGates)
        memory.update(day, DirectorGates.open(level, DirectorGates.Gate.RECOGNIZE), BaseMemory.timeOfDay(server));
        // 弱い所を突く: 拠点のまわりの守りの方角を、間を空けて見直す(フェーズを見ない。使う側が見る)
        for (BaseMemory.Base b : memory.bases()) {
            if (day - b.defenseAt() < DesignData.director().base.weakRefreshDays) continue;
            double[] d = BaseDefense.measure(level, b, day);
            b.setDefense(d, BaseDefense.weakest(d), day);
        }
        RaidScales.refreshSaving(level, memory, day); // 見送って貯めている間(貯めの 2 日目から)は G1 が遠巻きにうろつく(11-19 ユーザー)
        ForestMoods.tick(level, day, DesignData.director().base.sampleTicks); // 貯めて一気に使う(第 3 段)
        LargeRaids.tick(level, day, DesignData.director().base.sampleTicks); // 大きな襲撃(第 4 段)
        Watchers.tick(level, memory, day); // 監視塔を植える(F5 から。監視塔が組み込まれてから)
        com.yayuy.overgrownvegetation.organ.OrganPlanner.tick(level, day); // 森の器官を芽吹かせる(2026-11-17。organs.json の間隔で)
        com.yayuy.overgrownvegetation.corruption.fissure.FissurePlanner.tick(level, day); // 地下に小さな亀裂を開く(2026-11-20。F4 から・fissure.json の間隔で。プレイヤーの位置を見ない)
        // 送り込み・危ない所へ送るのを止める: 大きな襲撃の前触れ・本番の間と、貯めている間。偵察は止めない(11-14 ユーザー T1 = 2)
        ForestDirector.Mood mood = ForestDirector.get(level).mood();
        boolean omen = DirectorGates.largeRaidOmen(level) || ForestDirector.hoarding() || mood == ForestDirector.Mood.LARGE
                || HoardRaids.active() // 貯めた襲撃の継ぎ足しの間も、ふつうの送り込みは止める(11-16)
                || DirectorGates.peaceful(level); // ピースフルの間は送り込まない(時計も進めない。見直し ②9)
        if (DirectorGates.open(level, DirectorGates.Gate.SCOUT) && !DirectorGates.peaceful(level)) {
            dispatchScouts(level, memory, day);
            ScoutSearch.get(server).tick(level, day, BaseMemory.timeOfDay(server)); // 探りの偵察(11-20。手がかり A・B・殺された所・D 広がる探し方)
        }
        if (!omen && DirectorGates.open(level, DirectorGates.Gate.RAID)) BaseRaids.dispatch(level, memory, day);
        if (!omen && DirectorGates.open(level, DirectorGates.Gate.LEARN) && !com.yayuy.overgrownvegetation.front.FrontRaids.active(level)) DangerWatch.dispatch(level, day); // 11-21 前線と部隊の狩りに置き換え(前線が動いている間は送らない) // 学ぶ: 危ない所へ進化体(F3 から)
    }

    /**
     * プレイヤー 1 人がいる所を見る(見張り 1 回ぶん)。11-20 ユーザー決定(戦場の霧): 森の視界({@link ForestVision})の中だけ升に点を足す
     * (森の縁 64・偵察の周り 32・地脈の線の地下・フィロセンスの周り)。見えなければ何も覚えない。
     *
     * @return 見えて覚えたか
     */
    public static boolean observe(ServerLevel level, BaseMemory memory, BlockPos pos, double hours, double day) {
        if (!ForestVision.sees(level, pos)) return false;
        BaseMemoryTable t = DesignData.director().base;
        long chunk = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        boolean inRoom = RoomCache.roomAt(level, pos) != null;
        memory.addPresence(chunk, hours * (inRoom ? t.roomFactor : 1.0D), day);
        memory.setStructures(chunk, countStructures(level, pos, t.scanHalfHeight), day);
        return true;
    }

    /**
     * 偵察を出す(区切り 2): 覚えた・偵察済みの拠点で間が空いたら、拠点のまわり(読み込まれて動いている所)の段階 0 から 1 体選んで使いに出す。
     * 拠点に近すぎる個体は選ばない(拠点の外から来させる)。いなければ少し後でまた探す。
     * 段階 0 がいないときに新しく湧かせるか(11-14 ユーザー Q3 = 2)は、どこから来たことにするか(設定)が決まってから足す。
     */
    static void dispatchScouts(ServerLevel level, BaseMemory memory, double day) {
        BaseMemoryTable t = DesignData.director().base;
        for (BaseMemory.Base b : List.copyOf(memory.bases())) {
            if (!BaseMemory.wantsScout(b, day)) continue;
            AbstractParasitizedMonster pick = pickScout(level, b, t, level.getRandom());
            if (pick == null) pick = spawnScoutFromForest(level, b, t, level.getRandom());
            if (pick == null) {
                memory.scoutNotFound(b, day);
                continue;
            }
            pick.fate().scout().begin(b);
            com.yayuy.overgrownvegetation.spawn.EnemySpawns.release(pick); // 縁・地下の印を外す(縁の上限・片付けから外す。見直し ②4)
            memory.scoutSent(b, day, level.getRandom(), !DirectorGates.open(level, DirectorGates.Gate.RECOGNIZE)); // F4 は 1〜2 日おき
            ForestDirector.get(level).note(String.format(java.util.Locale.ROOT,
                    "%s → 偵察(拠点 #%d へ)", pick.species().key(), b.id()));
        }
    }

    @Nullable
    static AbstractParasitizedMonster pickScout(ServerLevel level, BaseMemory.Base b, BaseMemoryTable t, RandomSource random) {
        double r = t.scoutSearchBlocks;
        AABB box = new AABB(b.centerX() - r, level.getMinBuildHeight(), b.centerZ() - r, b.centerX() + r, level.getMaxBuildHeight(), b.centerZ() + r);
        List<AbstractParasitizedMonster> ok = new ArrayList<>();
        for (AbstractParasitizedMonster m : level.getEntitiesOfClass(AbstractParasitizedMonster.class, box)) {
            if (!canScout(m)) continue;
            double dx = m.getX() - b.centerX(), dz = m.getZ() - b.centerZ();
            double d2 = dx * dx + dz * dz;
            if (d2 > r * r || d2 < t.scoutFromMinBlocks * t.scoutFromMinBlocks) continue;
            if (!level.isPositionEntityTicking(m.blockPosition())) continue;
            ok.add(m);
        }
        return ok.isEmpty() ? null : ok.get(random.nextInt(ok.size()));
    }

    /**
     * 拠点のまわりに出せる段階 0 がいないとき、森のある方角から偵察を 1 体湧かせる(11-14 ユーザー決定 Q3 = 2「森のある方向から」)。
     * <ul>
     *   <li>森(過繁茂のチャンク)が拠点のまわり {@code forestSearchChunks} に見当たらなければ湧かせない(まだ汚染の無い世界では偵察は来ない)。</li>
     *   <li>出す所: 拠点の真ん中から森の方角へ {@code scoutSpawnMinBlocks}〜{@code scoutSpawnMaxBlocks}(森がそれより近ければ森の縁まで)。
     *   向きを ±{@code scoutSpawnSpreadDeg} の中で振って {@code scoutSpawnTries} か所試す。</li>
     *   <li>プレイヤーの目の前に湧いて見えないように: どのプレイヤーからも {@code scoutSpawnPlayerMinBlocks} より遠く、目から見通せない所
     *   (間にブロックがある)だけ。地面は高さ地図(葉を除く)の上で、足元が固く、水・溶岩でない所。動いているチャンク(読み込まれて動く範囲)だけ。</li>
     *   <li>値段 {@code scoutSpawnCost} を森の予算から払う。足りなければ湧かせない。</li>
     * </ul>
     */
    @Nullable
    static AbstractParasitizedMonster spawnScoutFromForest(ServerLevel level, BaseMemory.Base b, BaseMemoryTable t, RandomSource random) {
        if (!t.scoutSpawnEnabled) return null;
        net.minecraft.world.phys.Vec3 center = new net.minecraft.world.phys.Vec3(b.centerX(), 64.0D, b.centerZ());
        ForestEdge forest = ForestEdge.of(level);
        net.minecraft.world.phys.Vec3 away = forest.away(center);
        if (away == null) return null;
        double forestDist = forest.distance(center);
        double baseAngle = Math.atan2(-away.z, -away.x); // 森のある方角
        for (int i = 0; i < t.scoutSpawnTries; i++) {
            double ang = baseAngle + Math.toRadians((random.nextDouble() * 2.0D - 1.0D) * t.scoutSpawnSpreadDeg);
            double dist = t.scoutSpawnMinBlocks + random.nextDouble() * (t.scoutSpawnMaxBlocks - t.scoutSpawnMinBlocks);
            if (forestDist > 0.0D && Double.isFinite(forestDist)) dist = Math.min(dist, Math.max(t.scoutFromMinBlocks, forestDist));
            int x = net.minecraft.util.Mth.floor(b.centerX() + Math.cos(ang) * dist);
            int z = net.minecraft.util.Mth.floor(b.centerZ() + Math.sin(ang) * dist);
            BlockPos column = new BlockPos(x, 0, z);
            if (!level.isPositionEntityTicking(column)) continue;
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            if (!level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)) continue;
            if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty()) continue;
            if (seenByPlayer(level, feet, t.scoutSpawnPlayerMinBlocks)) continue;
            com.yayuy.overgrownvegetation.corpse.G1Species s = com.yayuy.overgrownvegetation.spawn.DirectorHooks.pickG1(random); // 重みは spawns.json(11-16 見直し: 1 か所に)
            var type = s.entityType();
            AbstractParasitizedMonster m = type.create(level);
            if (m == null) continue;
            m.moveTo(x + 0.5D, y, z + 0.5D, random.nextFloat() * 360.0F, 0.0F);
            if (!level.noCollision(m)) {
                m.discard();
                continue;
            }
            if (!ForestDirector.get(level).tryPay(level, t.scoutSpawnCost)) {
                m.discard();
                return null;
            }
            level.addFreshEntity(m);
            ForestDirector.get(level).note(String.format(java.util.Locale.ROOT,
                    "%s を森の方角(%d, %d, %d)から湧かせた(偵察。予算 %.0f)", s.key(), x, y, z, t.scoutSpawnCost));
            return m;
        }
        return null;
    }

    /** どれかのプレイヤーの近く、または目から見通せる所か(湧かせてはいけない所)。 */
    static boolean seenByPlayer(ServerLevel level, BlockPos feet, double minBlocks) {
        net.minecraft.world.phys.Vec3 head = net.minecraft.world.phys.Vec3.atBottomCenterOf(feet).add(0.0D, 1.6D, 0.0D);
        net.minecraft.world.phys.Vec3 foot = net.minecraft.world.phys.Vec3.atBottomCenterOf(feet).add(0.0D, 0.3D, 0.0D);
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            if (p.distanceToSqr(head) < minBlocks * minBlocks) return true;
            net.minecraft.world.phys.Vec3 eye = p.getEyePosition();
            if (eye.distanceToSqr(head) > 256.0D * 256.0D) continue;
            for (net.minecraft.world.phys.Vec3 to : new net.minecraft.world.phys.Vec3[]{head, foot}) {
                var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, to, net.minecraft.world.level.ClipContext.Block.VISUAL,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, p));
                if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return true;
            }
        }
        return false;
    }

    /** 偵察に出せる段階 0 か(道が決まっていない・使いの途中でない・起き上がりや前兆の途中でない・戦っていない)。 */
    public static boolean canScout(AbstractParasitizedMonster m) {
        G1Fate f = m.fate();
        return m.isAlive() && f != null && f.route() == ForestDirector.Route.NONE && !f.scout().active() && !m.isRising()
                && m.omen() == G1Fate.OMEN_NONE && !m.isPassenger() && !G1Fate.inCombat(m);
    }

    /** 偵察が倒された: プレイヤー(か、プレイヤーの飛び道具)に倒されたら、拠点の認識が進む。 */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof AbstractParasitizedMonster m) || !(m.level() instanceof ServerLevel level)) return;
        G1Fate f = m.fate();
        if (f == null || !f.scout().active()) return;
        if (event.getSource().getEntity() instanceof net.minecraft.world.entity.player.Player) {
            f.scout().onKilledByPlayer(level);
        } else {
            if (f.scout().exploring()) ScoutSearch.get(level.getServer()).forgetBody(m.getUUID());
            f.scout().cancel();
        }
    }

    /** 升(足元のチャンク)の、足元から上下 {@code halfHeight} の中の置き物の数。ベッドは足の側だけ数える(2 マスで 1 つ)。 */
    public static int countStructures(ServerLevel level, BlockPos feet, int halfHeight) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(feet.getX() >> 4, feet.getZ() >> 4);
        if (chunk == null) return 0;
        int minY = Math.max(level.getMinBuildHeight(), feet.getY() - halfHeight);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, feet.getY() + halfHeight);
        int n = 0;
        LevelChunkSection[] sections = chunk.getSections();
        for (int si = chunk.getSectionIndex(minY); si <= chunk.getSectionIndex(maxY); si++) {
            if (si < 0 || si >= sections.length) continue;
            LevelChunkSection section = sections[si];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> s.is(ModTags.BASE_STRUCTURE))) continue;
            int baseY = chunk.getSectionYFromSectionIndex(si) << 4;
            for (int y = Math.max(0, minY - baseY); y <= Math.min(15, maxY - baseY); y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState s = section.getBlockState(x, y, z);
                        if (!s.is(ModTags.BASE_STRUCTURE)) continue;
                        if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD) continue;
                        n++;
                    }
                }
            }
        }
        return n;
    }
}
