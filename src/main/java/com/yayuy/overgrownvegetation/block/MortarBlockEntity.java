package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.crafting.GrindingRecipe;
import com.yayuy.overgrownvegetation.crafting.MortarMixingRecipe;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModRecipes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 乳鉢の中身(2026-10-16)。1 個ずつのスタックを {@link MortarMixingRecipe#MAX_INGREDIENTS 6 つ}まで。
 * すりつぶす進み({@link #progress})は先頭の「まだすりつぶせる物」に効き、長押しの 1 回ごと(4 tick おき)に 4 進む。
 * {@link #grindUntil} は乳棒を回して見せる時刻(クライアントへ送る)。
 */
public class MortarBlockEntity extends BlockEntity {

    /** 長押しで {@code useWithoutItem} が呼ばれる間隔(バニラの右クリックの間)。 */
    public static final int STEP_TICKS = 4;

    private final NonNullList<ItemStack> items = NonNullList.withSize(MortarMixingRecipe.MAX_INGREDIENTS, ItemStack.EMPTY);
    private int progress;
    private long grindUntil;

    public MortarBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MORTAR.get(), pos, state);
    }

    public int count() {
        int n = 0;
        for (ItemStack s : items) {
            if (!s.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    public List<ItemStack> contents() {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : items) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    public long grindUntil() {
        return grindUntil;
    }

    public int progress() {
        return progress;
    }

    /** 入れられる物か: すりつぶせる物か、薬のどれかの材料。 */
    public static boolean accepts(Level level, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (grindingFor(level, stack).isPresent()) {
            return true;
        }
        for (RecipeHolder<MortarMixingRecipe> r : level.getRecipeManager().getAllRecipesFor(ModRecipes.MORTAR_MIXING_TYPE.get())) {
            for (var ing : r.value().ingredients()) {
                if (ing.test(stack)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Optional<RecipeHolder<GrindingRecipe>> grindingFor(Level level, ItemStack stack) {
        return level.getRecipeManager().getRecipeFor(ModRecipes.GRINDING_TYPE.get(), new SingleRecipeInput(stack), level);
    }

    /** 1 個入れる。入ったら true。 */
    public boolean insert(ItemStack one) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isEmpty()) {
                items.set(i, one.copyWithCount(1));
                changed();
                return true;
            }
        }
        return false;
    }

    /** 最後に入れた物を 1 つ出す(無ければ空)。 */
    public ItemStack popLast() {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (!items.get(i).isEmpty()) {
                ItemStack out = items.get(i);
                items.set(i, ItemStack.EMPTY);
                progress = 0;
                changed();
                return out;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 長押しの 1 回ぶん、すりつぶす。すりつぶせる物が無ければ false。1 個すりつぶし終わったらそれを結果に置き換える。
     */
    public boolean grindStep(ServerLevel level) {
        int index = -1;
        Optional<RecipeHolder<GrindingRecipe>> recipe = Optional.empty();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty()) {
                recipe = grindingFor(level, items.get(i));
                if (recipe.isPresent()) {
                    index = i;
                    break;
                }
            }
        }
        if (index < 0) {
            return false;
        }
        ItemStack target = items.get(index);
        int time = recipe.get().value().time() > 0 ? recipe.get().value().time() : DesignData.effects().medicineGrindTicks();
        progress += STEP_TICKS;
        grindUntil = level.getGameTime() + STEP_TICKS * 3L;
        BlockPos p = getBlockPos();
        double[] c = MortarBlock.bowlCenter(getBlockState());
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, target), p.getX() + c[0], p.getY() + 0.2D, p.getZ() + c[1],
                2, 0.06D, 0.02D, 0.06D, 0.04D);
        level.playSound(null, p, SoundEvents.BRUSH_GRAVEL, SoundSource.BLOCKS, 0.5F, 1.2F + level.random.nextFloat() * 0.3F);
        if (progress >= time) {
            items.set(index, recipe.get().value().assemble(new SingleRecipeInput(target), level.registryAccess()).copyWithCount(1));
            progress = 0;
            level.playSound(null, p, SoundEvents.SAND_BREAK, SoundSource.BLOCKS, 0.6F, 1.1F);
        }
        changed();
        return true;
    }

    /**
     * 中身の組み合わせのレシピが、このプレイヤーには研究で封印されているか(2026-10-24、忌避の精油を研究「転用」で封印するため。
     * 前は乳鉢のレシピは封印を見ていなかった)。組み合わせになっていなければ false。
     */
    public boolean isLockedFor(ServerLevel level, net.minecraft.world.entity.player.Player player) {
        return level.getRecipeManager().getRecipeFor(ModRecipes.MORTAR_MIXING_TYPE.get(), new MortarMixingRecipe.Input(contents()), level)
                .map(r -> com.yayuy.overgrownvegetation.research.ResearchLogic.isRecipeLocked(player, r.id())).orElse(false);
    }

    /** 中身が薬の組み合わせなら、中身を空にして薬を返す(無ければ空)。 */
    public ItemStack collect(ServerLevel level) {
        MortarMixingRecipe.Input input = new MortarMixingRecipe.Input(contents());
        Optional<RecipeHolder<MortarMixingRecipe>> recipe = level.getRecipeManager()
                .getRecipeFor(ModRecipes.MORTAR_MIXING_TYPE.get(), input, level);
        if (recipe.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack out = recipe.get().value().assemble(input, level.registryAccess());
        items.replaceAll(s -> ItemStack.EMPTY);
        progress = 0;
        changed();
        return out;
    }

    public NonNullList<ItemStack> itemsForDrop() {
        return items;
    }

    private void changed() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, true, registries);
        tag.putInt("Progress", progress);
        tag.putLong("GrindUntil", grindUntil);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.replaceAll(s -> ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
        progress = tag.getInt("Progress");
        grindUntil = tag.getLong("GrindUntil");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
