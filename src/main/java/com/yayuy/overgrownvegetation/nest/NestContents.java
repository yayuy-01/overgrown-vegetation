package com.yayuy.overgrownvegetation.nest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.ArboraStoragePodBlockEntity;
import com.yayuy.overgrownvegetation.block.SporeNodeBlockEntity;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NestTable;
import com.yayuy.overgrownvegetation.entity.ArboraEntity;
import com.yayuy.overgrownvegetation.entity.ArboraQueenEntity;
import com.yayuy.overgrownvegetation.entity.NestCoreEntity;
import com.yayuy.overgrownvegetation.entity.OvergrownShielderEntity;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 掘り終わった巣に<b>中身</b>を置く(2026-09-20)。{@link NestGrowthQueue} が掘り終えた直後に
 * 1 回だけ呼ばれる。
 *
 * <h2>側室ごとの中身</h2>
 * <table>
 *   <tr><td>孵化室</td><td>アルボラの卵 {@code eggsPerHatchery} 個。孵る個体は必ず亜種</td></tr>
 *   <tr><td>衛兵室</td><td>シールダー 1 + アルボラ 4(どれも能力 {@code guardBuff} 倍)</td></tr>
 *   <tr><td>噴気室</td><td>胞子ノード 1 個。地上へ噴出孔を最大 {@code ventMaxCount} 本伸ばす</td></tr>
 *   <tr><td>貯蔵室</td><td>アルボラの貯蔵胞 {@code storagePodsPerRoom} 個(戦利品入り)</td></tr>
 *   <tr><td>根の間</td><td>巨大樹の根が壁を貫き、クロロフィライトと樹液結晶の鉱脈が露出する</td></tr>
 *   <tr><td>女王室</td><td>女王 1 体と、その奥の巣の核</td></tr>
 * </table>
 *
 * <p>置く場所は<b>掘った空洞の中から探し直す</b> —— 設計の座標は球の中心でしかなく、
 * 実際にそこが床とはかぎらない(鉱石や player の建築物を避けて掘り残した場所があるため)。
 */
public final class NestContents {

    /** 貯蔵胞に入る戦利品。 */
    private static final ResourceKey<LootTable> POD_LOOT =
            ResourceKey.create(Registries.LOOT_TABLE, OvergrownVegetation.id("chests/arbora_storage_pod"));

    /** ブロックを置くときの設置フラグ(掘るときと同じで、近隣更新を抑える)。 */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    public static void populate(ServerLevel level, NestLayout.Plan plan) {
        NestTable t = DesignData.nest();
        RandomSource random = level.random;
        BlockPos sporeNode = null;

        for (NestLayout.Chamber chamber : plan.chambers) {
            switch (chamber.type()) {
                case HATCHERY -> fillHatchery(level, chamber, t, random);
                case GUARD -> fillGuard(level, chamber, t, random, plan.origin);
                case VENT -> sporeNode = fillVent(level, chamber, plan, random);
                case STORAGE -> fillStorage(level, chamber, t, random);
                case ROOT -> fillRoot(level, chamber, random);
                case QUEEN -> fillQueen(level, chamber, random);
            }
        }
        placeCore(level, plan, sporeNode);
    }

    // --- 孵化室 ---

    private static void fillHatchery(ServerLevel level, NestLayout.Chamber chamber, NestTable t, RandomSource random) {
        List<BlockPos> floor = floorSpots(level, chamber);
        Collections.shuffle(floor, new java.util.Random(random.nextLong()));
        BlockState egg = ModBlocks.ARBORA_EGG.get().defaultBlockState();
        int placed = 0;
        for (BlockPos pos : floor) {
            if (placed >= t.eggsPerHatchery()) {
                break;
            }
            if (!egg.canSurvive(level, pos)) {
                continue;
            }
            level.setBlock(pos, egg, FLAGS);
            placed++;
        }
    }

    // --- 衛兵室 ---

