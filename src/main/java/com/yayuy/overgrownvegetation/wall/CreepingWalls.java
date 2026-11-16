package com.yayuy.overgrownvegetation.wall;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.CreepingWallPayload;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 動いている蠢く壁をまとめて進める所(2026-11-16 試作)。世界の tick の終わりに:
 * <ol>
 *     <li>各壁を進める({@link CreepingWall#tick})</li>
 *     <li>埋まった升に入った生き物を飲む(プレイヤーは成れの果て + 即死、Mob は消えて時間 +1 秒)</li>
 *     <li>新しく埋まった先頭の升を、近くのプレイヤーへ送る</li>
 *     <li>跡を置く(全部の壁で 1 tick に {@link WallTuning#trailPerTick} まで)</li>
 * </ol>
 * 保存しない。サーバーを止めると全部消える(その場で止まった扱い)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CreepingWalls {

    private static final Map<ResourceKey<Level>, List<CreepingWall>> WALLS = new HashMap<>();
    private static int nextId = 1;

    /** 測った値(コマンドの status で出す)。 */
    static long sentBytesTotal;
    static long sentBytesWindow;
    static long windowStart;
    static double sentBytesPerSecond;
    static long maxSentBytesPerSecond;
    static long lastTrailNanos, maxTrailNanos, lastSwallowNanos, maxSwallowNanos;

    private CreepingWalls() {
    }

    public static CreepingWall start(ServerLevel level, BlockPos origin) {
        CreepingWall wall = new CreepingWall(nextId++, level, origin, level.getGameTime());
        WALLS.computeIfAbsent(level.dimension(), k -> new ArrayList<>()).add(wall);
        return wall;
    }

    /** 前触れから起こす(自然に起きるとき・/ov wall start)。近くの人へ「音を止める」を送る。 */
    public static CreepingWall startWithOmen(ServerLevel level, BlockPos origin) {
        CreepingWall wall = start(level, origin);
        wall.beginOmen(level.getGameTime());
        int quiet = WallTuning.omenTicks + WallTuning.baseDurationTicks + WallTuning.maxExtensionTicks;
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(origin) <= 160 * 160) {
                sendTo(p, new CreepingWallPayload(wall.id, CreepingWallPayload.OMEN, origin, new int[]{quiet},
                        (float) (WallTuning.speedBlocksPerSecond / 20.0D)));
            }
        }
        return wall;
    }

    /** 殴られた(画面の側から)。升が埋まっていて、手の届く所(6 ブロック以内)ならひるませる。火ならもっとひるむ。 */
    public static void onHit(ServerPlayer player, BlockPos cell) {
        ServerLevel level = player.serverLevel();
        if (player.isSpectator() || player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(cell)) > 36.0D) {
            return;
        }
        for (CreepingWall w : walls(level)) {
            if (w.isStopped() || !w.isFilled(cell)) {
                continue;
            }
            net.minecraft.world.item.ItemStack held = player.getMainHandItem();
            boolean fire = held.is(net.minecraft.world.item.Items.FLINT_AND_STEEL) || held.is(net.minecraft.world.item.Items.FIRE_CHARGE)
                    || held.is(net.minecraft.world.item.Items.TORCH) || held.is(net.minecraft.world.item.Items.SOUL_TORCH)
                    || net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                            level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                                    .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.FIRE_ASPECT), held) > 0;
            if (w.flinch(level.getGameTime(), fire ? WallTuning.flinchFireTicks : WallTuning.flinchTicks)) {
                // 削れる(粒と音だけ。倒せない)
                level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,
                                net.minecraft.world.level.block.Blocks.MOSS_BLOCK.defaultBlockState()),
                        cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5, 24, 0.4, 0.4, 0.4, 0.1);
                if (fire) {
                    level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME, cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.02);
                }
                level.playSound(null, cell, net.minecraft.sounds.SoundEvents.ROOTED_DIRT_BREAK, net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 0.6F);
            }
            return;
        }
    }

    public static List<CreepingWall> walls(ServerLevel level) {
        return WALLS.getOrDefault(level.dimension(), List.of());
    }

    /** 全部止める(跡は置き続ける)。止めた数。 */
    public static int stopAll(ServerLevel level) {
        int n = 0;
        for (CreepingWall w : walls(level)) {
            if (!w.isStopped()) {
                w.stop(level.getGameTime());
                n++;
            }
        }
        return n;
    }

    /** テスト用: 壁を跡ごと捨てる。 */
    public static void discard(CreepingWall wall) {
        List<CreepingWall> list = WALLS.get(wall.level.dimension());
        if (list != null) {
            list.remove(wall);
        }
    }

    /**
     * 埋まった升の中には Mob を湧かせない(2026-11-16 計測で見つけた: 大きな洞窟では塊の中の暗い空洞に Mob が次々と湧き、
     * それを飲んで 2 分で 4236 体・止まるまでが 70 分を超えた)。湧きの場所の判定で断る(自然湧き・スポナー)。
     */
    @SubscribeEvent
    public static void onSpawnCheck(net.neoforged.neoforge.event.entity.living.MobSpawnEvent.PositionCheck event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<CreepingWall> list = WALLS.get(level.dimension());
        if (list == null || list.isEmpty()) {
            return;
        }
        long p = BlockPos.asLong(Mth.floor(event.getX()), Mth.floor(event.getY()), Mth.floor(event.getZ()));
        for (CreepingWall w : list) {
            if (!w.isStopped() && w.filled.contains(p)) {
                event.setResult(net.neoforged.neoforge.event.entity.living.MobSpawnEvent.PositionCheck.Result.FAIL);
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        WALLS.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getGameTime();
        if (now % 1200 == 0) {
            WallSporeTrail.prune(level);
        }
        List<CreepingWall> list = WALLS.get(level.dimension());
        if (list == null || list.isEmpty()) {
            return;
        }
        for (CreepingWall w : new ArrayList<>(list)) {
            boolean wasStopped = w.isStopped();
            w.tick(now);
            if (!w.isStopped()) {
                long t0 = System.nanoTime();
                swallow(level, w);
                lastSwallowNanos = System.nanoTime() - t0;
                maxSwallowNanos = Math.max(maxSwallowNanos, lastSwallowNanos);
            }
            send(level, w, now, !wasStopped && w.isStopped());
        }
        long t0 = System.nanoTime();
        int budget = WallTuning.trailPerTick;
        for (CreepingWall w : list) {
            if (budget <= 0) {
                break;
            }
            budget -= w.placeTrail(now, budget);
        }
        lastTrailNanos = System.nanoTime() - t0;
        maxTrailNanos = Math.max(maxTrailNanos, lastTrailNanos);
        list.removeIf(w -> {
            if (w.isFinished()) {
                broadcast(level, w, new CreepingWallPayload(w.id, CreepingWallPayload.CLEAR, w.origin, new int[0], 0.0F));
                return true;
            }
            return false;
        });
        if (now - windowStart >= 20) {
            sentBytesPerSecond = sentBytesWindow * 20.0D / Math.max(1, now - windowStart);
            maxSentBytesPerSecond = Math.max(maxSentBytesPerSecond, (long) sentBytesPerSecond);
            sentBytesWindow = 0;
            windowStart = now;
        }
    }

    // ------------------------------------------------------------------ 飲み込み

    /** 埋まった升に体が入った生き物を飲む。 */
    static void swallow(ServerLevel level, CreepingWall w) {
        if (w.filled.isEmpty()) {
            return;
        }
        AABB bounds = new AABB(w.minX - 1, w.minY - 1, w.minZ - 1, w.maxX + 2, w.maxY + 2, w.maxZ + 2);
        List<LivingEntity> hit = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof LivingEntity living) || !living.isAlive() || !bounds.intersects(e.getBoundingBox())) {
                continue;
            }
            if (e instanceof ServerPlayer p && (p.isCreative() || p.isSpectator())) {
                continue;
            }
            if (!(e instanceof ServerPlayer) && (!(e instanceof Mob) || e instanceof EnderDragon || e instanceof WitherBoss)) {
                continue;
            }
            if (touches(w, e.getBoundingBox())) {
                hit.add(living);
            }
        }
        for (LivingEntity e : hit) {
            if (e instanceof ServerPlayer p) {
                WallRemains.consume(level, p);
            } else {
                e.discard(); // 飲まれて壁の一部になる(落とさない)
                w.swallowOne();
            }
        }
    }

    /** 1 体を見て、埋まった升に入っていれば飲む(世界の一覧に入らない偽プレイヤーのテスト用)。飲んだら true。 */
    public static boolean swallowIfInside(ServerLevel level, CreepingWall w, LivingEntity e) {
        if (!e.isAlive() || !touches(w, e.getBoundingBox())) {
            return false;
        }
        if (e instanceof ServerPlayer p) {
            WallRemains.consume(level, p);
        } else {
            e.discard();
            w.swallowOne();
        }
        return true;
    }

    /** 体の箱が埋まった升に入っているか(少し内側へ縮めて、面に触れただけは数えない)。 */
    static boolean touches(CreepingWall w, AABB box) {
        AABB b = box.deflate(0.05D);
        int x0 = Mth.floor(b.minX), x1 = Mth.floor(b.maxX);
        int y0 = Mth.floor(b.minY), y1 = Mth.floor(b.maxY);
        int z0 = Mth.floor(b.minZ), z1 = Mth.floor(b.maxZ);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (w.filled.contains(BlockPos.asLong(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 送る

    private static void send(ServerLevel level, CreepingWall w, long now, boolean justStopped) {
        boolean snapshot = (now - w.startTick) % WallTuning.snapshotIntervalTicks == 0;
        int r = WallTuning.sendRadius;
        float bpt = (float) (WallTuning.speedBlocksPerSecond / 20.0D);
        for (ServerPlayer player : level.players()) {
            BlockPos base = player.blockPosition();
            if (base.getX() < w.minX - r || base.getX() > w.maxX + r || base.getY() < w.minY - r || base.getY() > w.maxY + r
                    || base.getZ() < w.minZ - r || base.getZ() > w.maxZ + r) {
                continue;
            }
            if (!w.newlySettled.isEmpty()) {
                int[] cells = near(base, w.newlySettled.iterator(), r);
                if (cells.length > 0) {
                    sendTo(player, new CreepingWallPayload(w.id, CreepingWallPayload.ADD, base, cells, bpt));
                    if (now % 15 == 0) {
                        roar(level, player, base, cells, now);
                    }
                }
            }
            if (snapshot && !w.isStopped() && !w.front.isEmpty()) {
                int[] cells = near(base, w.front.iterator(), r);
                if (cells.length > 0) {
                    sendTo(player, new CreepingWallPayload(w.id, CreepingWallPayload.SNAPSHOT, base, cells, bpt));
                }
            }
            if (justStopped) {
                sendTo(player, new CreepingWallPayload(w.id, CreepingWallPayload.STOP, base, new int[0], bpt));
            }
        }
        w.newlySettled.clear();
    }

    /**
     * 進むときの音(決定 その 3「濁流のような擦れる轟音 / 根が軋む音」)。今は仮にバニラの音を低くして、いちばん近い新しい先頭の升で鳴らす
     * (擦れる轟音 = ウォーデンが地面を掘る音、軋み = 根付いた土の音)。くぐもった声・胞子嚢が弾ける音は、本当の音と一緒に後で。
     */
    private static void roar(ServerLevel level, ServerPlayer player, BlockPos base, int[] cells, long now) {
        int best = -1, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < cells.length; i++) {
            int c = cells[i];
            int d = CreepingWallPayload.dx(c) * CreepingWallPayload.dx(c) + CreepingWallPayload.dy(c) * CreepingWallPayload.dy(c)
                    + CreepingWallPayload.dz(c) * CreepingWallPayload.dz(c);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        if (best < 0 || bestD > 48 * 48) {
            return;
        }
        double x = base.getX() + CreepingWallPayload.dx(best) + 0.5, y = base.getY() + CreepingWallPayload.dy(best) + 0.5,
                z = base.getZ() + CreepingWallPayload.dz(best) + 0.5;
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(net.minecraft.sounds.SoundEvents.WARDEN_DIG),
                net.minecraft.sounds.SoundSource.HOSTILE, x, y, z, 1.6F, 0.45F + level.random.nextFloat() * 0.1F, level.random.nextLong()));
        if ((now / 15) % 3 == 0) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                    net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(net.minecraft.sounds.SoundEvents.ROOTED_DIRT_BREAK),
                    net.minecraft.sounds.SoundSource.HOSTILE, x, y, z, 1.2F, 0.5F, level.random.nextLong()));
        }
    }

    /** {@code base} から r 以内の升(近い順に {@link WallTuning#sendCap} まで)。 */
    private static int[] near(BlockPos base, LongIterator it, int r) {
        LongArrayList picked = new LongArrayList();
        IntArrayList dist = new IntArrayList();
        int r2 = r * r;
        while (it.hasNext()) {
            long p = it.nextLong();
            int dx = BlockPos.getX(p) - base.getX(), dy = BlockPos.getY(p) - base.getY(), dz = BlockPos.getZ(p) - base.getZ();
            int d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= r2) {
                picked.add(p);
                dist.add(d2);
            }
        }
        int n = picked.size();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        if (n > WallTuning.sendCap) {
            java.util.Arrays.sort(order, (a, b) -> Integer.compare(dist.getInt(a), dist.getInt(b)));
            n = WallTuning.sendCap;
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            long p = picked.getLong(order[i]);
            out[i] = CreepingWallPayload.pack(BlockPos.getX(p) - base.getX(), BlockPos.getY(p) - base.getY(), BlockPos.getZ(p) - base.getZ());
        }
        return out;
    }

    private static void sendTo(ServerPlayer player, CreepingWallPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
        sentBytesTotal += payload.approxBytes();
        sentBytesWindow += payload.approxBytes();
    }

    private static void broadcast(ServerLevel level, CreepingWall w, CreepingWallPayload payload) {
        for (ServerPlayer p : level.players()) {
            sendTo(p, payload);
        }
    }
}
