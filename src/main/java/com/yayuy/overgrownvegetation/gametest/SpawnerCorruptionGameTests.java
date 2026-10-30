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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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

    /** ゾンビのスポナー → 寄生されたゾンビ。剥がすとゾンビへ戻る。 */
    @GameTest(template = "gametest_empty7")
    public static void zombieSpawnerBecomesParasitizedAndReverts(GameTestHelper helper) {
        BlockPos pos = placeSpawner(helper, EntityType.ZOMBIE);
        String error = corrupt(helper, pos);
        if (error == null && !spawnerIds(helper, pos).equals(List.of(id(ModEntities.PARASITIZED_ZOMBIE.get())))) {
            error = "汚染したゾンビのスポナーが寄生されたゾンビを湧かせない: " + spawnerIds(helper, pos);
        }
        if (error == null && !OvergrownLayerData.isCovered(helper.getLevel(), pos)) {
            error = "汚染したスポナーが覆われていない(苔が付かない)";
        }
        OvergrownLayerData.uncover(helper.getLevel(), pos);
        if (error == null && !spawnerIds(helper, pos).equals(List.of("minecraft:zombie"))) {
            error = "覆いを剥がしてもゾンビへ戻らない: " + spawnerIds(helper, pos);
        }
        if (error == null && helper.getLevel().getBlockEntity(pos).hasData(ModAttachments.SPAWNER_ORIGINAL)) {
            error = "戻した後も元の登録の記録が残っている";
        }
        finish(helper, error);
    }

    /** ブレイズのスポナー → 寄生されたブレイズ(ガスト・ブレイズの入れ替え表)。 */
    @GameTest(template = "gametest_empty7")
    public static void blazeSpawnerUsesNetherTable(GameTestHelper helper) {
        BlockPos pos = placeSpawner(helper, EntityType.BLAZE);
        String error = corrupt(helper, pos);
        if (error == null && !spawnerIds(helper, pos).equals(List.of(id(ModEntities.PARASITIZED_BLAZE.get())))) {
            error = "汚染したブレイズのスポナーが寄生されたブレイズを湧かせない: " + spawnerIds(helper, pos);
        }
        OvergrownLayerData.uncover(helper.getLevel(), pos);
        finish(helper, error);
    }

    /** 洞窟グモ(寄生体が無い)→ この次元(地上)の G1 のどれか。ネザーの種にはならない。 */
    @GameTest(template = "gametest_empty7")
    public static void caveSpiderSpawnerFallsBackToDimensionG1(GameTestHelper helper) {
        BlockPos pos = placeSpawner(helper, EntityType.CAVE_SPIDER);
        String error = corrupt(helper, pos);
        if (error == null) {
            List<String> ids = spawnerIds(helper, pos);
            EntityType<?> type = ids.size() == 1 ? EntityType.byString(ids.get(0)).orElse(null) : null;
            if (type == null || !type.is(ModTags.GROUP_1) || type.is(ModTags.NETHER_NATIVE)) {
                error = "洞窟グモのスポナーが地上の G1 にならない: " + ids;
            }
        }
        OvergrownLayerData.uncover(helper.getLevel(), pos);
        if (error == null && !spawnerIds(helper, pos).equals(List.of("minecraft:cave_spider"))) {
            error = "覆いを剥がしても洞窟グモへ戻らない: " + spawnerIds(helper, pos);
        }
        finish(helper, error);
    }

    /** 試練のスポナー: 通常・不吉の両方の一覧が寄生体になり、剥がすと戻る。 */
    @GameTest(template = "gametest_empty7")
    public static void trialSpawnerConfigsBecomeParasitized(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(AT);
        helper.setBlock(AT, Blocks.TRIAL_SPAWNER);
        BlockEntity be = level.getBlockEntity(pos);
        try {
            CompoundTag tag = be.saveWithoutMetadata(level.registryAccess());
            tag.put("normal_config", TagParser.parseTag(
                    "{spawn_potentials:[{data:{entity:{id:\"minecraft:skeleton\"}},weight:1}]}"));
            tag.put("ominous_config", TagParser.parseTag(
                    "{spawn_potentials:[{data:{entity:{id:\"minecraft:zombie\"}},weight:1}]}"));
            be.loadWithComponents(tag, level.registryAccess());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            helper.fail("NBT が読めない: " + e.getMessage());
            return;
        }
        String error = corrupt(helper, pos);
        List<String> parasitized = List.of(id(ModEntities.PARASITIZED_SKELETON.get()), id(ModEntities.PARASITIZED_ZOMBIE.get()));
        if (error == null && !trialIds(helper, pos).equals(parasitized)) {
            error = "汚染した試練のスポナーが寄生体にならない(通常・不吉): " + trialIds(helper, pos);
        }
        OvergrownLayerData.uncover(level, pos);
        if (error == null && !trialIds(helper, pos).equals(List.of("minecraft:skeleton", "minecraft:zombie"))) {
            error = "覆いを剥がしても試練のスポナーが元へ戻らない(通常・不吉): " + trialIds(helper, pos);
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
        CompoundTag tag = level.getBlockEntity(pos).saveWithoutMetadata(level.registryAccess());
        List<String> ids = new ArrayList<>();
        collect(tag.get("SpawnData"), ids);
        collect(tag.get("SpawnPotentials"), ids);
        return ids;
    }

    /**
     * 試練のスポナーが今持っている通常・不吉の設定の Mob の id(それぞれの最初の 1 つ)。保存した NBT ではなく本体を見る
     * —— バニラは不吉の設定が通常と同じなら保存を省き、読むときに通常から補う。
     */
    private static List<String> trialIds(GameTestHelper helper, BlockPos pos) {
        var spawner = ((net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity) helper.getLevel().getBlockEntity(pos)).getTrialSpawner();
        List<String> ids = new ArrayList<>();
        for (var config : List.of(spawner.getNormalConfig(), spawner.getOminousConfig())) {
            var entries = config.spawnPotentialsDefinition().unwrap();
            ids.add(entries.isEmpty() ? "(無し)" : entries.get(0).data().entityToSpawn().getString("id"));
        }
        return ids;
    }

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
