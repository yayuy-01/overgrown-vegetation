package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.defense.MachineBattery;
import com.yayuy.overgrownvegetation.mixin.ServerGamePacketListenerFloatingAccessor;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 上昇気流機の中身(2026-10-22、地脈技術の 2 段目。送風機から作り替え。ユーザー指定)。
 * <ul>
 *     <li>気流の柱: 機械の真上の 1 マス幅の列、高さ {@link #HEIGHT}(仮決め 32)。間に<b>まるごと詰まったブロック</b>(当たり判定が 1 マスいっぱい)が
 *     あればその下まで(天井で止まる)。</li>
 *     <li>柱の中のエンティティ・落ちている物は、上への速さを毎 tick 少なくとも {@link #LIFT}(仮決め 0.1)にする = 歩いているプレイヤーも緩く浮いて昇る昇降機。
 *     落下の距離は消す(柱の中で落ちても怪我をしない)。スニークしている間は持ち上げず、ゆっくり下りる({@link #SINK}。仮決め)。
 *     クリエイティブで飛んでいるプレイヤー・観戦者は動かさない。</li>
 *     <li>プレイヤーの動きはクライアントが持つので、プレイヤーは手元のクライアントで持ち上げる({@code client/LeyUpdraftClient})。
 *     ほかの物はサーバーで持ち上げる。サーバーは柱の中のプレイヤーの落下の距離と「浮きすぎ」の数(専用サーバーは 80 tick 浮くと切断する)を毎 tick 戻す。</li>
 *     <li>柱の判定 {@link #updraftBelow}・{@link #isInColumn} は位置だけで答える(後で作るグライダーがこの柱で持ち上がる想定。ユーザー指定)。</li>
 *     <li>電池は働いている間 1 tick に 1 減る(満タンで 1 時間。機械の電池の決まり)。</li>
 * </ul>
 */
public class LeyUpdraftBlockEntity extends BlockEntity implements MachineBattery.Holder {

    /** 気流の柱の高さ(マス。仮決め 32)。 */
    public static final int HEIGHT = 32;
    /** 柱の中で保つ上への速さ(ブロック/tick。仮決め 0.1)。 */
    public static final double LIFT = 0.1D;
    /** スニークして下りるときの速さの下限(ブロック/tick。仮決め)。 */
    public static final double SINK = -0.15D;

    private int charge;
    private boolean hasBattery;

    public LeyUpdraftBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LEY_UPDRAFT.get(), pos, state);
    }

    // ------------------------------------------------------------------------------------------------
    // 柱の判定(両サイド・ほかのクラスからも使う)
    // ------------------------------------------------------------------------------------------------

    /** 気流が届くマス数(0〜{@link #HEIGHT})。機械のすぐ上から数える。 */
    public static int height(BlockGetter level, BlockPos updraft) {
        BlockPos.MutableBlockPos p = updraft.mutable();
        for (int i = 1; i <= HEIGHT; i++) {
            p.move(Direction.UP);
            if (level.getBlockState(p).isCollisionShapeFullBlock(level, p)) {
                return i - 1;
            }
        }
        return HEIGHT;
    }

    /** 柱の箱(機械のすぐ上から {@code height} マス)。 */
    public static AABB column(BlockPos updraft, int height) {
        return new AABB(updraft.getX(), updraft.getY() + 1, updraft.getZ(),
                updraft.getX() + 1, updraft.getY() + 1 + height, updraft.getZ() + 1);
    }

    /**
     * {@code pos} を柱に入れている、動いている上昇気流機の位置。柱の外なら null。
     * 真下へ {@link #HEIGHT} マスまで探し、まるごと詰まったブロックに当たったら柱の外。
     */
    @Nullable
    public static BlockPos updraftBelow(BlockGetter level, Vec3 pos) {
        BlockPos.MutableBlockPos p = BlockPos.containing(pos).mutable();
        if (level.getBlockState(p).isCollisionShapeFullBlock(level, p)) {
            return null; // 天井のブロックの中
        }
        for (int i = 1; i <= HEIGHT; i++) {
            p.move(Direction.DOWN);
            BlockState s = level.getBlockState(p);
            if (s.is(ModBlocks.LEY_UPDRAFT.get())) {
                return s.getValue(LeyUpdraftBlock.POWERED) ? p.immutable() : null;
            }
            if (s.isCollisionShapeFullBlock(level, p)) {
                return null;
            }
        }
        return null;
    }

    /** {@code pos} が動いている上昇気流機の柱の中か。 */
    public static boolean isInColumn(BlockGetter level, Vec3 pos) {
        return updraftBelow(level, pos) != null;
    }

    /** 柱の中で持ち上げる物か(足元の真ん中がこの機械の列にある)。 */
    public static boolean canLift(Entity e, BlockPos updraft) {
        if (!e.isAlive() || e.isSpectator() || e.noPhysics || e.isPassenger()) {
            return false;
        }
        if (e instanceof Player p && p.getAbilities().flying) {
            return false;
        }
        return e.getBlockX() == updraft.getX() && e.getBlockZ() == updraft.getZ();
    }

    /** 1 tick ぶん持ち上げる(スニークしているプレイヤーはゆっくり下ろす)。 */
    public static void lift(Entity e) {
        Vec3 v = e.getDeltaMovement();
        // 生き物は動いてから重力を引くが、落ちている物・飛び道具は重力を引いてから動くので、その分を足して同じ速さで昇らせる
        double lift = e instanceof net.minecraft.world.entity.LivingEntity ? LIFT : LIFT + e.getGravity();
        double y = e.isShiftKeyDown() ? Math.max(v.y, SINK) : Math.max(v.y, lift);
        e.setDeltaMovement(v.x, y, v.z);
        e.resetFallDistance();
    }

    // ------------------------------------------------------------------------------------------------
    // 毎 tick
    // ------------------------------------------------------------------------------------------------

    public static void serverTick(Level level, BlockPos pos, BlockState state, LeyUpdraftBlockEntity be) {
        boolean running = MachineBattery.hasEnergy(be);
        if (state.getValue(LeyUpdraftBlock.POWERED) != running) {
            level.setBlock(pos, state.setValue(LeyUpdraftBlock.POWERED, running), 3);
        }
        if (!running) {
            return;
        }
        MachineBattery.drain(be, 1);
        int height = height(level, pos);
        if (height <= 0) {
            return;
        }
        for (Entity e : level.getEntities((Entity) null, column(pos, height), e -> canLift(e, pos))) {
            if (e instanceof ServerPlayer player) {
                // 持ち上げは手元のクライアント。サーバーでは落下の距離と「浮きすぎ」の数だけ戻す(専用サーバーで切断されないように)
                player.resetFallDistance();
                ((ServerGamePacketListenerFloatingAccessor) player.connection).overgrown_vegetation$setAboveGroundTickCount(0);
                continue;
            }
            if (e instanceof Player) {
                continue;
            }
            lift(e);
            e.hurtMarked = true; // 速さをクライアントへ送る(落ちている物・Mob の見た目が遅れないように)
        }
    }

    // ------------------------------------------------------------------------------------------------
    // 電池・保存
    // ------------------------------------------------------------------------------------------------

    @Override
    public int batteryCharge() {
        return charge;
    }

    @Override
    public void setBatteryCharge(int charge) {
        this.charge = Math.max(0, charge);
    }

    @Override
    public boolean hasBattery() {
        return hasBattery;
    }

    @Override
    public void setHasBattery(boolean has) {
        this.hasBattery = has;
    }

    @Override
    public void batteryChanged() {
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        MachineBattery.save(this, tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        MachineBattery.load(this, tag);
    }
}
