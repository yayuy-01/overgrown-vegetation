package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NestTable;
import com.yayuy.overgrownvegetation.entity.ArboraEntity;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.variant.MobVariant;
import com.yayuy.overgrownvegetation.variant.MobVariants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * {@link ArboraEggBlock} の中身(2026-09-20)。{@code eggHatchIntervalTicks} を数えて 1 体孵す。
 *
 * <p>ランダムティックではなくブロックエンティティの時計で数えるのは、設計値の
 * 「何 tick で孵る」がそのまま効くようにするため —— ランダムティックは
 * {@code randomTickSpeed} 次第で 10 倍以上ぶれる。
 *
 * <p>周りに既にアルボラが {@code eggMaxNearby} 体いるときは孵さず、時計を半分まで戻して待つ。
 * プレイヤーが放置した孵化室が Mob で溢れないようにするための歯止め。
 */
public class ArboraEggBlockEntity extends BlockEntity {

    /** 周りのアルボラを数える半径。 */
    private static final double CROWD_RADIUS = 10.0D;

    private int age;

    public ArboraEggBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ARBORA_EGG.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ArboraEggBlockEntity egg) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        NestTable t = DesignData.nest();
        egg.age++;
        if (egg.age < t.eggHatchIntervalTicks()) {
            return;
        }
        if (crowded(serverLevel, pos, t)) {
            egg.age = t.eggHatchIntervalTicks() / 2;     // 混んでいる。半分まで戻して待つ
            return;
        }
        egg.age = 0;
        hatch(serverLevel, pos);
    }

    private static boolean crowded(ServerLevel level, BlockPos pos, NestTable t) {
        AABB box = new AABB(pos).inflate(CROWD_RADIUS);
        return level.getEntitiesOfClass(ArboraEntity.class, box).size() >= t.eggMaxNearby();
    }

    /** 1 体孵して卵を消す。孵った個体は<b>必ず亜種</b>(ユーザー指定 2026-09-19)。 */
    private static void hatch(ServerLevel level, BlockPos pos) {
        ArboraEntity arbora = ModEntities.ARBORA.get().create(level);
        if (arbora == null) {
            return;
        }
        arbora.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                level.random.nextFloat() * 360.0F, 0.0F);
        arbora.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.STRUCTURE, null);
        level.addFreshEntity(arbora);
        // finalizeSpawn より後に上書きする。EntityJoinLevelEvent の抽選は通常個体も引きうるので、
        // 孵化ぶんだけは通常個体を除いて引き直す。
        MobVariant variant = MobVariants.rollVariantOnly(level.random, DesignData.variants());
        MobVariants.force(arbora, variant);
        // 孵った個体は巣の住人。外の敵を追って巣を空にしない(ユーザー指定 2026-09-20)。
        com.yayuy.overgrownvegetation.nest.NestData.Nest nest =
                com.yayuy.overgrownvegetation.nest.NestData.get(level).enclosing(pos, 128.0D);
        if (nest != null) {
            arbora.setNestHome(nest.origin());
            arbora.setNestRole(com.yayuy.overgrownvegetation.entity.NestRole.RESIDENT);
        }

        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        level.playSound(null, pos, SoundEvents.TURTLE_EGG_CRACK, SoundSource.BLOCKS, 1.0F, 0.7F);
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR,
                pos.getX() + 0.5D, pos.getY() + 0.4D, pos.getZ() + 0.5D, 12, 0.3D, 0.2D, 0.3D, 0.01D);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Age", age);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        age = tag.getInt("Age");
    }
}
