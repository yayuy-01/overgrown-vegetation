package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.registry.ModG1;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 胞子の乳だまり(2026-11-13、能力の第 2 回。牛の 1 段目の (b)「胞子の乳」が地面に残す。仕様 Vault「G1 牛(1 段目)」3 節)。
 * {@link G1SporeCloud} と同じ「実体 1 つ」。地面に貼り付いた平たい円で、絵はクライアントの {@code client/G1MilkPuddleRenderer}(白っぽい汁の板 1 枚)。
 *
 * <ul>
 *   <li>踏んだ相手(植物側以外・創造でないプレイヤー)から<b>良い効果(バフ)だけを全部消す</b>【決定 11-09 ユーザー】。デバフ・胞子感染は残る。
 *   判定は {@value #CHECK_INTERVAL} tick ごと。暴露量は足さない(足すのは噴かれたときだけ)。</li>
 *   <li>{@link #duration()} tick で消える(仕様 6 秒【仮】。長さは出す側が渡す)。終わりの 1 秒で薄れる(描画)。</li>
 *   <li>地形は変えない。升の浮遊も上げない。</li>
 * </ul>
 * 後の回も {@link #spawn} と {@link #stripBuffs} を使える。
 */
public class G1MilkPuddle extends Entity {

    /** 踏んでいるかを確かめる間隔(tick)。【仮】 */
    static final int CHECK_INTERVAL = 5;

    private static final EntityDataAccessor<Float> RADIUS = SynchedEntityData.defineId(G1MilkPuddle.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DURATION = SynchedEntityData.defineId(G1MilkPuddle.class, EntityDataSerializers.INT);

    public G1MilkPuddle(EntityType<? extends G1MilkPuddle> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * 乳だまりを 1 つ置く(サーバー)。{@code near} の真下(上 1・下 3 ブロックまで)の地面の上に貼り付く。地面が無い・水の中なら置かない。
     *
     * @return 置いた乳だまり。置けなければ null
     */
    @Nullable
    public static G1MilkPuddle spawn(ServerLevel level, Vec3 near, float radius, int ticks) {
        Vec3 at = ground(level, near);
        if (at == null) return null;
        G1MilkPuddle p = new G1MilkPuddle(ModG1.MILK_PUDDLE.get(), level);
        p.setPos(at);
        p.entityData.set(RADIUS, radius);
        p.entityData.set(DURATION, ticks);
        level.addFreshEntity(p);
        return p;
    }

    /** {@code near} の真下の地面の上の点(当たり判定の天辺)。上が塞がっている・水があるなら null。 */
    @Nullable
    static Vec3 ground(ServerLevel level, Vec3 near) {
        BlockPos.MutableBlockPos p = BlockPos.containing(near.x, near.y + 1.0D, near.z).mutable();
        for (int i = 0; i < 5; i++, p.move(0, -1, 0)) {
            BlockState s = level.getBlockState(p);
            VoxelShape shape = s.getCollisionShape(level, p);
            if (shape.isEmpty()) continue;
            BlockPos above = p.above();
            BlockState a = level.getBlockState(above);
            if (!a.getCollisionShape(level, above).isEmpty() || !a.getFluidState().isEmpty()) return null;
            return new Vec3(near.x, p.getY() + shape.max(net.minecraft.core.Direction.Axis.Y), near.z);
        }
        return null;
    }

    /**
     * 良い効果(バフ)だけを全部消す【決定 11-09 ユーザー: 植物由来かどうかでは分けない】。植物側・創造/観戦のプレイヤーには効かない。
     *
     * @return 消した効果の数
     */
    public static int stripBuffs(LivingEntity e) {
        if (!e.isAlive() || SporeExposureTracker.isPlantSide(e)) return 0;
        if (e instanceof Player p && (p.isCreative() || p.isSpectator())) return 0;
        List<Holder<MobEffect>> good = new ArrayList<>();
        for (MobEffectInstance i : e.getActiveEffects()) {
            if (i.getEffect().value().getCategory() == MobEffectCategory.BENEFICIAL) good.add(i.getEffect());
        }
        for (Holder<MobEffect> h : good) e.removeEffect(h);
        return good.size();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(RADIUS, 1.0F);
        builder.define(DURATION, 120);
    }

    public float radius() {
        return this.entityData.get(RADIUS);
    }

    public int duration() {
        return this.entityData.get(DURATION);
    }

    /** その相手が乳だまりを踏んでいるか(足が円の中・高さは乳だまりの面の前後)。 */
    public boolean touches(LivingEntity e) {
        double r = radius() + e.getBbWidth() * 0.5D;
        double dx = e.getX() - getX(), dz = e.getZ() - getZ();
        return dx * dx + dz * dz <= r * r && e.getY() >= getY() - 0.4D && e.getY() <= getY() + 0.6D;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;
        if (tickCount > duration()) {
            discard();
            return;
        }
        if (tickCount % CHECK_INTERVAL == 1) {
            float r = radius() + 1.0F;
            AABB box = new AABB(getX() - r, getY() - 0.5D, getZ() - r, getX() + r, getY() + 1.0D, getZ() + r);
            for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, box)) {
                if (touches(e)) stripBuffs(e);
            }
        }
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(RADIUS, tag.getFloat("Radius"));
        this.entityData.set(DURATION, tag.getInt("Duration"));
        this.tickCount = tag.getInt("Age");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("Radius", radius());
        tag.putInt("Duration", duration());
        tag.putInt("Age", tickCount);
    }
}
