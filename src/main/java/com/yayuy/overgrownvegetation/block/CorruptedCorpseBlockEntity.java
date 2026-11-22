package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import com.yayuy.overgrownvegetation.registry.ModG1;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * G1 の死体ブロックの中身(2026-11-08 に G1 段階 0 の組み込みで書き直した。元は 2026-10-09〜22 の版、parked → git {@code 940392c})。
 *
 * <p>一生(Vault「G1 段階 0 の共通」5 節【決定 10-22】): 胞子感染で死ぬ → 死体の姿勢へ崩れ落ちて死体ブロック → 少しの間 周りを汚染し胞子を出す →
 * 起き上がって G1 になる。
 * <ul>
 *   <li>胞子: 起き上がるまで {@value #EMIT_EVERY_TICKS} tick ごとにその升へ点の放出(1 日あたり {@value #SPORES_PER_DAY}。旧 spore_field.json の corpsePerDay と同じ値)。</li>
 *   <li>汚染: 汚染シダと同じ {@link CorruptionSpreader} で、露出した面だけ半径 {@value #CORRUPTION_RADIUS}(旧 config の corpseCorruptionRadius の既定)。</li>
 *   <li>起き上がり: 置かれてから {@value #RISE_AFTER_TICKS} tick(【仮 10-22】10 分)。侵食の完了は待たない(10-23 胞子の場 A3 と同じ)。
 *   死体が倒れていたその場・その向きで、種の {@code rise} を流して起き上がる(起き上がりの間は AI を止める)。必ず起き上がる
 *   (旧の 80% / 外れで汚染シダ、は 11-05 の決定「死体 → 起き上がる」に合わせて外した)。ピースフルでは敵を出さず汚染シダにする。</li>
 * </ul>
 * 時計の値は【仮】でまだ design json に出していない(DEV_NOTES の G1 段階 0 の節に書いた)。
 *
 * <p>見た目(クライアント): 置かれた時刻 {@link #placedGameTime} を見ている人へ送る。描画が始まったら、置かれてすぐなら
 * {@code corpse_burst}(突き破り)→ {@code corpse_idle}、遅れて読み込んだ(区画の読み直し・後から近づいた)なら {@code corpse_idle} だけ。
 * 絵は突き破りの {@code swapTick} まで無傷の絵(描画 {@code CorruptedCorpseModel} が {@link #burstStartClientTick} から決める)。
 */
public class CorruptedCorpseBlockEntity extends BlockEntity implements GeoBlockEntity {

    /** 起き上がるまで(tick)【仮 10-22 ユーザー了承の時計: 死体 10 分】。 */
    public static final int RISE_AFTER_TICKS = 12000;
    /** 胞子を出す間隔(tick)。 */
    public static final int EMIT_EVERY_TICKS = 100;
    /** 死体が出す胞子(過繁茂 1 チャンクの 1 日の放出 = 1。旧 corpsePerDay)。 */
    public static final double SPORES_PER_DAY = 1.0D;
    /** 周りを汚染する半径(旧 config corpseCorruptionRadius の既定)。 */
    public static final int CORRUPTION_RADIUS = 10;
    /** 置かれてからこの tick 以内に描き始めたら突き破りを流す(それより遅い読み込みは待機だけ)。 */
    public static final int BURST_LATE_TICKS = 10;
    /**
     * 周りの汚染(茂み・苔)を始めるまで(tick)。突き破り(corpse_burst、全種 60 tick = 3 秒)が終わってから(11-08 ユーザー「突き破りの後へ遅らせる」。
     * 前は置かれてすぐ茂みが生えて、突き破りが隠れて見えなかった)。描画の区画の作り直しの遅れの分を少し足した。
     */
    public static final int CORRUPT_AFTER_TICKS = 70;

    private static final RawAnimation BURST_THEN_IDLE = RawAnimation.begin().thenPlay("corpse_burst").thenLoop("corpse_idle");
    private static final RawAnimation IDLE_ONLY = RawAnimation.begin().thenLoop("corpse_idle");

    private final CorruptionSpreader spreader = new CorruptionSpreader();
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    /** 起き上がりを済ませたか。 */
    private boolean risen = false;
    /** 置かれてからの tick。 */
    private int age;
    /** 置かれたゲームの時刻(見ている人へ送る。突き破りを流すかの判断)。 */
    private long placedGameTime = Long.MIN_VALUE;
    /** 羊の毛の色(DyeColor の id、-1 = 無し)。 */
    private int woolColor = -1;
    /** 手に持っていた物(スケルトンの弓。起き上がった G1 にそのまま持たせ、死体にも描く)。 */
    private net.minecraft.world.item.ItemStack heldItem = net.minecraft.world.item.ItemStack.EMPTY;
    /**
     * 死体の向き(度、Mob の yBodyRot と同じ測り方)と、ブロックの底の真ん中からのずれ(ブロック単位)。崩れ落ちて死体になったときは
     * 死んだ Mob の向き・位置。起き上がる Mob もこの向き・位置で起きる。
     */
    private float yaw = 180.0F;
    private double offsetX, offsetY, offsetZ;
    private boolean exactOffset = false;

    // クライアントだけ
    /** 突き破りを流し始めたクライアントのゲーム時刻(流さなかったら Long.MIN_VALUE)。 */
    private long burstStartClientTick = Long.MIN_VALUE;
    private boolean animChosen = false;

    public CorruptedCorpseBlockEntity(BlockPos pos, BlockState state) {
        super(ModG1.CORRUPTED_CORPSE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CorruptedCorpseBlockEntity be) {
        if (level instanceof ServerLevel serverLevel) {
            be.tick(serverLevel, pos);
        }
    }

    private void tick(ServerLevel level, BlockPos pos) {
        if (placedGameTime == Long.MIN_VALUE) {
            // 崩れ落ちを通らずに置かれた死体(コマンド・テスト): 置かれた時刻を見ている人へ送る(送らないと突き破りを流すか決められず、止まった姿のままになる)
            placedGameTime = level.getGameTime();
            setChanged();
            level.sendBlockUpdated(pos, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
        if (risen || CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10 ユーザー指定): 侵食も起き上がりも待つ(置かれた時刻は上で先に送る = 突き破りの見た目は止めない)
        }
        age++;
        if (age % EMIT_EVERY_TICKS == 0) {
            SporeFieldServer.emitPoint(level, pos, SPORES_PER_DAY * EMIT_EVERY_TICKS / 24000.0D);
        }
        if ((age & 63) == 0) {
            setChanged();
        }
        if (age > CORRUPT_AFTER_TICKS && !spreader.isFinished()) {
            spreader.seedGround(level, pos);
            int interval = OvergrownVegetationConfig.COMMON.tickInterval.get();
            // 同じ tick に多数の死体の侵食が重ならないよう、座標から決まる位相でずらす
            int phase = Math.floorMod(pos.hashCode(), interval);
            if ((level.getGameTime() + phase) % interval == 0) {
                int before = spreader.getCorruptedCount();
                spreader.tick(level, pos, CORRUPTION_RADIUS, 0); // 露出面のあるブロックだけ(深さ 0)
                CorruptionCounter.record(level, spreader.getCorruptedCount() - before);
            }
        }
        if (age >= RISE_AFTER_TICKS) {
            rise(level, pos);
        }
    }

    /** 起き上がらせる(時計が来た・{@code /ov g1 hatch})。死体ブロックは消える。 */
    public void rise(ServerLevel level, BlockPos pos) {
        if (risen) {
            return;
        }
        risen = true;
        setChanged();
        G1Species species = G1Species.ofCorpse(getBlockState().getBlock());
        level.removeBlock(pos, false);
        if (species == null || level.getDifficulty() == Difficulty.PEACEFUL) {
            // ピースフルでは敵を出さず汚染シダにする(出しても次の tick に消えて、死体だけ失われるので。2026-09-26 と同じ)
            BlightFernPlacement.spawn(level, pos, MobSpawnType.MOB_SUMMONED);
            return;
        }
        AbstractParasitizedMonster mob = species.entityType().create(level, null, pos, MobSpawnType.MOB_SUMMONED, false, false);
        if (mob == null) {
            return;
        }
        double x = pos.getX() + 0.5D + (exactOffset ? offsetX : 0.0D);
        double y = pos.getY() + (exactOffset ? offsetY : 0.0D);
        double z = pos.getZ() + 0.5D + (exactOffset ? offsetZ : 0.0D);
        mob.moveTo(x, y, z, yaw, 0.0F);
        mob.yBodyRot = yaw;
        mob.yBodyRotO = yaw;
        mob.yHeadRot = yaw;
        mob.yHeadRotO = yaw;
        mob.inheritCorpse(this);
        mob.startRising(species.riseTicks());
        level.addFreshEntity(mob);
    }

    /** 崩れ落ちて死体になったときの向きと位置(ブロックの底の真ん中からのずれ)を記録して、見ている人へ送る。 */
    public void setPlacement(float yaw, double dx, double dy, double dz) {
        this.yaw = yaw;
        this.offsetX = dx;
        this.offsetY = dy;
        this.offsetZ = dz;
        this.exactOffset = true;
        if (level != null && !level.isClientSide()) {
            this.placedGameTime = level.getGameTime();
        }
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public void setWoolColor(int color) {
        this.woolColor = color;
        setChanged();
    }

    public int woolColor() {
        return woolColor;
    }

    public void setHeldItem(net.minecraft.world.item.ItemStack stack) {
        this.heldItem = stack.copy();
        setChanged();
    }

    public net.minecraft.world.item.ItemStack heldItem() {
        return heldItem;
    }

    public int age() {
        return age;
    }

    /** テスト用: 時計を進める。 */
    public void setAgeForTests(int age) {
        this.age = age;
    }

    public boolean hasRisen() {
        return risen;
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

    public long placedGameTime() {
        return placedGameTime;
    }

    /**
     * クライアント: 突き破りを流し始めた時刻(流さないなら Long.MIN_VALUE)。描画が初めて聞いたときに 1 回だけ決める:
     * 置かれてから {@value #BURST_LATE_TICKS} tick 以内なら今から流す、それより遅い読み込み(区画の読み直し・後から近づいた)なら流さない。
     * 置かれた時刻がまだ届いていなければ決めない(ふつうはブロックと同じ便で届く)。絵の選び({@code CorruptedCorpseModel})と
     * アニメの選びが同じ答えを使う —— GeckoLib は絵をアニメより先に聞くので、どちらが先でも食い違わないように。
     */
    public long burstStartClientTick() {
        if (!animChosen && level != null && placedGameTime != Long.MIN_VALUE) {
            animChosen = true;
            long now = level.getGameTime();
            if (now - placedGameTime <= BURST_LATE_TICKS) {
                burstStartClientTick = now;
            }
        }
        return burstStartClientTick;
    }

    /** クライアント: 突き破りを流すかをもう決めたか。 */
    public boolean burstDecided() {
        burstStartClientTick();
        return animChosen;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        spreader.save(tag);
        tag.putBoolean("Risen", risen);
        tag.putInt("CorpseAge", age);
        savePlacement(tag, registries);
    }

    private void savePlacement(CompoundTag tag, HolderLookup.Provider registries) {
        if (!heldItem.isEmpty()) {
            tag.put("Held", heldItem.save(registries));
        }
        tag.putFloat("CorpseYaw", yaw);
        if (exactOffset) {
            tag.putDouble("CorpseDx", offsetX);
            tag.putDouble("CorpseDy", offsetY);
            tag.putDouble("CorpseDz", offsetZ);
        }
        if (placedGameTime != Long.MIN_VALUE) {
            tag.putLong("PlacedAt", placedGameTime);
        }
        if (woolColor >= 0) {
            tag.putInt("WoolColor", woolColor);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        spreader.load(tag);
        risen = tag.getBoolean("Risen");
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
        placedGameTime = tag.contains("PlacedAt") ? tag.getLong("PlacedAt") : Long.MIN_VALUE;
        woolColor = tag.contains("WoolColor") ? tag.getInt("WoolColor") : -1;
        heldItem = tag.contains("Held") ? net.minecraft.world.item.ItemStack.parseOptional(registries, tag.getCompound("Held")) : net.minecraft.world.item.ItemStack.EMPTY;
    }

    /** 見ている人へは向き・位置・置かれた時刻・毛の色だけ送る(侵食の状態はクライアントに要らない)。 */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        savePlacement(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /**
     * 死体のアニメ(2026-11-08): 置かれてすぐ描き始めたら突き破り → 待機、遅れて読み込んだら待機だけ(区画の読み直しで突き破りを
     * 繰り返さないように)。決めるのは最初の 1 回だけ。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "corpse", 0, state -> {
            if (!burstDecided()) {
                return software.bernie.geckolib.animation.PlayState.STOP; // 置かれた時刻がまだ届いていない
            }
            return state.setAndContinue(burstStartClientTick != Long.MIN_VALUE ? BURST_THEN_IDLE : IDLE_ONLY);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
