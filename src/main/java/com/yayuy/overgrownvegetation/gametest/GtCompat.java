package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

/**
 * 1.20.1 だけの GameTest の助け手(G8。2026-11-23)。1.21 の {@link GameTestHelper} にあって 1.20.1 に無い口を、同じ形で用意する。
 * テストの登録({@code @GameTestHolder})は無い。
 */
final class GtCompat {

    private static Field testInfoField;

    /** 1.21 の {@code helper.makeMockPlayer(GameType)} と同じ(1.20.1 の {@code makeMockPlayer()} はいつもクリエイティブ)。 */
    static Player mockPlayer(GameTestHelper helper, GameType gameType) {
        return new Player(helper.getLevel(), BlockPos.ZERO, 0.0F, new GameProfile(UUID.randomUUID(), "test-mock-player")) {
            @Override
            public boolean isSpectator() {
                return gameType == GameType.SPECTATOR;
            }

            @Override
            public boolean isCreative() {
                return gameType.isCreative();
            }

            @Override
            public boolean isLocalPlayer() {
                return true;
            }
        };
    }

    /** 1.21 の {@code helper.getBounds()}(1.20.1 では private)。テスト場の構造物の箱(ワールドの座標)。 */
    static AABB bounds(GameTestHelper helper) {
        try {
            if (testInfoField == null) {
                for (Field f : GameTestHelper.class.getDeclaredFields()) {
                    if (f.getType() == GameTestInfo.class) {
                        f.setAccessible(true);
                        testInfoField = f;
                        break;
                    }
                }
            }
            GameTestInfo info = (GameTestInfo) testInfoField.get(helper);
            return info.getStructureBounds();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("GameTestHelper の testInfo が読めない", e);
        }
    }

    /** 1.21 の {@code helper.getEntities(EntityType)}: テスト場の箱の中の生きている物。 */
    static <T extends Entity> List<T> entities(GameTestHelper helper, EntityType<T> type) {
        return helper.getLevel().getEntities(type, bounds(helper), Entity::isAlive);
    }

    /** 1.21 の {@code SlotAccess.of(getter, setter)}(1.20.1 に無い)。 */
    static net.minecraft.world.entity.SlotAccess slotAccess(java.util.function.Supplier<net.minecraft.world.item.ItemStack> getter,
                                                            java.util.function.Consumer<net.minecraft.world.item.ItemStack> setter) {
        return new net.minecraft.world.entity.SlotAccess() {
            @Override
            public net.minecraft.world.item.ItemStack get() {
                return getter.get();
            }

            @Override
            public boolean set(net.minecraft.world.item.ItemStack stack) {
                setter.accept(stack);
                return true;
            }
        };
    }

    /**
     * 1.21 の {@code CraftingInput.of(w, h, items)} の代わり。1.20.1 の作業台のレシピは {@code CraftingContainer} を受ける。
     * 画面(menu)は持たないので、中身を読むだけに使う(setItem・removeItem を呼ぶと落ちる)。
     */
    static net.minecraft.world.inventory.CraftingContainer craftingGrid(int width, int height, List<net.minecraft.world.item.ItemStack> items) {
        net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> list =
                net.minecraft.core.NonNullList.withSize(width * height, net.minecraft.world.item.ItemStack.EMPTY);
        for (int i = 0; i < items.size() && i < list.size(); i++) {
            list.set(i, items.get(i));
        }
        return new net.minecraft.world.inventory.TransientCraftingContainer(null, width, height, list);
    }

    /** 1.21 の {@code RandomizableContainer#getLootTable()}(1.20.1 は protected の欄だけ)。保存した NBT から読む。無ければ null。 */
    static net.minecraft.resources.ResourceLocation lootTable(net.minecraft.world.level.block.entity.BlockEntity be) {
        net.minecraft.nbt.CompoundTag tag = be.saveWithoutMetadata();
        return tag.contains("LootTable", net.minecraft.nbt.Tag.TAG_STRING)
                ? net.minecraft.resources.ResourceLocation.tryParse(tag.getString("LootTable")) : null;
    }

    /**
     * 1.21 の {@code stack.hurtAndBreak(n, level, (LivingEntity) null, …)}(持ち主なしで減らす)の代わりの持ち主。
     * 1.20.1 の {@code hurtAndBreak(n, entity, onBroken)} は持ち主が要る(世界には入れない防具立て。クリエイティブでないので減る)。
     */
    static net.minecraft.world.entity.LivingEntity wearer(net.minecraft.server.level.ServerLevel level) {
        return new net.minecraft.world.entity.decoration.ArmorStand(level, 0.0D, 0.0D, 0.0D);
    }

    /**
     * 1.21 の袋・箱のデータコンポーネント(BUNDLE_CONTENTS・CONTAINER)の代わり。{@code parent} に 1.20.1 の形の {@code Items}
     * (枠の番号 Slot 付き)を書く。袋はアイテムの NBT に、箱は {@code BlockEntityTag} に。
     */
    static void putItems(net.minecraft.nbt.CompoundTag parent, List<net.minecraft.world.item.ItemStack> items) {
        net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> list =
                net.minecraft.core.NonNullList.withSize(items.size(), net.minecraft.world.item.ItemStack.EMPTY);
        for (int i = 0; i < items.size(); i++) {
            list.set(i, items.get(i));
        }
        net.minecraft.world.ContainerHelper.saveAllItems(parent, list);
    }

    private GtCompat() {
    }
}
