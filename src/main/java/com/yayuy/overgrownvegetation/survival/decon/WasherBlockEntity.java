package com.yayuy.overgrownvegetation.survival.decon;

import com.yayuy.overgrownvegetation.data.DeconSpec;
import com.yayuy.overgrownvegetation.ley.LeyText;
import com.yayuy.overgrownvegetation.registry.ModDecon;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;

/**
 * <b>地脈の洗い場</b>の中身(2026-11-14 サバイバルの背骨 段 3a、決定 30)。
 *
 * <ul>
 *     <li>FE の口は受け取りだけ。中に {@code decon.machineEnergyBuffer} までためる。</li>
 *     <li>洗う前の 9 枠 → 洗い上がりの 9 枠。洗える物があり FE が {@code decon.machineFePerTick} 以上あれば、毎 tick その分を使って進み、
 *     {@code decon.machineTicksPerItem} tick で 1 つ洗う({@link Decon#washOne})。洗い上がりの枠が埋まっていれば止まる(FE も使わない)。</li>
 *     <li>上に落ちた洗える物を吸い込む({@code decon.machinePickup})。</li>
 *     <li>上に立った人の装備を洗う(段 3b)。</li>
 * </ul>
 * 中の FE・物・進みは保存する。
 */
public class WasherBlockEntity extends BlockEntity implements GeoBlockEntity {

    /** 動いている間のアニメ(はずみ車が回り、噴き出し管から水の筋が落ちる。2026-11-22 ユーザーの手直し版の模型)。 */
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("animation.ley_washer.run");
    /** 槽の中に描く洗う前の物の数【仮】。 */
    public static final int SHOWN_MAX = 3;

    public static final int INPUTS = 9;
    public static final int OUTPUTS = 9;
    static final int RUNNING_GRACE_TICKS = 10;

    private int energy;
    private int progress;
    private long lastRunTick = Long.MIN_VALUE;
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    /** 槽の中に描く物(洗う前の枠の先頭から {@link #SHOWN_MAX} 枠ぶん、1 つずつ)。サーバーでは最後に送った物、クライアントでは受け取った物。 */
    private List<ItemStack> shown = List.of();

