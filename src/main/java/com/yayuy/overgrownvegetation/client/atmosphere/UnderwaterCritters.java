package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.UnderwaterMoteParticle;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 水中のにぎわいを出す係(2026-11-13、水まわり ⑥。ユーザー「水中がちょっとまだ寂しい」→ 選択)。
 * 同じ日の直し(ユーザー「ドットが浮いているだけに見える」→「ミジンコの群れの数を減らす・形を付ける・水に溶かす・共通の流れ・場所に紐づける」):
 * 色の点を直接描くのをやめ、絵の付いた粒({@link com.yayuy.overgrownvegetation.client.UnderwaterLifeParticles})を、理由のある所にだけ出す。
 * <ul>
 *   <li><b>水生の虫の群れ</b>(設定「生き物は虫・爬虫類」): 水草(海草・昆布・シャジクモ・胞子昆布)のそばにだけ。カメラのまわり {@link #SEARCH}
 *   ブロックで水草を探し、見つかったらその上に {@link #SWARM_SIZE} 匹の群れ。群れの数は汚染の水 {@link #SWARMS_MURKY}・ほか {@link #SWARMS_CLEAR}。</li>
 *   <li><b>沈んでいく葉</b>: 水面の上に木の葉(張り出した枝)がある所だけ、水面のすぐ下から葉のかけらが沈む。</li>
 *   <li><b>胞子の綿</b>: 胞子は空から降るので、汚染の水を中心に、どこからでも少し。</li>
 *   <li><b>泥煙</b>: 水底の砂・土・泥・砂利・粘土の上を、プレイヤーや Mob が動くと泥が舞い上がる({@link com.yayuy.overgrownvegetation.client.MudCloudParticle})。</li>
 * </ul>
 * 数値はすべて仮決め。
 */
final class UnderwaterCritters {

    static final int SWARMS_MURKY = 2;
    static final int SWARMS_CLEAR = 1;
    static final int SWARM_SIZE = 10;
    static final int SEARCH = 8;
    static final int SWARM_TICKS = 600;
    static final float LEAF_TRIES_MURKY = 0.6F;
    static final float LEAF_TRIES_CLEAR = 0.3F;
    static final float FLUFF_MURKY = 0.12F;
    static final float FLUFF_CLEAR = 0.02F;
    static final double MUD_RANGE = 24.0;

    private static final RandomSource RANDOM = RandomSource.create();
    private static final int MAX_SWARMS = 4;
    private static final BlockPos[] SWARM_AT = new BlockPos[MAX_SWARMS];
    private static final long[] SWARM_UNTIL = new long[MAX_SWARMS];
    /** 開発用: 出した数(群れ・葉・綿)。 */
    private static int spawnedSwarms;
    private static int spawnedLeaves;
    private static int spawnedFluff;

    static String describe() {
        int alive = 0;
        for (BlockPos p : SWARM_AT) {
            alive += p != null ? 1 : 0;
        }
        return String.format("水中 群れ %d(出した %d)葉 %d 綿 %d 流れ %.4f %.4f", alive, spawnedSwarms, spawnedLeaves, spawnedFluff, WaterCurrent.x(), WaterCurrent.z());
    }

    static void clear() {
        java.util.Arrays.fill(SWARM_AT, null);
    }

    static void tick(ClientLevel level) {
        if (!VisualQuality.underwater()) {
            clear();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        mud(level, eye);
        if (mc.gameRenderer.getMainCamera().getFluidInCamera() != FogType.WATER) {
            clear();
            return;
        }
        boolean murky = UnderwaterMoteParticle.overgrownWater(level.getBiome(BlockPos.containing(eye)));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        swarms(level, eye, murky ? SWARMS_MURKY : SWARMS_CLEAR, pos);
        if (RANDOM.nextFloat() < (murky ? LEAF_TRIES_MURKY : LEAF_TRIES_CLEAR)) {
            leaf(level, eye, pos);
        }
        if (RANDOM.nextFloat() < (murky ? FLUFF_MURKY : FLUFF_CLEAR)) {
            double x = eye.x + (RANDOM.nextDouble() * 2.0 - 1.0) * 10.0;
            double y = eye.y + (RANDOM.nextDouble() * 2.0 - 1.0) * 5.0;
            double z = eye.z + (RANDOM.nextDouble() * 2.0 - 1.0) * 10.0;
            if (level.getFluidState(pos.set(x, y, z)).is(FluidTags.WATER)) {
                level.addParticle(ModParticles.SPORE_FLUFF.get(), x, y, z, 2.0, 0.0, 0.0);
                spawnedFluff++;
            }
        }
    }

    // ---------------------------------------------------------------- 水生の虫の群れ

    private static void swarms(ClientLevel level, Vec3 eye, int wanted, BlockPos.MutableBlockPos pos) {
        long now = level.getGameTime();
        int alive = 0;
        for (int i = 0; i < MAX_SWARMS; i++) {
            BlockPos at = SWARM_AT[i];
            if (at == null) {
                continue;
            }
            if (now > SWARM_UNTIL[i] || at.distToCenterSqr(eye) > 20.0 * 20.0) {
                SWARM_AT[i] = null;
                continue;
            }
            alive++;
        }
        if (alive >= wanted) {
            return;
        }
        // 1 tick に何か所か試して、水草のそばに群れを置く。
        for (int tries = 0; tries < 6; tries++) {
            int x = Mth.floor(eye.x) + RANDOM.nextInt(SEARCH * 2 + 1) - SEARCH;
            int y = Mth.floor(eye.y) + RANDOM.nextInt(9) - 4;
            int z = Mth.floor(eye.z) + RANDOM.nextInt(SEARCH * 2 + 1) - SEARCH;
            if (!waterPlant(level.getBlockState(pos.set(x, y, z)))) {
                continue;
            }
            if (Math.abs(x + 0.5 - eye.x) < 2.5 && Math.abs(z + 0.5 - eye.z) < 2.5) {
                continue; // すぐ目の前には出さない(出てすぐ逃げる)
            }
            for (int i = 0; i < MAX_SWARMS; i++) {
                if (SWARM_AT[i] == null) {
                    SWARM_AT[i] = new BlockPos(x, y, z);
                    SWARM_UNTIL[i] = now + SWARM_TICKS;
                    spawnedSwarms++;
                    double cx = x + 0.5;
                    double cy = y + 1.0 + RANDOM.nextDouble() * 0.6;
                    double cz = z + 0.5;
                    for (int k = 0; k < SWARM_SIZE; k++) {
                        level.addParticle(ModParticles.WATER_FLEA.get(), cx + (RANDOM.nextDouble() - 0.5), cy, cz + (RANDOM.nextDouble() - 0.5), cx, cy, cz);
                    }
                    return;
                }
            }
            return;
        }
    }

    private static boolean waterPlant(BlockState s) {
        return s.is(Blocks.SEAGRASS) || s.is(Blocks.TALL_SEAGRASS) || s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT)
                || s.is(ModBlocks.CHARA.get()) || s.is(ModBlocks.SPORE_KELP_STALK.get()) || s.is(ModBlocks.SPORE_KELP_FROND.get());
    }

    // ---------------------------------------------------------------- 沈む葉

    /** 水面の上に木の葉がある列を探し、水面のすぐ下から葉のかけらを 1 枚沈める。 */
    private static void leaf(ClientLevel level, Vec3 eye, BlockPos.MutableBlockPos pos) {
        int x = Mth.floor(eye.x) + RANDOM.nextInt(21) - 10;
        int z = Mth.floor(eye.z) + RANDOM.nextInt(21) - 10;
        int y = Mth.floor(eye.y);
        if (!level.getFluidState(pos.set(x, y, z)).is(FluidTags.WATER)) {
            return;
        }
        int surface = Integer.MIN_VALUE;
        for (int i = 1; i <= 24; i++) {
            if (!level.getFluidState(pos.set(x, y + i, z)).is(FluidTags.WATER)) {
                surface = y + i - 1;
                break;
            }
        }
        if (surface == Integer.MIN_VALUE) {
            return;
        }
        boolean leaves = false;
        for (int i = 1; i <= 10 && !leaves; i++) {
            BlockState s = level.getBlockState(pos.set(x, surface + i, z));
            leaves = s.is(BlockTags.LEAVES) || s.is(ModBlocks.OVERGROWN_LEAVES.get());
        }
        if (!leaves) {
            return;
        }
        double kind = RANDOM.nextFloat() < 0.4F ? 1.0 : 0.0;
        spawnedLeaves++;
        level.addParticle(ModParticles.SINKING_LEAF.get(), x + RANDOM.nextDouble(), surface + 0.6 - RANDOM.nextDouble() * 1.5, z + RANDOM.nextDouble(), kind, 0.0, 0.0);
    }

    // ---------------------------------------------------------------- 泥煙

    /** 泥煙: カメラのまわりの、水底の柔らかい地面の上を動いているプレイヤー・Mob の足元から泥を舞い上げる。 */
    private static void mud(ClientLevel level, Vec3 cam) {
        AABB box = new AABB(cam.x - MUD_RANGE, cam.y - MUD_RANGE, cam.z - MUD_RANGE, cam.x + MUD_RANGE, cam.y + MUD_RANGE, cam.z + MUD_RANGE);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isInWater)) {
            double speed = Math.hypot(e.getX() - e.xo, e.getZ() - e.zo) + Math.max(0.0, e.yo - e.getY()) * 0.5;
            if (speed < 0.02) {
                continue;
            }
            int fy = Mth.floor(e.getY() - 0.05);
            BlockState below = level.getBlockState(pos.set(Mth.floor(e.getX()), fy, Mth.floor(e.getZ())));
            if (e.getY() - (fy + 1) > 0.6 || !softBottom(below) || !level.getFluidState(pos.move(0, 1, 0)).is(FluidTags.WATER)) {
                continue;
            }
            if (RANDOM.nextFloat() < Math.min(0.9, speed * 5.0)) {
                double w = e.getBbWidth() * 0.5;
                level.addParticle(ModParticles.MUD_CLOUD.get(), e.getX() + (RANDOM.nextDouble() * 2.0 - 1.0) * w, fy + 1.05,
                        e.getZ() + (RANDOM.nextDouble() * 2.0 - 1.0) * w, (RANDOM.nextDouble() - 0.5) * 0.02, 0.01 + RANDOM.nextDouble() * 0.01,
                        (RANDOM.nextDouble() - 0.5) * 0.02);
            }
        }
    }

    private static boolean softBottom(BlockState s) {
        return s.is(BlockTags.SAND) || s.is(BlockTags.DIRT) || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.MUD) || s.is(Blocks.SOUL_SAND);
    }

    private UnderwaterCritters() {
    }
}