    private static void fillGuard(ServerLevel level, NestLayout.Chamber chamber, NestTable t, RandomSource random,
                                  BlockPos home) {
        List<BlockPos> floor = floorSpots(level, chamber);
        if (floor.isEmpty()) {
            return;
        }
        for (int i = 0; i < t.guardShielders(); i++) {
            OvergrownShielderEntity shielder = ModEntities.OVERGROWN_SHIELDER.get().create(level);
            spawnGuard(level, shielder, floor.get(random.nextInt(floor.size())), t.guardBuff(), home);
        }
        for (int i = 0; i < t.guardArbora(); i++) {
            ArboraEntity arbora = ModEntities.ARBORA.get().create(level);
            spawnGuard(level, arbora, floor.get(random.nextInt(floor.size())), t.guardBuff(), home);
        }
    }

    /**
     * 衛兵として湧かせる。能力を {@code buff} 倍にし、<b>巣の住人</b>にする ——
     * 住人は巣から {@code guardLeashRadius} より外の相手を狙わないので、
     * 外の敵に釣られて巣を空にしない(ユーザー指定 2026-09-20)。
     */
    private static void spawnGuard(ServerLevel level, Mob mob, BlockPos pos, double buff, BlockPos home) {
        if (mob == null) {
            return;
        }
        if (mob instanceof com.yayuy.overgrownvegetation.entity.AbstractOvergrownInvaderEntity invader) {
            invader.setNestHome(home);
            invader.setNestRole(com.yayuy.overgrownvegetation.entity.NestRole.RESIDENT);
        } else if (mob instanceof OvergrownShielderEntity shielder) {
            shielder.setNestHome(home);               // スカトゥリウムも巣の中では外の敵に釣られない
        }
        mob.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.STRUCTURE, null);
        mob.setPersistenceRequired();
        multiply(mob, Attributes.MAX_HEALTH, buff, "guard_health");
        multiply(mob, Attributes.ARMOR, buff, "guard_armor");
        multiply(mob, Attributes.ATTACK_DAMAGE, buff, "guard_attack");
        mob.setHealth(mob.getMaxHealth());
        level.addFreshEntity(mob);
    }

    private static void multiply(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                 double buff, String name) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.addPermanentModifier(new AttributeModifier(OvergrownVegetation.id(name), buff - 1.0D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    // --- 噴気室 ---

    private static BlockPos fillVent(ServerLevel level, NestLayout.Chamber chamber, NestLayout.Plan plan,
                                     RandomSource random) {
        List<BlockPos> floor = floorSpots(level, chamber);
        BlockPos at = floor.isEmpty() ? fallbackSpot(level, chamber) : nearestTo(floor, chamber.pos());
        if (at == null) {
            OvergrownVegetation.LOGGER.warn("巣の噴気室に胞子ノードを置けなかった: {}", chamber.pos());
            return null;
        }
        level.setBlock(at, ModBlocks.SPORE_NODE.get().defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(at) instanceof SporeNodeBlockEntity node) {
            node.setVentSites(plan.surfaceVents);
        }
        return at;
    }


    // --- 貯蔵室 ---

    private static void fillStorage(ServerLevel level, NestLayout.Chamber chamber, NestTable t, RandomSource random) {
        List<BlockPos> floor = floorSpots(level, chamber);
        Collections.shuffle(floor, new java.util.Random(random.nextLong()));
        BlockState pod = ModBlocks.ARBORA_STORAGE_POD.get().defaultBlockState();
        int placed = 0;
        for (BlockPos pos : floor) {
            if (placed >= t.storagePodsPerRoom()) {
                break;
            }
            level.setBlock(pos, pod, Block.UPDATE_ALL);
            if (level.getBlockEntity(pos) instanceof ArboraStoragePodBlockEntity be) {
                be.setLootTable(POD_LOOT, random.nextLong());
            }
            placed++;
        }
    }

    // --- 根の間 ---

    /**
     * 壁に巨大樹の根を貫かせ、鉱脈を露出させる。掘った空洞ではなく<b>壁のマス</b>を置き換えるので、
     * 部屋の形は変わらない。
     */
    private static void fillRoot(ServerLevel level, NestLayout.Chamber chamber, RandomSource random) {
        int r = chamber.radius() + 1;
        BlockPos centre = chamber.pos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockState root = ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();
        BlockState chloro = ModBlocks.CHLOROPHYLLITE_ORE.get().defaultBlockState();
        BlockState sap = ModBlocks.HARDENED_SAP_CRYSTAL_ORE.get().defaultBlockState();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 < (r - 1) * (r - 1) || d2 > r * r) {
                        continue;                       // 壁の殻だけ
                    }
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    if (!level.isLoaded(cursor) || level.getBlockState(cursor).isAir()) {
                        continue;
                    }
                    double roll = random.nextDouble();
                    if (roll < 0.14D) {
                        level.setBlock(cursor.immutable(), root, FLAGS);
                    } else if (roll < 0.20D) {
                        level.setBlock(cursor.immutable(), chloro, FLAGS);
                    } else if (roll < 0.25D) {
                        level.setBlock(cursor.immutable(), sap, FLAGS);
                    }
                }
            }
        }
    }

    // --- 女王室と核 ---

    private static void fillQueen(ServerLevel level, NestLayout.Chamber chamber, RandomSource random) {
        List<BlockPos> floor = floorSpots(level, chamber);
        if (floor.isEmpty()) {
            return;
        }
        BlockPos at = nearestTo(floor, chamber.pos());
        ArboraQueenEntity queen = ModEntities.ARBORA_QUEEN.get().create(level);
        if (queen == null) {
            return;
        }
        queen.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, random.nextFloat() * 360.0F, 0.0F);
        queen.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.STRUCTURE, null);
        queen.setPersistenceRequired();
        level.addFreshEntity(queen);
    }

    private static void placeCore(ServerLevel level, NestLayout.Plan plan, BlockPos sporeNode) {
        if (plan.corePos == null) {
            return;
        }
        NestCoreEntity core = ModEntities.NEST_CORE.get().create(level);
        if (core == null) {
            return;
        }
        core.moveTo(plan.corePos.getX() + 0.5D, plan.corePos.getY() + 0.5D, plan.corePos.getZ() + 0.5D, 0.0F, 0.0F);
        core.finalizeSpawn(level, level.getCurrentDifficultyAt(plan.corePos), MobSpawnType.STRUCTURE, null);
        if (sporeNode != null) {
            core.setSporeNode(sporeNode);
        }
        level.addFreshEntity(core);
    }

    // --- 場所探し ---

    /**
     * 床が 1 マスも見つからなかったときの置き場所。部屋の中心から下へ辿って、
     * 空気で足元が固いマスを探す。ここまで外れるのは部屋が水没している / 掘り残された場合だけ。
     */
    @Nullable
    private static BlockPos fallbackSpot(ServerLevel level, NestLayout.Chamber chamber) {
        BlockPos.MutableBlockPos cursor = chamber.pos().mutable();
        for (int i = 0; i <= chamber.radius() + 2; i++) {
            if (level.isLoaded(cursor) && level.getBlockState(cursor).isAir()
                    && level.getBlockState(cursor.below()).isFaceSturdy(level, cursor.below(), Direction.UP)) {
                return cursor.immutable();
            }
            cursor.move(Direction.DOWN);
        }
        return null;
    }

    /** 部屋の中で「足元が固く、頭上が空いている」マス。中身を置ける床。 */
    private static List<BlockPos> floorSpots(ServerLevel level, NestLayout.Chamber chamber) {
        List<BlockPos> out = new ArrayList<>();
        int r = chamber.radius();
        BlockPos centre = chamber.pos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (dx * dx + dy * dy + dz * dz > r * r) {
                        continue;
                    }
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    if (!level.isLoaded(cursor) || !level.getBlockState(cursor).isAir()) {
                        continue;
                    }
                    if (!level.getBlockState(cursor.above()).isAir()) {
                        continue;                       // 頭上が詰まっている
                    }
                    BlockPos below = cursor.below();
                    if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                        continue;
                    }
                    out.add(cursor.immutable());
                }
            }
        }
        return out;
    }

    private static BlockPos nearestTo(List<BlockPos> spots, BlockPos target) {
        BlockPos best = spots.get(0);
        double bestDist = best.distSqr(target);
        for (BlockPos pos : spots) {
            double d = pos.distSqr(target);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    private NestContents() {
    }
}
