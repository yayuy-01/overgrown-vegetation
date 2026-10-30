package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.neoforged.neoforge.common.ItemAbilities;

import javax.annotation.Nullable;

/**
 * 「過繫茂したドア / チェスト / 樽」({@link OvergrownDoorBlock} / {@link OvergrownChestBlock} /
 * {@link OvergrownBarrelBlock})に共通する挙動をまとめた補助クラス (2026-09-11)。
 *
 * <h2>仕様(ユーザー要望)</h2>
 * <ul>
 *     <li>蔦やコケに覆われているせいで<b>開けるときに確率で開けられない</b>(閉じるのは常に可)。</li>
 *     <li>覆われ具合は {@link #OVERGROWTH} プロパティ(0〜{@value #MAX_LEVEL})。0 = きれい(必ず開く)、
 *         {@value #MAX_LEVEL} = びっしり。開けられない確率は段階に比例し、最大で
 *         {@code overgrownContainerStuckChanceAtMax}(既定 0.75)。</li>
 *     <li>対処は 2 つ: <b>ハサミ</b>で先に右クリックして蔦やコケを剥がす({@link #tryShear}、段階が 0 になる)か、
 *         <b>壊して置き直す</b>(置いたときは必ず段階 0 — {@code getStateForPlacement} 側で設定)。</li>
 *     <li>剥がしても<b>時間経過で再び覆われていく</b>: ランダムティックごとに
 *         {@code overgrownContainerRegrowChance}(既定 0.35)の確率で段階が 1 つ上がる({@link #regrow})。</li>
 * </ul>
 *
 * <h2>汚染による変換</h2>
 * <p>木のドア({@link BlockTags#WOODEN_DOORS})・チェスト({@link Blocks#CHEST})・樽({@link Blocks#BARREL})が
 * 汚染されると、対応する過繫茂ブロックへ変換される({@link #corruptedStateFor})。<b>バニラの扉は 2026-10-05 から
 * 覆われた扉</b>(同じ木の扉に苔。{@code covered/CoveredInteractables} が同じ「時々開かない」を持つ)になり、
 * 過繫茂したドアになるのは覆われた版の無い他 MOD の木のドアだけ。チェスト・樽は
 * {@code corruptBlockEntities}(既定 false)に関係なく変換され、<b>中身・名前・ロック・未開封のルートテーブルは
 * そのまま引き継ぐ</b>({@link #placeConverted}: 変換前の BlockEntity を NBT に退避 → 中身を空にしてから置き換え
 * (置き換え時のドロップ防止)→ 新しい BlockEntity へ読み戻す)。ドアは上下 2 マス、ラージチェストは 2 マスを
 * 同時に変換する(片方だけ変換すると形状更新で壊れる/見た目が割れるため)。変換直後は段階 {@value #MAX_LEVEL}。
 */
public final class OvergrownContainers {

    /** 蔦・コケの覆われ具合(0 = きれい、{@value #MAX_LEVEL} = びっしり)。 */
    public static final IntegerProperty OVERGROWTH = IntegerProperty.create("overgrowth", 0, 3);
    public static final int MAX_LEVEL = 3;

    /** ブロック側で段階を書き換える手段(ドアは上下 2 マス、他は 1 マス)。 */
    @FunctionalInterface
    public interface LevelSetter {
        void set(Level level, BlockPos pos, BlockState state, int overgrowth);
    }

    private OvergrownContainers() {
    }

    // ------------------------------------------------------------------
    // 汚染による変換
    // ------------------------------------------------------------------

