package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.WallRemainsBlock;
import com.yayuy.overgrownvegetation.block.WallRemainsBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import com.yayuy.overgrownvegetation.port.registries.DeferredBlock;
import com.yayuy.overgrownvegetation.port.registries.DeferredHolder;

/**
 * 蠢く壁(2026-11-16 試作)の登録: 成れの果てのブロックとブロックエンティティ・ダメージの型。
 * 共有の登録クラスは並行するセッションが触るので、ここに寄せた(入れ物の DeferredRegister は共有のもの。ModG1 と同じ形)。
 * {@link #init()} を MOD の構築子から呼ぶ。
 */
public final class ModCreepingWall {

    private ModCreepingWall() {
    }

    public static void init() {
    }

    /** 成れの果て。素手でも壊せる(硬さ 1.5【仮】)。爆発には強め。ピストンで動かない。 */
    public static final DeferredBlock<WallRemainsBlock> WALL_REMAINS = ModBlocks.BLOCKS.registerBlock("wall_remains", WallRemainsBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(1.5F, 6.0F)
                    .sound(SoundType.MOSS)
                    .noOcclusion()
                    .pushReaction(PushReaction.BLOCK));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WallRemainsBlockEntity>> WALL_REMAINS_BE =
            ModBlockEntities.BLOCK_ENTITY_TYPES.register("wall_remains", () -> BlockEntityType.Builder.of(
                    WallRemainsBlockEntity::new, WALL_REMAINS.get()).build(null));

    /**
     * 「蠢く壁に飲まれた」。即死(決定 その 2)。防具・耐性・エンチャント・盾・無敵時間・<b>不死のトーテム</b>を貫く
     * (タグ {@code bypasses_invulnerability} など。トーテムを無視するのはユーザー決定 11-16)。クリエイティブ・スペクテイターは呼ぶ側で外す。
     */
    public static final ResourceKey<DamageType> CREEPING_WALL =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("creeping_wall"));

    public static DamageSource creepingWall(ServerLevel level) {
        return level.damageSources().source(CREEPING_WALL);
    }
}
