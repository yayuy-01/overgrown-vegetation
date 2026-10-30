package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * {@link CorruptedCorpseBlock} の中身。「胞子感染」で力尽きた Mob の死体として、
 * blight_fern と同じ {@link CorruptionSpreader} を使ってこの場所を起点に侵食を広げる。
 * ただし半径は {@link OvergrownVegetationConfig.Common#corpseCorruptionRadius}
 * (既定 10。blight_fern の {@code corruptionRadius}(既定 30)より小さい)。
 *
 * <p>半径いっぱいまで汚染し終えたら(=侵食が完了したら)、この死体を消して
 * {@link OvergrownVegetationConfig.Common#corpseHatchChance}(既定 80%)の確率で種族対応の
 * 「過繁茂した(MOB名)」({@link CorpseHatchTable} 参照)を同じ場所に孵化させ、外れた残り
 * (既定 20%)ではその場に汚染シダ(blight_fern)を生やす({@link #finish}）。全ての死体ブロックが
 * {@link CorpseHatchTable} に対応表を持つので、この2択のどちらかに必ず分岐する。
 *
 * <p>種族専用の GeckoLib モデルを持つ死体({@link GeoCorruptedCorpseBlock}: 羊・豚・クリーパー)は
 * この中身を {@code GeoBlockEntity} として {@code CorruptedCorpseRenderer}(GeoBlockRenderer)で
 * 描画する。遠くの個体は
 * 描画しない({@code getViewDistance()} を短く設定)。アニメーションはせず静的なモデルだが、
 * GeckoLib でカスタムモデルを描画するには {@code GeoBlockEntity} + {@code GeoBlockRenderer} の組が必要。
 * まだ専用モデルの無い死体(牛・ゾンビ・クモ)は通常のブロックモデル({@link CorruptedCorpseBlock})の
 * ままで、その場合 {@code CorruptedCorpseRenderer} は何も描画しない。
 */
public class CorruptedCorpseBlockEntity extends BlockEntity implements GeoBlockEntity {

    private final CorruptionSpreader spreader = new CorruptionSpreader();
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    /** 侵食完了後の孵化/汚染シダ抽選を、もう行ったか。 */
    private boolean spawnRolled = false;
    /** 置かれてからの tick(起き上がりと胞子の放出の時計。G1 の作り直し 第 1 段)。 */
    private int age;
    /**
     * 死体の向き(度、Mob の yBodyRot と同じ測り方)と、ブロックの底の真ん中からのずれ(ブロック単位)。2026-10-09。
     * 崩れ落ちて死体になったときは死んだ Mob の向き・位置({@code corpse.CorpseCollapse#finish})、手で置いたときは置いた人の向きで、
     * ずれは無し({@link #hasExactOffset} が false → 描画で死体の真ん中をブロックの真ん中に寄せる)。孵化した Mob もこの向き・位置で起き上がる。
     */
    private float yaw = 180.0F;
    private double offsetX, offsetY, offsetZ;
    private boolean exactOffset = false;

    public CorruptedCorpseBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CORRUPTED_CORPSE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CorruptedCorpseBlockEntity blockEntity) {
        if (level instanceof ServerLevel serverLevel) {
            blockEntity.tick(serverLevel, pos);
        }
    }

    private void tick(ServerLevel level, BlockPos pos) {
        if (com.yayuy.overgrownvegetation.corruption.CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10 ユーザー指定): 侵食も孵化も待つ
        }
        if (spawnRolled) {
            // 拡散も抽選も完了済み。以後は本当に何もしない(blight_fern と同じ考え方)
            return;
        }

        // G1 の作り直し 第 1 段(2026-10-22): 死体は起き上がるまで(g1.json corpse.emitTicks、仮 10 分)その升へ胞子を出す。
        // 量は spore_field.json の sources.corpsePerDay(1 日あたり、仮決め)。emitTicks が 0 なら前のとおり侵食し終えたら起き上がる。
        com.yayuy.overgrownvegetation.data.G1Table g1 = com.yayuy.overgrownvegetation.data.DesignData.g1();
        age++;
        if (g1.corpseEmitTicks > 0 && age <= g1.corpseEmitTicks && age % g1.corpseEmitEveryTicks == 0) {
            com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.emitPoint(level, pos,
                    com.yayuy.overgrownvegetation.data.DesignData.sporeField().corpsePerDay() * g1.corpseEmitEveryTicks / 24000.0D);
        }
        if ((age & 63) == 0) {
            setChanged();
        }

        if (!spreader.isFinished()) {
            spreader.seedGround(level, pos);

            int interval = OvergrownVegetationConfig.COMMON.tickInterval.get();
            // 同じ tick に多数の死体の侵食サイクルが重ならないよう、座標から決まる位相でずらす
            // (blight_fern が UUID でやっているのと同じ考え方)。
            int phase = Math.floorMod(pos.hashCode(), interval);
            if ((level.getGameTime() + phase) % interval == 0) {
                // 死体は露出面のあるブロックのみ汚染する(内部には一切広がらない、深さ0固定)
                int before = spreader.getCorruptedCount();
                spreader.tick(level, pos, OvergrownVegetationConfig.COMMON.corpseCorruptionRadius.get(), 0);
                CorruptionCounter.record(level, spreader.getCorruptedCount() - before);
            }
        }
        // 起き上がり(2026-10-23 胞子の場 A3): 時計(g1.json corpse.emitTicks)が来たら、侵食が半径いっぱいに届いていなくても起き上がる
        // (【決定】「死体に → 少しの間 周囲を汚染し胞子を生産 → 起き上がる」。前は侵食の完了も待っていて、周りが先に汚染されていると
        // 触手が進めずいつまでも起き上がらなかった)。時計が 0 なら前のとおり侵食し終えたら。
        if (g1.corpseEmitTicks > 0 ? age < g1.corpseEmitTicks : !spreader.isFinished()) {
            return;
        }

        spawnRolled = true;
        setChanged();
        finish(level, pos, false);
    }

    /**
     * 侵食完了時の一度きりの分岐。死体を消し、{@code corpseHatchChance} に当たれば種族対応の
     * 「過繁茂した(MOB名)」を、外れれば汚染シダを、いずれも同じ座標(死体があった場所)に湧かせる。
     * {@code shouldOffsetY=false} で {@link EntityType#create} を呼ぶことで、死体の1つ上ではなく
     * 死体そのものの位置に足が来るようにしている(死体はこの直前に取り除くので、湧いた Mob が
     * 消える前のブロックにめり込むことはない)。
     */
    /** 侵食の完了を待たずに孵す(`/ov corpse hatch`、確かめる用。2026-10-09)。孵化の確率も無視して必ず孵す。 */
    public void forceHatch(ServerLevel level) {
        if (spawnRolled) {
            return;
        }
        spawnRolled = true;
        finish(level, worldPosition, true);
    }

    private void finish(ServerLevel level, BlockPos pos, boolean forceHatch) {
        EntityType<?> hatchType = CorpseHatchTable.hatchTypeFor(getBlockState().getBlock());
        if (hatchType != null && com.yayuy.overgrownvegetation.difficulty.Peaceful.blocksSpawn(level, hatchType)) {
            // ピースフルでは敵を孵さず、外れと同じ汚染シダにする(孵しても次の tick に消えて、死骸だけ失われるので。2026-09-26)。
            hatchType = null;
        }
        if (hatchType != null && com.yayuy.overgrownvegetation.base.build.BaseRefuge.blocksSpawn(level, hatchType, pos)) {
            // 同盟軍の基地の壁の中でも孵さず汚染シダにする(避難場所、順 2 の着手順 3。孵す湧きは取り消されて死骸だけ失われるので)。
            hatchType = null;
        }
        level.removeBlock(pos, false);

        double hatchChance = OvergrownVegetationConfig.COMMON.corpseHatchChance.get();
        if (hatchType != null && (forceHatch || level.random.nextDouble() < hatchChance)) {
            Entity spawned = hatchType.create(level, null, pos, MobSpawnType.MOB_SUMMONED, false, false);
            if (spawned != null) {
                if (spawned instanceof com.yayuy.overgrownvegetation.corpse.RisingMob rising
                        && com.yayuy.overgrownvegetation.corpse.CorpseSpecies.speciesOf(getBlockState().getBlock()) != null) {
                    // 死体が倒れていたその場・その向きで起き上がる(2026-10-09)。手で置いた死体はブロックの真ん中
                    double x = pos.getX() + 0.5 + (exactOffset ? offsetX : 0);
                    double y = pos.getY() + (exactOffset ? offsetY : 0);
                    double z = pos.getZ() + 0.5 + (exactOffset ? offsetZ : 0);
                    spawned.moveTo(x, y, z, yaw, 0.0F);
                    if (spawned instanceof net.minecraft.world.entity.LivingEntity living) {
                        living.yBodyRot = yaw;
                        living.yBodyRotO = yaw;
                        living.yHeadRot = yaw;
                        living.yHeadRotO = yaw;
                    }
                    rising.startRising(com.yayuy.overgrownvegetation.corpse.RisingMob.RISE_TICKS);
                }
                level.addFreshEntity(spawned);
            }
        } else {
            // フォールバックの汚染シダ。水中の死体なら汚染水草になる。
            BlightFernPlacement.spawn(level, pos, MobSpawnType.MOB_SUMMONED);
        }
    }

    /** 崩れ落ちて死体になったときの向きと位置(ブロックの底の真ん中からのずれ)を記録して、見ている人へ送る。 */
    public void setPlacement(float yaw, double dx, double dy, double dz) {
        this.yaw = yaw;
        this.offsetX = dx;
        this.offsetY = dy;
        this.offsetZ = dz;
        this.exactOffset = true;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    /** 手で置いたときの向き(置いた人の向き)。ずれは無し(描画で真ん中に寄せる)。 */
    public void setPlacedYaw(float yaw) {
        this.yaw = yaw;
        this.exactOffset = false;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    /** 置かれてからの tick(テスト・コマンド用)。 */
    public int age() {
        return age;
    }

    /** テスト用: 時計を進める。 */
    public void setAgeForTests(int age) {
        this.age = age;
    }

    public float yaw() {
        return yaw;
    }

    public boolean hasExactOffset() {
        return exactOffset;
    }

    public double offsetX() {
        return offsetX;
    }

    public double offsetY() {
        return offsetY;
    }

    public double offsetZ() {
        return offsetZ;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        spreader.save(tag);
        tag.putBoolean("SpawnRolled", spawnRolled);
        tag.putInt("CorpseAge", age);
        savePlacement(tag);
    }

    private void savePlacement(CompoundTag tag) {
        tag.putFloat("CorpseYaw", yaw);
        if (exactOffset) {
            tag.putDouble("CorpseDx", offsetX);
            tag.putDouble("CorpseDy", offsetY);
            tag.putDouble("CorpseDz", offsetZ);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        spreader.load(tag);
        spawnRolled = tag.getBoolean("SpawnRolled");
        age = tag.getInt("CorpseAge");
        if (tag.contains("CorpseYaw")) {
            yaw = tag.getFloat("CorpseYaw");
        }
        exactOffset = tag.contains("CorpseDx");
        if (exactOffset) {
            offsetX = tag.getDouble("CorpseDx");
            offsetY = tag.getDouble("CorpseDy");
            offsetZ = tag.getDouble("CorpseDz");
        }
    }

    /** 見ている人へは向き・位置だけ送る(侵食の状態はクライアントに要らない)。 */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        savePlacement(tag);
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 死体は静的なモデルなので、アニメーションコントローラーは登録しない。
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