    private final ItemStackHandler items = new ItemStackHandler(INPUTS + OUTPUTS) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            if (slot < INPUTS) {
                refreshShown();
            }
        }
    };

    private final IEnergyStorage port = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            int n = Math.max(0, Math.min(toReceive, spec().machineEnergyBuffer() - energy));
            if (!simulate && n > 0) {
                energy += n;
                setChanged();
            }
            return n;
        }

        @Override
        public int extractEnergy(int toExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return energy;
        }

        @Override
        public int getMaxEnergyStored() {
            return spec().machineEnergyBuffer();
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    };

    /** 外から見える物の口: 入れるのは洗える物を洗う前の枠へだけ、出すのは洗い上がりの枠からだけ。 */
    private final IItemHandler itemPort = new IItemHandler() {
        @Override
        public int getSlots() {
            return INPUTS + OUTPUTS;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return items.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot >= INPUTS || !accepts(stack)) return stack;
            return items.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot < INPUTS) return ItemStack.EMPTY;
            return items.extractItem(slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return items.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot < INPUTS && accepts(stack);
        }
    };

    public WasherBlockEntity(BlockPos pos, BlockState state) {
        super(ModDecon.WASHER_BE.get(), pos, state);
    }

    static DeconSpec spec() {
        return Decon.spec();
    }

    public IEnergyStorage energyPort() {
        return port;
    }

    public IItemHandler itemPort() {
        return itemPort;
    }

    public int energy() {
        return energy;
    }

    /** 中の物の枠(0〜8 = 洗う前、9〜17 = 洗い上がり。GameTest・ブロックの右クリックから)。 */
    public ItemStackHandler items() {
        return items;
    }

    boolean accepts(ItemStack stack) {
        return level != null && Decon.kind(level, stack) != Decon.Kind.NONE;
    }

    /** 洗う前の枠へ入れる。入り切らなかった残りを返す。 */
    public ItemStack insert(ItemStack stack) {
        if (!accepts(stack)) return stack;
        return ItemHandlerHelper.insertItemStacked(new net.neoforged.neoforge.items.wrapper.RangedWrapper(items, 0, INPUTS), stack, false);
    }

    /** 動いているか(直近 {@link #RUNNING_GRACE_TICKS} tick 以内に FE を使って洗った)。 */
    public boolean running() {
        return level != null && lastRunTick != Long.MIN_VALUE && level.getGameTime() - lastRunTick < RUNNING_GRACE_TICKS;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, WasherBlockEntity be) {
        be.tickOnce();
    }

    /** 1 tick ぶん(GameTest からも呼ぶ)。FE を使って進めたら true。 */
    public boolean tickOnce() {
        if (!(level instanceof ServerLevel server)) return false;
        DeconSpec spec = spec();
        if (spec.machinePickup() && (server.getGameTime() + worldPosition.asLong()) % 10 == 0) {
            pickUp(server);
        }
        boolean ran = false;
        if (energy >= spec.machineFePerTick()) {
            ran = GearShower.washStanding(server, worldPosition, this);
        }
        int slot = nextInput();
        if (slot < 0) {
            progress = 0;
        } else if (energy >= spec.machineFePerTick() && progress < spec.machineTicksPerItem()) {
            progress++;
            ran = true;
        }
        if (slot >= 0 && progress >= spec.machineTicksPerItem() && washSlot(server, slot)) {
            progress = 0;
        }
        if (ran) {
            energy -= spec.machineFePerTick();
            lastRunTick = server.getGameTime();
            setChanged();
        }
        boolean run = running();
        BlockState state = getBlockState();
        if (state.hasProperty(WasherBlock.RUNNING) && state.getValue(WasherBlock.RUNNING) != run) {
            server.setBlock(worldPosition, state.setValue(WasherBlock.RUNNING, run), Block.UPDATE_CLIENTS);
        }
        return ran;
    }

    /** 次に洗う枠(洗えない物 —— 表を変えた後など —— は洗い上がりへそのまま送る)。無ければ -1。 */
    private int nextInput() {
        for (int i = 0; i < INPUTS; i++) {
            ItemStack s = items.getStackInSlot(i);
            if (s.isEmpty()) continue;
            if (Decon.kind(level, s) != Decon.Kind.NONE) return i;
            ItemStack rest = moveToOutputs(s.copy(), false);
            items.setStackInSlot(i, rest);
        }
        return -1;
    }

    /** {@code slot} の 1 つを洗い、洗い上がりへ。入り切らなければ洗わずに false。 */
    private boolean washSlot(ServerLevel server, int slot) {
        ItemStack src = items.getStackInSlot(slot);
        List<ItemStack> outs = Decon.washOne(server, src.copyWithCount(1), server.random, GearDirt.machineStep());
        for (ItemStack o : outs) {
            if (!moveToOutputs(o.copy(), true).isEmpty()) return false;
        }
        // 出口ごとに入るかを確かめた(素材と胞子が同じ空き枠を取り合って入り切らない分は足元へ落とす)
        items.extractItem(slot, 1, false);
        for (ItemStack o : outs) {
            ItemStack rest = moveToOutputs(o, false);
            if (!rest.isEmpty()) {
                net.minecraft.world.Containers.dropItemStack(server, worldPosition.getX() + 0.5D, worldPosition.getY() + 1.0D,
                        worldPosition.getZ() + 0.5D, rest);
            }
        }
        return true;
    }

    private ItemStack moveToOutputs(ItemStack stack, boolean simulate) {
        return ItemHandlerHelper.insertItemStacked(new net.neoforged.neoforge.items.wrapper.RangedWrapper(items, INPUTS, INPUTS + OUTPUTS),
                stack, simulate);
    }

    /** 上のマスに落ちている洗える物を吸い込む。 */
    private void pickUp(ServerLevel server) {
        AABB box = new AABB(worldPosition.above()).inflate(0.0D, 0.25D, 0.0D);
        for (ItemEntity e : server.getEntitiesOfClass(ItemEntity.class, box, ItemEntity::isAlive)) {
            if (e.hasPickUpDelay()) continue;
            ItemStack rest = insert(e.getItem().copy());
            if (rest.isEmpty()) e.discard();
            else e.setItem(rest);
        }
    }

    /** 洗い上がりの物を全部取り出す(無ければ洗う前の物)。 */
    public java.util.List<ItemStack> takeAll() {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        for (int i = INPUTS; i < INPUTS + OUTPUTS; i++) {
            ItemStack s = items.getStackInSlot(i);
            if (!s.isEmpty()) {
                out.add(s);
                items.setStackInSlot(i, ItemStack.EMPTY);
            }
        }
        if (out.isEmpty()) {
            for (int i = 0; i < INPUTS; i++) {
                ItemStack s = items.getStackInSlot(i);
                if (!s.isEmpty()) {
                    out.add(s);
                    items.setStackInSlot(i, ItemStack.EMPTY);
                }
            }
            progress = 0;
        }
        return out;
    }

    /** 右クリック・置いたときの表示。 */
    public Component info() {
        int in = 0, out = 0;
        for (int i = 0; i < INPUTS; i++) in += items.getStackInSlot(i).getCount();
        for (int i = INPUTS; i < INPUTS + OUTPUTS; i++) out += items.getStackInSlot(i).getCount();
        String state = running() ? "running" : energy < spec().machineFePerTick() ? "no_power" : "idle";
        return Component.translatable("message.overgrown_vegetation.ley_washer." + state).copy()
                .append(Component.translatable("message.overgrown_vegetation.ley_washer.info", in, out,
                        LeyText.fe(energy), LeyText.fe(spec().machineEnergyBuffer())));
    }

    // ---- 槽の中に描く物(2026-11-22。クライアントへは変わったときだけ送る) ----

    /** 槽の中に描く物(クライアントの描画 {@code client/decon/WasherRenderer} から)。 */
    public List<ItemStack> shown() {
        return shown;
    }

    /** 今の洗う前の枠から、描く物を選ぶ(先頭から空でない枠を {@link #SHOWN_MAX} 個まで。数は見せないので 1 つずつ)。 */
    public List<ItemStack> computeShown() {
        List<ItemStack> out = new java.util.ArrayList<>(SHOWN_MAX);
        for (int i = 0; i < INPUTS && out.size() < SHOWN_MAX; i++) {
            ItemStack s = items.getStackInSlot(i);
            if (!s.isEmpty()) out.add(s.copyWithCount(1));
        }
        return out;
    }

    /** 描く物の顔ぶれ(物の種類と中身。数は見ない)が変わったら、クライアントへ送り直す。洗うたびに数が減るだけなら送らない。 */
    private void refreshShown() {
        if (level == null || level.isClientSide) return;
        List<ItemStack> now = computeShown();
        if (sameItems(shown, now)) return;
        shown = now;
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    /** 描く物の顔ぶれが同じか(数は見ない。GameTest からも)。 */
    public static boolean sameItems(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.isSameItemSameComponents(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    /** クライアントへ送る中身は描く物だけ(FE・枠の中身・進みは送らない)。 */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        List<ItemStack> now = shown;
        if (level != null && !level.isClientSide) {
            // 送った物を覚える(11-22 見直し): 読み込んだ直後は shown が空のままなので、チャンクと一緒に今の中身を送った後で
            // 槽を空にしても「変わっていない」と見て送らず、クライアントに浮いた物が残っていた。
            now = computeShown();
            shown = now;
        }
        for (ItemStack s : now) list.add(s.save(registries));
        tag.put("shown", list);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** 受け取った描く物だけを読む(ふつうの読み込みに回すと、クライアントの FE などが 0 で上書きされる)。 */
    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        readShown(tag, registries);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
        readShown(pkt.getTag(), registries);
    }

    private void readShown(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains("shown", Tag.TAG_LIST)) return;
        ListTag list = tag.getList("shown", Tag.TAG_COMPOUND);
        List<ItemStack> out = new java.util.ArrayList<>(list.size());
        for (int i = 0; i < list.size() && out.size() < SHOWN_MAX; i++) {
            ItemStack.parse(registries, list.getCompound(i)).ifPresent(out::add);
        }
        shown = List.copyOf(out);
    }

    // ---- GeckoLib ----

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "run", 0, state -> {
            BlockState bs = getBlockState();
            if (bs.hasProperty(WasherBlock.RUNNING) && bs.getValue(WasherBlock.RUNNING)) {
                return state.setAndContinue(RUN);
            }
            return PlayState.STOP;
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("energy", energy);
        tag.putInt("progress", progress);
        tag.put("items", items.serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Math.max(0, tag.getInt("energy"));
        progress = Math.max(0, tag.getInt("progress"));
        if (tag.contains("items")) items.deserializeNBT(registries, tag.getCompound("items"));
    }
}
