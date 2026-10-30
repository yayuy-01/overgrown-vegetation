package com.yayuy.overgrownvegetation.td;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * ターゲットブロック(TD イベント、2026-09-25)。
 *
 * <p>森の侵攻(TD イベント)が<b>狙う的</b>(ユーザー指定 2026-09-24: 「攻撃は新しいターゲットブロックを狙う」)。
 * 拠点に置いておくと、近く(192 ブロック以内。仮決め)に過繁茂のチャンクがあり、バイオマスとフェーズが条件を
 * 満たしたときに森が自動で攻めてくる。群れはこのブロックを取り囲んで殴り、耐久({@link TargetBlockEntity})が
 * 0 になったら敗北 —— 侵攻は失敗し、拠点の周りに汚染が噴き出す(ユーザー指定 2026-09-24)。
 *
 * <h2>「本当に無くなった」の見分け方</h2>
 * {@link #onRemove} はサーバーで<b>別のブロックに置き換わったとき</b>(掘られた・爆発・/setblock・敗北で消された)
 * にだけ来る。チャンクの読み込み解除では来ないので、ここが「ターゲットが無くなった」の唯一の入口になる。
 * 敗北で消したとき({@link TdEvents#isDefeating})は敗北の処理に任せ、それ以外は {@link TdEvents#onTargetRemoved} へ知らせる
 * (侵攻中なら負け、予告中なら取りやめ)。
 * ピストンでは動かない(ブロックエンティティ付きのブロックはバニラで押せない + pushReaction BLOCK)。
 *
 * <h2>進行中は掘れない</h2>
 * 予告中・侵攻中のターゲットは、サバイバル・アドベンチャーのプレイヤーには掘れない({@link #getDestroyProgress} が 0)。
 * 仮決め 2026-09-25(レビュー指摘): 掘れた頃は、耐久 10% の知らせを見てから掘れば「中止・払い戻し」で負けを逃れられ、
 * 予告のたびに掘って置き直せば侵攻そのものを永久に避けられた(どちらもユーザー指定「ターゲットが壊れたら負け」を骨抜きにする)。
 * クリエイティブは掘れる(そのときは負け。先に {@code /ov td stop})。クライアントは進行中かを知らないので、
 * ブロックエンティティの同期の印({@link TargetBlockEntity#lockedOnClient})で同じ答えを出す(ひびが進んで戻る見た目を出さない)。
 *
 * <p>見た目はバニラのテクスチャだけで作った仮のもの(側面 = ロードストーン、上面 = 的)。素材(png)は
 * ユーザーの作品なので新しく作らない。汚染は受けない(タグ {@code corruption_immune})。
 */
public class TargetBlock extends BaseEntityBlock {

    public static final MapCodec<TargetBlock> CODEC = simpleCodec(TargetBlock::new);

    public TargetBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL; // BaseEntityBlock の既定は INVISIBLE
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TargetBlockEntity(pos, state);
    }

    /**
     * ブロックを持っているときは状態表示に使わず、そのまま隣へ置けるようにする
     * (ターゲットの周りを壁で囲う作業の邪魔をしない。仮決め 2026-09-25)。
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hitResult) {
        if (stack.getItem() instanceof BlockItem) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * 右クリック: 収納の画面を開く(2026-09-25、ユーザー指定「ラージチェスト 4 つ分のインベントリ」。耐久も画面に出る)。
     * 素手でしゃがんで右クリック: 今までどおり耐久と侵攻の状態をアクションバーに出す(画面を開くとアクションバーが隠れるので分けた。仮決め)。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof TargetBlockEntity target) {
            if (player.isSecondaryUseActive()) {
                player.displayClientMessage(target.statusLine(server), true);
            } else {
                player.openMenu(target);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** 進行中のターゲットは掘れない(上の「進行中は掘れない」)。サーバーは台帳で、クライアントは同期の印で決める。 */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (lockedForPlayers(level, pos)) {
            return 0.0F;
        }
        return super.getDestroyProgress(state, player, level, pos);
    }

    /** 掘り始めた瞬間(サーバー): 進行中で掘れないことをアクションバーで知らせる。 */
    @Override
    protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
        if (level instanceof ServerLevel server && !player.isCreative() && TdEvents.isActiveTarget(server, pos)) {
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.target_block.locked"), true);
        }
        super.attack(state, level, pos, player);
    }

    /** 進行中(予告中・侵攻中)のターゲットか。サーバーは TD の台帳、クライアントはブロックエンティティの同期の印。 */
    static boolean lockedForPlayers(BlockGetter level, BlockPos pos) {
        if (level instanceof ServerLevel server) {
            return TdEvents.isActiveTarget(server, pos);
        }
        return level.getBlockEntity(pos) instanceof TargetBlockEntity target && target.lockedOnClient();
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server) {
            if (level.getBlockEntity(pos) instanceof TargetBlockEntity target) {
                // 収納の中身は散らばる(チェストと同じ。掘った・爆発・攻め手に壊された予備のターゲット・負けで消えたとき)。
                Containers.dropContents(level, pos, target.storage());
            }
            TargetBlockEntity.clearCracks(server, pos);
            // 敗北で消したときは敗北の処理が済ませる。それ以外は「無くなった」(SHARED API。侵攻中なら負け)。
            if (!TdEvents.isDefeating(server, pos)) {
                TdEvents.onTargetRemoved(server, pos);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