    /** チェスト・樽のように BlockEntity を持つが、中身を保ったまま過繫茂版へ変換できるブロックか。 */
    public static boolean isConvertibleContainer(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.BARREL);
    }

    /**
     * {@code state} がドア/チェスト/樽なら対応する過繫茂ブロックの状態(向きなどを引き継ぎ、段階 {@value #MAX_LEVEL})を返す。
     * それ以外は {@code null}(通常の変換フローへ)。{@link CorruptionRegistry#corrupt(BlockState, RandomSource)} が
     * 汎用ブロックへの置き換え抽選より<b>前</b>に呼ぶ — チェストが汎用ブロックに化けて中身が消えるのを防ぐため。
     */
    @Nullable
    public static BlockState corruptedStateFor(BlockState state) {
        Block target;
        if (state.is(BlockTags.WOODEN_DOORS) && state.getBlock() instanceof DoorBlock) {
            target = ModBlocks.OVERGROWN_DOOR.get();
        } else if (state.is(Blocks.CHEST)) {
            target = ModBlocks.OVERGROWN_CHEST.get();
        } else if (state.is(Blocks.BARREL)) {
            target = ModBlocks.OVERGROWN_BARREL.get();
        } else {
            return null;
        }
        BlockState result = CorruptionRegistry.copyMatchingProperties(state, target.defaultBlockState());
        return result.setValue(OVERGROWTH, MAX_LEVEL);
    }

    /**
     * {@code corrupted} が過繫茂ドア/チェスト/樽なら、その設置を引き受ける
     * ({@link CorruptionRegistry#placeCorrupted} から)。
     *
     * @return 引き受けた(設置まで済ませた)ら true。false なら呼び出し側が通常どおり設置する。
     */
    public static boolean placeConverted(LevelAccessor level, BlockPos pos, BlockState previous,
                                         BlockState corrupted, int flags) {
        if (corrupted.getBlock() instanceof DoorBlock && previous.getBlock() instanceof DoorBlock) {
            // 過繫茂したドアと、覆われた扉(バニラの扉は全部これになる。2026-10-05)。どちらも上下 2 マスいっしょに。
            placeDoor(level, pos, previous, corrupted, flags);
            return true;
        }
        if (corrupted.is(ModBlocks.OVERGROWN_CHEST.get())) {
            placeChest(level, pos, previous, corrupted, flags);
            return true;
        }
        if (corrupted.is(ModBlocks.OVERGROWN_BARREL.get())) {
            replaceKeepingContents(level, pos, corrupted, flags);
            return true;
        }
        return false;
    }

    private static void placeDoor(LevelAccessor level, BlockPos pos, BlockState previous, BlockState corrupted, int flags) {
        if (!previous.hasProperty(DoorBlock.HALF)) {
            level.setBlock(pos, corrupted, flags);
            return;
        }
        DoubleBlockHalf half = previous.getValue(DoorBlock.HALF);
        BlockPos otherPos = half == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        BlockState other = level.getBlockState(otherPos);
        level.setBlock(pos, corrupted.setValue(DoorBlock.HALF, half), flags);
        if (other.getBlock() == previous.getBlock() && other.hasProperty(DoorBlock.HALF)
                && other.getValue(DoorBlock.HALF) != half) {
            // もう片方の半分も同じ向き・開閉状態で同時に変換する(蝶番・電源状態は元の半分のものを引き継ぐ)。
            // 覆われた鉄の扉は手で開かないので覆われ具合を持たない。
            BlockState otherCorrupted = CorruptionRegistry.copyMatchingProperties(other, corrupted)
                    .setValue(DoorBlock.HALF, other.getValue(DoorBlock.HALF));
            if (otherCorrupted.hasProperty(OVERGROWTH)) {
                otherCorrupted = otherCorrupted.setValue(OVERGROWTH, MAX_LEVEL);
            }
            level.setBlock(otherPos, otherCorrupted, flags);
        }
    }

    private static void placeChest(LevelAccessor level, BlockPos pos, BlockState previous, BlockState corrupted, int flags) {
        replaceKeepingContents(level, pos, corrupted, flags);
        if (!previous.hasProperty(ChestBlock.TYPE) || previous.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return;
        }
        // ラージチェスト: 相方も同時に変換する。片方だけ変換すると、バニラのチェストと過繫茂チェストが
        // 互いを「相方」と認めなくなり、見た目も中身も割れてしまう。
        Direction toPartner = ChestBlock.getConnectedDirection(previous);
        BlockPos partnerPos = pos.relative(toPartner);
        BlockState partner = level.getBlockState(partnerPos);
        if (partner.is(Blocks.CHEST) && partner.hasProperty(ChestBlock.TYPE)
                && partner.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                && ChestBlock.getConnectedDirection(partner) == toPartner.getOpposite()) {
            BlockState partnerCorrupted = CorruptionRegistry.copyMatchingProperties(partner,
                    ModBlocks.OVERGROWN_CHEST.get().defaultBlockState()).setValue(OVERGROWTH, MAX_LEVEL);
            replaceKeepingContents(level, partnerPos, partnerCorrupted, flags);
        }
    }

    /**
     * {@code pos} のコンテナブロックを、中身を保ったまま {@code replacement} に置き換える。
     * 変換前の BlockEntity の NBT(中身・カスタム名・ロック・ルートテーブル)を退避してから
     * {@code clearContent()} で空にし(置き換え時の {@code onRemove} による中身ドロップを防ぐ)、
     * 置き換え後の BlockEntity へ読み戻す。
     *
     * <p><b>未開封のルートテーブル付きチェスト(村・ダンジョン・難破船など)の注意 (2026-09-11 バグ修正)</b>:
     * バニラの {@code ChestBlock#onRemove} → {@code Containers.dropContentsOnDestroy} は中身を
     * {@code Container#getItem} で 1 スロットずつ読むが、{@code RandomizableContainerBlockEntity#getItem} は
     * その場でルートテーブルを展開する({@code unpackLootTable(null)})。つまり中身を空にしただけでは、
     * 置き換えの瞬間に<b>旧チェストがルートを生成して地面にばら撒き</b>、退避した NBT の {@code LootTable} で
     * 新チェストを開けたときに<b>もう一度生成</b>される(= アイテム複製)。退避後に旧 BlockEntity の
     * ルートテーブルを {@code setLootTable(null)} で外してから空にすることで防ぐ。新チェスト側は退避 NBT の
     * {@code LootTable} を引き継ぐので、開封時に 1 回だけ生成される。
     */
    private static void replaceKeepingContents(LevelAccessor level, BlockPos pos, BlockState replacement, int flags) {
        BlockEntity old = level.getBlockEntity(pos);
        CompoundTag saved = null;
        if (old != null) {
            saved = old.saveWithoutMetadata(level.registryAccess());
            if (old instanceof RandomizableContainer randomizable) {
                // 先にルートテーブルを外す。これをしないと下の clearContent 後の onRemove(dropContents → getItem)で
                // 旧チェストがルートを展開してドロップし、新チェストと二重になる。
                randomizable.setLootTable(null);
            }
            if (old instanceof net.minecraft.world.Container container) {
                container.clearContent();
            }
        }
        level.setBlock(pos, replacement, flags);
        BlockEntity fresh = level.getBlockEntity(pos);
        if (saved != null && fresh != null) {
            fresh.loadWithComponents(saved, level.registryAccess());
            fresh.setChanged();
        }
    }

    // ------------------------------------------------------------------
    // 開閉・ハサミ・再繁茂
    // ------------------------------------------------------------------

    /** 段階 {@code overgrowth} のときに「開けられない」確率(0〜1)。 */
    public static double stuckChance(int overgrowth) {
        if (overgrowth <= 0) {
            return 0.0D;
        }
        double atMax = OvergrownVegetationConfig.COMMON.overgrownContainerStuckChanceAtMax.get();
        return atMax * Math.min(overgrowth, MAX_LEVEL) / (double) MAX_LEVEL;
    }

    /**
     * プレイヤーが開けようとした。サーバー側でのみ呼ぶ。
     *
     * @return true = 開けてよい。false = 蔦に絡まって開かなかった(音・パーティクル・メッセージは出し終えている)
     */
    public static boolean tryOpen(Level level, BlockPos pos, BlockState state, @Nullable Player player) {
        return tryOpen(level, pos, state, player, STUCK_MESSAGE);
    }

    /** アクションバーの文言。開くものは「開かない」、ボタン・レバー(覆われたブロック、2026-10-05)は「動かない」。 */
    public static final String STUCK_MESSAGE = "message.overgrown_vegetation.overgrown_container.stuck";
    public static final String STUCK_MOVE_MESSAGE = "message.overgrown_vegetation.overgrown_container.stuck_move";

    /** {@link #tryOpen(Level, BlockPos, BlockState, Player)} の文言を選べる版。 */
    public static boolean tryOpen(Level level, BlockPos pos, BlockState state, @Nullable Player player, String messageKey) {
        if (!state.hasProperty(OVERGROWTH)) {
            return true;
        }
        double chance = stuckChance(state.getValue(OVERGROWTH));
        if (chance <= 0.0D || level.random.nextDouble() >= chance) {
            return true;
        }
        stuckFeedback(level, pos, player, messageKey);
        return false;
    }

    /**
     * 開けられなかったときの演出: 物がきしむ音 + 蔦の破片 + アクションバーのメッセージ。
     * 音はバニラの木のドアの開閉音をピッチ 0.45〜0.55 まで落としたもの(低くゆっくり鳴らすと「引っかかって
     * きしむ」音になる。2026-09-11: 以前の蔦を壊す草っぽい音から変更)。
     */
    public static void stuckFeedback(Level level, BlockPos pos, @Nullable Player player) {
        stuckFeedback(level, pos, player, STUCK_MESSAGE);
    }

    /** {@link #stuckFeedback(Level, BlockPos, Player)} の文言を選べる版。 */
    public static void stuckFeedback(Level level, BlockPos pos, @Nullable Player player, String messageKey) {
        level.playSound(null, pos, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 1.0F,
                0.45F + level.random.nextFloat() * 0.1F);
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.VINE.defaultBlockState()),
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 8, 0.3D, 0.3D, 0.3D, 0.0D);
        }
        if (player != null) {
            player.displayClientMessage(Component.translatable(messageKey), true);
        }
    }

    /**
     * ハサミで右クリックされたときの処理({@code useItemOn} から)。ハサミでなければ
     * {@link ItemInteractionResult#PASS_TO_DEFAULT_BLOCK_INTERACTION}(通常の開閉へ)。
     * ハサミなら段階を 0 に戻し、ハサミの耐久を 1 減らし、音とパーティクルを出す。
     * 既に段階 0 なら何もしない(ハサミの消費も無し)。
     */
    public static ItemInteractionResult tryShear(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                 Player player, InteractionHand hand, LevelSetter setter) {
        if (!stack.canPerformAction(ItemAbilities.SHEARS_HARVEST) || !state.hasProperty(OVERGROWTH)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (state.getValue(OVERGROWTH) <= 0) {
            // 剥がすものが無い。ハサミを構えたまま普通に開けられるよう、通常の操作へ流す。
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        setter.set(level, pos, state, 0);
        shearEffects(stack, level, pos, player, hand);
        return ItemInteractionResult.CONSUME;
    }

    /**
     * ハサミで剥がしたときの消耗と演出(ハサミの耐久 1・音・苔の破片・ゲームイベント)。サーバー側で呼ぶ。
     * 覆われた扉などを元のブロックへ戻すとき({@code covered/CoveredInteractables}、2026-10-05 夜)も同じものを出す。
     */
    public static void shearEffects(ItemStack stack, Level level, BlockPos pos, Player player, InteractionHand hand) {
        stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
        level.playSound(null, pos, SoundEvents.GROWING_PLANT_CROP, SoundSource.BLOCKS, 1.0F, 1.0F);
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MOSS_BLOCK.defaultBlockState()),
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 16, 0.35D, 0.35D, 0.35D, 0.0D);
        }
        level.gameEvent(player, net.minecraft.world.level.gameevent.GameEvent.SHEAR, pos);
    }

    /**
     * ランダムティック: {@code overgrownContainerRegrowChance} の確率で段階を 1 つ上げる(最大 {@value #MAX_LEVEL})。
     * 剥がした後も時間が経てばまた覆われていく(要望)。
     */
    public static void regrow(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, LevelSetter setter) {
        if (!state.hasProperty(OVERGROWTH)) {
            return;
        }
        int current = state.getValue(OVERGROWTH);
        if (current >= MAX_LEVEL) {
            return;
        }
        double chance = OvergrownVegetationConfig.COMMON.overgrownContainerRegrowChance.get();
        if (chance > 0.0D && random.nextDouble() < chance) {
            setter.set(level, pos, state, current + 1);
        }
    }

    /** 1 マスのブロック用の {@link LevelSetter}(チェスト・樽)。BlockEntity は同じブロックなので保持される。 */
    public static void setSingle(Level level, BlockPos pos, BlockState state, int overgrowth) {
        level.setBlock(pos, state.setValue(OVERGROWTH, overgrowth), Block.UPDATE_ALL);
    }
}
