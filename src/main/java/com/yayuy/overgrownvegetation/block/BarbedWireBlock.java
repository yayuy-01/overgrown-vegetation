package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.td.TdSiege;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 有刺鉄線(2026-09-19)。当たり判定が無く、踏み込んだ生き物(プレイヤーも Mob も)を
 * {@code defense.json} の {@code barbedWire.slowMultiplier} 倍に減速させ、
 * {@code damageIntervalTicks} ごとに {@code damage} を与える。クモの巣と同じ「入れるが抜けにくい」作り。
 * TD の侵攻中は、踏み込んだアルボラが切る({@code td/TdSiege#onWireContact}、2026-09-25)。
 * 樹液結晶 + 鉄 + クロロフィライトで作る。
 */
public class BarbedWireBlock extends Block {

    public static final MapCodec<BarbedWireBlock> CODEC = simpleCodec(BarbedWireBlock::new);

    private static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 12.0D, 16.0D);
    private static final double VERTICAL_MULTIPLIER = 0.75D;

    public BarbedWireBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        DefenseTable table = DesignData.defense();
        double slow = table.wireSlowMultiplier();
        if (slow < 1.0D) {
            entity.makeStuckInBlock(state, new Vec3(slow, VERTICAL_MULTIPLIER, slow));
        }
        // 2026-09-24 軽量化(監査 1208): ダメージの tick を個体ごとに entity id でずらす。前は全員が同じ tick
        // (時刻 % 間隔 == 0)に hurt されて、鉄線に大勢かかると 1 tick に集中していた。間隔と 1 回の量は同じ。
        if (level instanceof ServerLevel serverLevel && table.wireDamage() > 0.0D
                && Math.floorMod(level.getGameTime() + entity.getId(), (long) table.wireDamageIntervalTicks()) == 0L) {
            living.hurt(ModDamageTypes.barbedWire(serverLevel), (float) table.wireDamage());
        }
        // TD の侵攻中は、踏み込んだアルボラが鉄線を切る(2026-09-25 ユーザー指定「アルボラが他の mob が通れるよう有刺鉄線を破壊する」)。
        if (level instanceof ServerLevel serverLevel && entity instanceof Mob mob) {
            TdSiege.onWireContact(serverLevel, mob, pos);
        }
    }
}
