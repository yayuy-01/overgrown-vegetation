package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 汚染したスポナー({@code corruption/SpawnerCorruption}、2026-10-09 ユーザー指定)。
 * 汚染の入口({@code CorruptionRegistry#canCorrupt} → {@code corrupt})を通して覆い、湧く Mob が寄生体になること、
 * 寄生体の無い Mob はその次元の G1 になること、覆いを剥がすと元へ戻ることを見る。最後は必ず剥がす(GameTest の世界に覆いの記録を残さない)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SpawnerCorruptionGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SpawnerCorruptionGameTests.class);
        }

        private Registration() {
        }
    }

    private static final BlockPos AT = new BlockPos(3, 2, 3);



    /**
     * 洞窟グモ → 萌芽体(2026-10-30 の整理(退避)で寄生体・G1 を外したので、この MOD 以外の Mob は萌芽体に書き換える。仮決め)。
     * 剥がすと元へ戻る。
     */
    @GameTest(template = "gametest_empty7")
    public static void caveSpiderSpawnerBecomesBlightFern(GameTestHelper helper) {
        BlockPos pos = placeSpawner(helper, EntityType.CAVE_SPIDER);
        String error = corrupt(helper, pos);
        if (error == null) {
            List<String> ids = spawnerIds(helper, pos);
            if (!ids.equals(List.of("overgrown_vegetation:blight_fern"))) {
                error = "洞窟グモのスポナーが萌芽体にならない: " + ids;
            }
        }
        OvergrownLayerData.uncover(helper.getLevel(), pos);
        if (error == null && !spawnerIds(helper, pos).equals(List.of("minecraft:cave_spider"))) {
            error = "覆いを剥がしても洞窟グモへ戻らない: " + spawnerIds(helper, pos);
        }
        finish(helper, error);
    }


    // ------------------------------------------------------------------

    private static BlockPos placeSpawner(GameTestHelper helper, EntityType<?> type) {
        helper.setBlock(AT, Blocks.SPAWNER);
        BlockPos pos = helper.absolutePos(AT);
        ((SpawnerBlockEntity) helper.getLevel().getBlockEntity(pos)).setEntityId(type, helper.getLevel().random);
        return pos;
    }

    /** 汚染の入口を通して汚染する。通らなければ失敗の文。 */
    private static String corrupt(GameTestHelper helper, BlockPos pos) {
        ServerLevel level = helper.getLevel();
        BlockState state = level.getBlockState(pos);
        if (!CorruptionRegistry.canCorrupt(level, pos, state, false)) {
            return "スポナーが汚染の対象にならない";
        }
        BlockState after = CorruptionRegistry.corrupt(level, pos, state, level.random);
        if (!after.equals(state)) {
            return "スポナーが別のブロックに置き換わった: " + after;
        }
        return null;
    }

    /** スポナーの SpawnData と SpawnPotentials の中の Mob の id(重複なし、出てきた順)。 */
    private static List<String> spawnerIds(GameTestHelper helper, BlockPos pos) {
        ServerLevel level = helper.getLevel();
        CompoundTag tag = level.getBlockEntity(pos).saveWithoutMetadata();
        List<String> ids = new ArrayList<>();
        collect(tag.get("SpawnData"), ids);
        collect(tag.get("SpawnPotentials"), ids);
        return ids;
    }

    // 1.20.1: 試練のスポナーは無い(決定 1)ので、その Mob の id を読む助け手 trialIds は外した。

    private static void collect(Tag tag, List<String> out) {
        if (tag instanceof CompoundTag compound) {
            if (compound.contains("entity", Tag.TAG_COMPOUND) && compound.getCompound("entity").contains("id", Tag.TAG_STRING)) {
                String id = compound.getCompound("entity").getString("id");
                if (!out.contains(id)) {
                    out.add(id);
                }
            }
            for (String key : compound.getAllKeys()) {
                if (!key.equals("entity")) {
                    collect(compound.get(key), out);
                }
            }
        } else if (tag instanceof ListTag list) {
            for (Tag element : list) {
                collect(element, out);
            }
        }
    }

    private static String id(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }

    private static void finish(GameTestHelper helper, String error) {
        if (error != null) {
            helper.fail(error);
        } else {
            helper.succeed();
        }
    }
}
