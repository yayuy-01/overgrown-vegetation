package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 「覆われて動かなくなったブロック」({@link OvergrownHuskBlock})の中身(2026-09-22、要望)。
 *
 * <p>持つのは 2 つだけ。
 * <ul>
 *     <li><b>元のブロック状態</b>({@link #mimic}) —— 見た目を借りるためだけに持つ。
 *     クライアントへも送る({@link #getUpdateTag})。</li>
 *     <li><b>元の中身</b>({@link #contents}) —— 覆われる前にその機械が持っていたアイテム。
 *     かまどで焼いていたものが消えないようにするため(要望)。</li>
 * </ul>
 * 壊すと<b>この 2 つがまとめて返る</b>({@link #dropSalvage}) —— 元のブロックはアイテムの形で、
 * 中身はそのまま。{@link #mimic} は見た目のためだけでなく、<b>返すブロックを決めるのにも使う</b>。
 *
 * <h2>tick しない</h2>
 * <p>この中身は<b>何も動かさない</b>。元のブロックの挙動はすべて捨てるのが目的なので、
 * ticker を付けないこと —— 付けた瞬間に「覆われているのに動くブロック」に逆戻りする。
 *
 * <h2>見た目の更新</h2>
 * <p>模型は {@link ModelData} 経由で {@link #mimic} を受け取る
 * ({@code client/OvergrownHuskModel})。チャンクのメッシュに焼き込まれるので、
 * <b>{@code mimic} が届いた/変わったときに自分でメッシュを作り直させる</b>必要がある
 * ({@link #refreshModel})。これを忘れると、置いた直後は
 * 「予備の模型(汚染ブロック)」のまま見えてしまう。
 */
public class OvergrownHuskBlockEntity extends BlockEntity {

    /** 模型へ「何に化けるか」を渡す口。 */
    public static final ModelProperty<BlockState> MIMIC = new ModelProperty<>();

    /** 中身として覚えておくスタックの上限。桁外れの内部インベントリを持つ MOD よけ。 */
    private static final int MAX_CONTENTS = 256;

    private BlockState mimic = Blocks.AIR.defaultBlockState();
    private final List<ItemStack> contents = new ArrayList<>();

    public OvergrownHuskBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OVERGROWN_HUSK.get(), pos, state);
    }

    /** 化けている元のブロック状態。読み込めなかった(MOD を外した)ときは空気。 */
    public BlockState mimic() {
        return mimic;
    }

    /** 覆った瞬間に、元のブロック状態と取り出した中身を預ける。 */
    public void capture(BlockState previous, List<ItemStack> items) {
        this.mimic = previous == null ? Blocks.AIR.defaultBlockState() : previous;
        this.contents.clear();
        for (ItemStack stack : items) {
            if (!stack.isEmpty() && contents.size() < MAX_CONTENTS) {
                contents.add(stack.copy());
            }
        }
        setChanged();
        if (level != null && !level.isClientSide) {
            // 見た目はクライアントが持つ mimic で決まるので、置いた直後に必ず送る。
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        refreshModel();
    }

    /**
     * 壊れたときに<b>元のブロックと中身をまとめて返す</b>(2026-09-22、要望)。
     *
     * <p>汚染を解こうとこれを壊せば、覆われていた機械そのものが手元に戻る ——
     * 中身だけ返しても、機械を作り直せないのでは「解いた」ことにならない。
     *
     * <p>元のブロックは<b>アイテムの形</b>で返るので、向きや状態(かまどが点いていたかなど)は
     * 引き継がない。アイテムを持たないブロック(多ブロック構造の部品など)は何も返らない。
     */
    public void dropSalvage(Level level, BlockPos pos) {
        if (level.isClientSide) {
            return;
        }
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;
        if (!mimic.isAir()) {
            Item item = mimic.getBlock().asItem();
            if (item != Items.AIR) {
                Containers.dropItemStack(level, x, y, z, new ItemStack(item));
            }
        }
        for (ItemStack stack : contents) {
            Containers.dropItemStack(level, x, y, z, stack.copy());
        }
        contents.clear();
        setChanged();
    }

    /** 中に残っているスタック数(コマンド・テスト用)。 */
    public int contentsSize() {
        return contents.size();
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Mimic", Tag.TAG_COMPOUND)) {
            // MOD を外していると元のブロックが引けない。そのときは空気が返るので、
            // 模型は予備の見た目に落ちる(クラッシュはしない)。
            mimic = NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("Mimic"));
        } else {
            mimic = Blocks.AIR.defaultBlockState();
        }
        contents.clear();
        ListTag list = tag.getList("Contents", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && contents.size() < MAX_CONTENTS; i++) {
            ItemStack.parse(registries, list.getCompound(i)).ifPresent(contents::add);
        }
        refreshModel();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!mimic.isAir()) {
            tag.put("Mimic", NbtUtils.writeBlockState(mimic));
        }
        if (!contents.isEmpty()) {
            ListTag list = new ListTag();
            for (ItemStack stack : contents) {
                if (!stack.isEmpty()) {
                    list.add(stack.save(registries));
                }
            }
            tag.put("Contents", list);
        }
    }

    /**
     * クライアントへ送る内容。<b>中身({@code Contents})は送らない</b> ——
     * 見た目に関係しないうえ、倉庫を覆ったときに大量のアイテム NBT を配ることになる。
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        if (!mimic.isAir()) {
            tag.put("Mimic", NbtUtils.writeBlockState(mimic));
        }
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        super.onDataPacket(net, packet, registries);
        refreshModel();
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        refreshModel();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        refreshModel();
    }

    @Override
    public ModelData getModelData() {
        return ModelData.builder().with(MIMIC, mimic).build();
    }

    /**
     * 模型データを取り直させ、このセクションのメッシュを作り直させる。
     * {@code requestModelDataUpdate} だけではメッシュは作り直されない(次に何かが変わるまで古いまま)。
     */
    private void refreshModel() {
        if (level == null || !level.isClientSide) {
            return;
        }
        requestModelDataUpdate();
        level.setBlocksDirty(worldPosition, getBlockState(), getBlockState());
    }
}
