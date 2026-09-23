package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.menu.SporeDeviceMenu;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * {@link SporeDeviceBlock} の中身。スロットは 2 つ:
 * <ul>
 *     <li>{@link #SLOT_FUEL}(0): レッドストーンダスト。1 個で {@code ticksPerRedstone}(既定 5 分)稼働。</li>
 *     <li>{@link #SLOT_SPORE}(1): 除去装置では産出した「胞子」の取り出し口、拡散装置では消費する「胞子」の投入口。</li>
 * </ul>
 * 稼働中({@link #isActive()})の間だけ胞子濃度に効く({@link com.yayuy.overgrownvegetation.spore.SporeDeviceData#deltaAt})。
 *
 * <p>除去装置は稼働中 {@code sporeIntervalTicks}(既定 30 秒)ごとに「胞子」を産出する。個数は
 * 「装置のあるチャンクの<b>装置の影響を除いた</b>胞子濃度」({@link SporeConcentration.Result#percentBeforeDevices})に
 * 比例し、100% で {@code sporesPerCycleAtFull}(既定 4)個。端数は確率で切り上げる。
 * 拡散装置は燃料投入のたびにレッドストーンダストと一緒に「胞子」を {@code sporesPerRedstone}(既定 1)個消費する。
 *
 * <p>ホッパー対応: 燃料は上下左右どこからでも投入でき、除去装置の「胞子」はどの面からでも取り出せる。
 */
public class SporeDeviceBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {

    public static final int SLOT_FUEL = 0;
    public static final int SLOT_SPORE = 1;
    /**
     * 胞子フィルター(除去装置の消耗品、2026-09-21 のユーザー指定)。
     * 拡散装置では使わないので、そちらではこのスロットに何も入らない。
     */
    public static final int SLOT_FILTER = 2;
    public static final int SLOT_COUNT = 3;
    private static final int[] ALL_SLOTS = {SLOT_FUEL, SLOT_SPORE, SLOT_FILTER};

    public static final int DATA_REMAINING = 0;
    public static final int DATA_TICKS_PER_FUEL = 1;
    public static final int DATA_CYCLE = 2;
    public static final int DATA_CYCLE_LENGTH = 3;
    public static final int DATA_COUNT = 4;

    /**
     * 胞子爆弾の汚染範囲に入っているとみなす高さの差(ブロック)。
     * {@code corruptSurfaceDisc} が「地表が着弾点から 24 ブロック以上離れた列は汚染しない」としているので、
     * 詰まりの判定もそれに合わせる。段差の下や塔の上の装置まで巻き込まないための上限。
     */
    private static final double JAM_VERTICAL_REACH = 24.0D;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    /** 残り稼働 tick。0 なら停止中(燃料があれば次の tick で再点火)。 */
    private int remainingTicks;
    /** 除去装置の「胞子」産出サイクルの進行 tick。 */
    private int cycleTicks;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            EffectTable.DeviceSpec spec = kind().spec();
            return switch (index) {
                case DATA_REMAINING -> remainingTicks;
                case DATA_TICKS_PER_FUEL -> spec.ticksPerRedstone();
                case DATA_CYCLE -> cycleTicks;
                case DATA_CYCLE_LENGTH -> spec.sporeIntervalTicks();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            if (index == DATA_REMAINING) {
                remainingTicks = value;
            } else if (index == DATA_CYCLE) {
                cycleTicks = value;
            }
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    public SporeDeviceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPORE_DEVICE.get(), pos, state);
    }

    public SporeDeviceKind kind() {
        return getBlockState().getBlock() instanceof SporeDeviceBlock block ? block.kind() : SporeDeviceKind.PURIFIER;
    }

    /** 稼働中(燃料が燃えている)か。胞子濃度への効果はこの間だけ。 */
    public boolean isActive() {
        return remainingTicks > 0;
    }

    /**
     * <b>詰まっている</b>か —— 除去装置の「胞子」の取り出し口が満杯で、これ以上産出できない状態。
     *
     * <p>詰まった除去装置は {@link #serverTick} でその場で止まり(残り稼働 tick を捨てて {@code LIT} も消える)、
     * <b>詰まりが取れるまで燃料も食わない</b>。胞子濃度への効果も止まる({@link #isActive()} が false になる)。
     * 直し方は「胞子」を取り出すだけ —— 壊す必要はない。
     *
     * <p>この状態を突然作りにくるのが<b>攻城戦ボスの胞子爆弾</b>だ:
     * 着弾点の汚染範囲に入った除去装置は取り出し口を満杯にされて止まる
     * ({@link #jamPurifiersInDisc}、ユーザー要望 2026-09-17)。拡散装置には効かない
     * (あちらの「胞子」スロットは消費する<b>投入口</b>なので、満杯は都合がいいだけ)。
     */
    /**
     * <b>フィルターが無い(または使い切った)</b>か。除去装置はこの間<b>動かない</b> ——
     * 濃度にも効かず、燃料も食わない(ユーザー指定 2026-09-21)。
     *
     * <p>「置けば永久に効く設備」から「濃いところほど維持費が高い設備」へ変えるための歯止め。
     * 詰まり({@link #isJammed()})と分けてあるのは、直し方が違うから ——
     * 詰まりは胞子を取り出す、こちらはフィルターを入れ替える。
     */
    public boolean needsFilter() {
        return kind().spec().requiresFilter()
                && !com.yayuy.overgrownvegetation.spore.SporeFilters.isUsable(items.get(SLOT_FILTER));
    }

    public boolean isJammed() {
        if (kind() != SporeDeviceKind.PURIFIER) {
            return false;
        }
        ItemStack out = items.get(SLOT_SPORE);
        return !out.isEmpty() && out.getCount() >= out.getMaxStackSize();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SporeDeviceBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        SporeDeviceKind kind = be.kind();
        EffectTable.DeviceSpec spec = kind.spec();
        boolean wasActive = be.remainingTicks > 0;

        // 詰まり(取り出し口が満杯)/ フィルター切れ = 強制停止。
        // 残り稼働 tick は捨て、直るまで燃料も食わない。
        boolean jammed = be.isJammed() || be.needsFilter();
        if (jammed) {
            if (be.remainingTicks != 0 || be.cycleTicks != 0) {
                be.remainingTicks = 0;
                be.cycleTicks = 0;
                be.setChanged();
            }
        } else if (be.remainingTicks > 0) {
            be.remainingTicks--;
            if (kind == SporeDeviceKind.PURIFIER && spec.sporeIntervalTicks() > 0) {
                be.cycleTicks++;
                if (be.cycleTicks >= spec.sporeIntervalTicks()) {
                    be.cycleTicks = 0;
                    // 働いたぶんだけフィルターが減る。胞子が 0 個でも基本の目詰まりは進む。
                    be.wearFilter(serverLevel, spec, be.produceSpores(serverLevel, spec));
                }
            }
            be.setChanged();
        }
        if (!jammed && be.remainingTicks <= 0 && be.tryConsumeFuel(kind, spec)) {
            be.remainingTicks = Math.max(1, spec.ticksPerRedstone());
            be.setChanged();
        }

        boolean active = be.remainingTicks > 0;
        if (state.getValue(SporeDeviceBlock.LIT) != active) {
            level.setBlock(pos, state.setValue(SporeDeviceBlock.LIT, active), 3);
        }
        if (active != wasActive) {
            // 稼働状態が変わった → 周囲の胞子濃度キャッシュと阻害率キャッシュを捨てて即反映。
            SporeConcentration.invalidateAround(serverLevel, new ChunkPos(pos), spec.reachChunks());
            SporeDeviceData.invalidateInhibitionAll();
        }
    }

    /**
     * <b>汚染の円盤に入った胞子除去装置を詰まらせる</b>(ユーザー要望 2026-09-17)。
     * 攻城戦ボスの胞子爆弾が着弾したときに {@code SiegeBombEntity.afterBlast} から呼ばれる。
     *
     * <p>やることは「胞子」の取り出し口を<b>満杯にする</b>ことだけ —— それで {@link #isJammed()} が立ち、
     * 次の tick に装置が止まる。壊しはしないので、プレイヤーが胞子を取り出せばそのまま動き出す
     * (爆弾は「一帯の除去装置を数十秒黙らせる」役で、設備を消しには来ない)。
     * <b>胞子がタダで 1 スタック手に入る</b>点はわざとそのままにしている ——
     * 取り出しに行くという行動そのものが、爆撃下で外へ出る危険と釣り合っている。
     *
     * <p>範囲は {@code corruptSurfaceDisc} の円盤と同じ考え方: <b>水平距離</b>が {@code radius} 以内で、
     * 着弾点との高さの差が {@link #JAM_VERTICAL_REACH} 以内。輪郭のノイズ(±1.5 程度)までは真似しない ——
     * 「汚染された地面の上に立っていたのに止まらなかった」という説明しづらい取りこぼしを避けるため、
     * 判定は設定値そのままの円で取る。未ロードのチャンクは見ない(そこに装置は動いていない)。
     *
     * @return 詰まらせた装置の数
     */
    public static int jamPurifiersInDisc(ServerLevel level, Vec3 center, double radius) {
        if (radius <= 0.0D) {
            return 0;
        }
        int minChunkX = SectionPos.blockToSectionCoord(Mth.floor(center.x - radius));
        int maxChunkX = SectionPos.blockToSectionCoord(Mth.floor(center.x + radius));
        int minChunkZ = SectionPos.blockToSectionCoord(Mth.floor(center.z - radius));
        int maxChunkZ = SectionPos.blockToSectionCoord(Mth.floor(center.z + radius));
        double radiusSq = radius * radius;
        int jammed = 0;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                // getBlockEntities() を舐めるのは、円の中を 1 ブロックずつ getBlockEntity するより
                // ずっと安い(装置は 1 チャンクに 0〜数個しか無い)。
                for (BlockEntity be : List.copyOf(level.getChunk(chunkX, chunkZ).getBlockEntities().values())) {
                    if (!(be instanceof SporeDeviceBlockEntity device)
                            || device.kind() != SporeDeviceKind.PURIFIER) {
                        continue;
                    }
                    BlockPos pos = device.getBlockPos();
                    double dx = (pos.getX() + 0.5D) - center.x;
                    double dz = (pos.getZ() + 0.5D) - center.z;
                    if (dx * dx + dz * dz > radiusSq
                            || Math.abs((pos.getY() + 0.5D) - center.y) > JAM_VERTICAL_REACH) {
                        continue;
                    }
                    if (device.jam()) {
                        jammed++;
                    }
                }
            }
        }
        return jammed;
    }

    /**
     * 「胞子」の取り出し口を満杯にして詰まらせる。既に満杯なら何もしない。
     *
     * @return この呼び出しで新たに詰まったか
     */
    private boolean jam() {
        if (isJammed()) {
            return false;
        }
        ItemStack spore = new ItemStack(ModItems.SPORE.get());
        spore.setCount(spore.getMaxStackSize());
        items.set(SLOT_SPORE, spore);
        setChanged();
        if (level != null) {
            level.playSound(null, worldPosition, SoundEvents.SCULK_BLOCK_SPREAD, SoundSource.BLOCKS, 1.2F, 0.6F);
        }
        return true;
    }

    /**
     * 働いたぶんだけフィルターを減らす。使い切ったら<b>その場で無くなる</b>(2026-09-21)。
     *
     * <p>減り方を「サイクル 1 回ぶん + 取れた胞子 1 個ぶん」にしてあるので、
     * <b>濃いところに置くほど早く目詰まりする</b> —— きれいな空気なら何時間も保つ。
     * 耐久はバニラの仕組みなので、残りはインベントリのバーでそのまま見える。
     *
     * <p>使い切っても<b>壊さない</b>({@link com.yayuy.overgrownvegetation.spore.SporeFilters})。
     * 目詰まりしたフィルターは除染装置({@link DecontaminatorBlockEntity})で洗って使い回せる。
     */
    private void wearFilter(ServerLevel level, EffectTable.DeviceSpec spec, int producedSpores) {
        if (!spec.requiresFilter()) {
            return;
        }
        ItemStack filter = items.get(SLOT_FILTER);
        if (filter.isEmpty() || !filter.isDamageableItem()) {
            return;
        }
        int wear = spec.filterWearPerCycle() + producedSpores * spec.filterWearPerSpore();
        if (wear <= 0) {
            return;
        }
        // <b>壊さない</b>。目詰まりしたフィルターは除染装置で洗って使い回せる
        // (ユーザー指定 2026-09-21)。消してしまうと洗いようがない。
        if (com.yayuy.overgrownvegetation.spore.SporeFilters.wear(filter, wear)) {
            level.playSound(null, worldPosition, SoundEvents.WOOL_BREAK, SoundSource.BLOCKS, 0.8F, 0.7F);
        }
        setChanged();
    }

    private boolean tryConsumeFuel(SporeDeviceKind kind, EffectTable.DeviceSpec spec) {
        ItemStack fuel = items.get(SLOT_FUEL);
        if (!fuel.is(Items.REDSTONE)) {
            return false;
        }
        if (kind == SporeDeviceKind.DIFFUSER) {
            int need = spec.sporesPerRedstone();
            ItemStack spore = items.get(SLOT_SPORE);
            if (need > 0 && (!spore.is(ModItems.SPORE.get()) || spore.getCount() < need)) {
                return false;
            }
            if (need > 0) {
                spore.shrink(need);
            }
        }
        fuel.shrink(1);
        return true;
    }

    /**
     * 産出サイクル 1 回ぶん。<b>取れた胞子の数を返す</b> —— フィルターの減りが
     * 「働いた量」に比例するので、呼ぶ側がその数を要る(2026-09-21)。
     */
    private int produceSpores(ServerLevel level, EffectTable.DeviceSpec spec) {
        if (spec.sporesPerCycleAtFull() <= 0.0D) {
            return 0;
        }
        ItemStack out = items.get(SLOT_SPORE);
        if (!out.isEmpty() && !out.is(ModItems.SPORE.get())) {
            return 0;
        }
        double base = SporeConcentration.at(level, new ChunkPos(worldPosition)).percentBeforeDevices();
        double expected = spec.sporesPerCycleAtFull() * Math.max(0.0D, Math.min(100.0D, base)) / 100.0D;
        int count = (int) Math.floor(expected);
        if (level.getRandom().nextDouble() < expected - count) {
            count++;
        }
        if (count <= 0) {
            return 0;
        }
        if (out.isEmpty()) {
            items.set(SLOT_SPORE, new ItemStack(ModItems.SPORE.get(), Math.min(count, 64)));
        } else {
            out.setCount(Math.min(out.getMaxStackSize(), out.getCount() + count));
        }
        setChanged();
        return count;
    }

    // --- Container ---

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.overgrown_vegetation." + kind().id());
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new SporeDeviceMenu(containerId, inventory, this, data, kind());
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    public int getContainerSize() {
        return items.size();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot == SLOT_FUEL) {
            return stack.is(Items.REDSTONE);
        }
        return kind() == SporeDeviceKind.DIFFUSER && stack.is(ModItems.SPORE.get());
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return ALL_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return slot == SLOT_SPORE && kind() == SporeDeviceKind.PURIFIER;
    }

    // --- NBT ---

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
        remainingTicks = tag.getInt("RemainingTicks");
        cycleTicks = tag.getInt("CycleTicks");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putInt("RemainingTicks", remainingTicks);
        tag.putInt("CycleTicks", cycleTicks);
    }
}
