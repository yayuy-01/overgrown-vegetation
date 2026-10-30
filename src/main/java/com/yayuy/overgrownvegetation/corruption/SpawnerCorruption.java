package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>汚染したスポナー</b>(2026-10-09、ユーザー指定)。
 *
 * <h2>ユーザー指定</h2>
 * <ul>
 *     <li>スポナーを汚染できるようにする。汚染したスポナーは、登録されている Mob の<b>寄生されたバージョン</b>を湧かせる。</li>
 *     <li>寄生されたバージョンが無い Mob なら、<b>そのスポナーのある次元に湧く G1 のどれか</b>(ランダム)にする。</li>
 *     <li>見た目は覆われたブロックと同じ苔をかぶせる。<b>試練のスポナーも</b>同じ。浄化したら<b>元に戻す</b>。</li>
 * </ul>
 *
 * <h2>作り</h2>
 * <p>ブロックは置き換えない(湧き方・試練の報酬などバニラの仕組みをそのまま使う)。覆われたマスの記録
 * ({@link OvergrownLayerData#cover})に入れ、苔は覆われたブロックと同じ描き方で付く({@code client/OvergrownLayerRenderer} が
 * スポナーの模型をなぞる)。覆った瞬間に、ブロックエンティティの「湧く Mob の登録」の中の Mob の id を寄生体へ書き換え、
 * 元の登録をブロックエンティティに覚えさせる({@link ModAttachments#SPAWNER_ORIGINAL})。覆いが剥がれたら
 * ({@link OvergrownLayerData#uncover}: 火の浄化・工兵の整備)覚えた登録へ戻す。
 * 覚えて戻すのは登録の部分だけ(スポナー: {@code SpawnData}・{@code SpawnPotentials}、試練のスポナー: 通常・不吉の設定と次に湧かす Mob)
 * —— 試練の待ち時間・登録したプレイヤーなどは汚染前へ巻き戻さない。
 *
 * <h2>対応(Claude 仮決め)</h2>
 * <ul>
 *     <li>寄生体: 死体の表({@link SpeciesCorpses} → {@link CorpseHatchTable})と、ガスト・ブレイズの入れ替え表
 *     ({@link NetherReanimation#parasitizedTypeFor})。新しい寄生体を表に足せば、スポナーにも自動で効く。</li>
 *     <li>もうこの MOD の Mob(どのグループでも)を湧かせるスポナーはそのまま。</li>
 *     <li>寄生体の無い Mob(洞窟グモ・シルバーフィッシュ・ハスク・ストレイ・ブリーズ・他 MOD の Mob など)は、G1 のうち自然に湧く種で、
 *     その次元の決まり({@link OvergrownSpawnProfile#netherRuleAllows}: ネザーはネザーの 9 種、それ以外は地上の 11 種)に合うものから
 *     1 つを選ぶ。同じスポナーの同じ元の Mob は同じ種になる(元の Mob ごとに 1 回だけ選ぶ)。</li>
 *     <li>書き換えた Mob は id だけにする(元の Mob 用の装備・属性などの指定は寄生体に合わないので持ち越さない)。</li>
 * </ul>
 */
public final class SpawnerCorruption {

    /** ふつうのスポナーで、湧く Mob の登録を持つ項目。 */
    private static final String[] SPAWNER_KEYS = {"SpawnData", "SpawnPotentials"};
    /** 試練のスポナーで、湧く Mob の登録を持つ項目。 */
    private static final String[] TRIAL_KEYS = {"normal_config", "ominous_config", "spawn_data"};

    private SpawnerCorruption() {
    }

    /** 汚染できるスポナーか(ふつうのスポナー・試練のスポナー)。 */
    public static boolean isSpawner(BlockState state) {
        return state.is(Blocks.SPAWNER) || state.is(Blocks.TRIAL_SPAWNER);
    }

    /** 覆った直後({@link OvergrownLayerData#cover})に呼ぶ。スポナーでなければ何もしない。 */
    static void onCovered(ServerLevel level, BlockPos pos) {
        if (!isSpawner(level.getBlockState(pos))) {
            return;
        }
        BlockEntity be = level.getBlockEntity(pos);
        String[] keys = keysFor(be);
        if (keys == null || be.hasData(ModAttachments.SPAWNER_ORIGINAL)) {
            return;
        }
        CompoundTag tag = be.saveWithoutMetadata(level.registryAccess());
        CompoundTag original = new CompoundTag();
        for (String key : keys) {
            Tag value = tag.get(key);
            if (value != null) {
                original.put(key, value.copy());
            }
        }
        Map<String, String> chosen = new HashMap<>();
        boolean changed = false;
        for (String key : keys) {
            Tag value = tag.get(key);
            if (value != null) {
                changed |= rewrite(value, level, chosen);
            }
        }
        if (changed) {
            load(level, pos, be, tag);
        }
        // 読み込みの後に付ける(読み込みでアタッチメントが書き換わらないよう、読み込む NBT からは外してある)。
        be.setData(ModAttachments.SPAWNER_ORIGINAL, original);
        be.setChanged();
    }

    /** 覆いを剥がした直後({@link OvergrownLayerData#uncover})に呼ぶ。覚えた登録があれば戻す。 */
    static void onUncovered(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !be.hasData(ModAttachments.SPAWNER_ORIGINAL)) {
            return;
        }
        CompoundTag original = be.getData(ModAttachments.SPAWNER_ORIGINAL);
        be.removeData(ModAttachments.SPAWNER_ORIGINAL);
        String[] keys = keysFor(be);
        if (keys != null) {
            CompoundTag tag = be.saveWithoutMetadata(level.registryAccess());
            for (String key : keys) {
                Tag value = original.get(key);
                if (value != null) {
                    tag.put(key, value.copy());
                } else {
                    tag.remove(key);
                }
            }
            load(level, pos, be, tag);
        }
        be.setChanged();
    }

    @Nullable
    private static String[] keysFor(@Nullable BlockEntity be) {
        if (be instanceof SpawnerBlockEntity) {
            return SPAWNER_KEYS;
        }
        if (be instanceof TrialSpawnerBlockEntity) {
            return TRIAL_KEYS;
        }
        return null;
    }

    private static void load(ServerLevel level, BlockPos pos, BlockEntity be, CompoundTag tag) {
        tag.remove(AttachmentHolder.ATTACHMENTS_NBT_KEY);
        be.loadWithComponents(tag, level.registryAccess());
        BlockState state = level.getBlockState(pos);
        // 中で回る Mob の模型を作り直させる(クライアントは更新の NBT を読み直すと表示用の Mob を作り直す)。
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
    }

    /** {@code tag} の中の {@code "entity": {"id": …}} を全部寄生体へ書き換える。書き換えたら true。 */
    private static boolean rewrite(Tag tag, ServerLevel level, Map<String, String> chosen) {
        boolean changed = false;
        if (tag instanceof CompoundTag compound) {
            if (compound.contains("entity", Tag.TAG_COMPOUND)) {
                CompoundTag entity = compound.getCompound("entity");
                if (entity.contains("id", Tag.TAG_STRING)) {
                    String from = entity.getString("id");
                    String to = chosen.computeIfAbsent(from, id -> targetId(level, id));
                    if (to != null && !to.equals(from)) {
                        CompoundTag replaced = new CompoundTag();
                        replaced.putString("id", to);
                        compound.put("entity", replaced);
                        changed = true;
                    }
                }
            }
            for (String key : compound.getAllKeys()) {
                if (!key.equals("entity")) {
                    changed |= rewrite(compound.get(key), level, chosen);
                }
            }
        } else if (tag instanceof ListTag list) {
            for (Tag element : list) {
                changed |= rewrite(element, level, chosen);
            }
        }
        return changed;
    }

    @Nullable
    private static String targetId(ServerLevel level, String id) {
        EntityType<?> type = EntityType.byString(id).orElse(null);
        EntityType<?> target = type == null ? null : targetFor(level, type);
        return target == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(target).toString();
    }

    /**
     * 汚染したスポナーで {@code type} の代わりに湧かせる種。
     * 2026-10-30 の整理(退避)で寄生体・死体の表(G1・ネザーの蘇生)を外したので、この MOD 以外の Mob は
     * 萌芽体に書き換える(仮決め。寄生体を作り直したらここを戻す。前の中身は parked/ の写し)。
     */
    @Nullable
    public static EntityType<?> targetFor(ServerLevel level, EntityType<?> type) {
        if (OvergrownVegetation.MODID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace())) {
            return type; // もうこの MOD の Mob
        }
        return ModEntities.BLIGHT_FERN.get();
    }
}
