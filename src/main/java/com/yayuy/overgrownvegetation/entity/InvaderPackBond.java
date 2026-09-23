package com.yayuy.overgrownvegetation.entity;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 群れの<b>所属そのもの</b>(ユーザー要望 2026-09-15:
 * 「近くにいたら『群れ』グループを作り、常に同じ個体の群れを形成し続けるように」)。
 *
 * <p>それまでの群れは「そのとき近くに居る仲間」を毎回数え直すだけで、<b>所属を持っていなかった</b>。
 * そのため通りすがりの個体が一時的に群れの一員として扱われたり、少し離れただけで別の群れに
 * 数えられたりしていた。ここからは<b>個体が群れの ID を憶える</b>。
 *
 * <h2>置き場所</h2>
 * <b>ワールド側に名簿は作らない。</b> ID は個体が持ち、エンティティと一緒に保存される。
 * こうすると後始末が要らない —— 死ねば ID ごと消えるし、チャンクがアンロードされても
 * next ロードでそのまま戻る。名簿を持つと「死んだ個体を消す」「アンロード中の個体をどう数えるか」が
 * 全部こちらの仕事になる。
 *
 * <p>群れの実体は<b>「同じ ID を持つ個体の集まり」</b>でしかない。誰がどの群れかを知りたいときは
 * 近くを見て ID を照合する({@link InvaderPack#packMatesNear})。
 *
 * <h2>所属の決まり方</h2>
 * すべて {@link InvaderPackBonds} にある。作る・入る・合流する・抜ける、の 4 つ。
 */
public final class InvaderPackBond {

    /** 所属している群れ。まだどこにも属していなければ {@code null}。 */
    @Nullable
    private UUID id;
    /**
     * 最後に同じ群れの仲間を見かけた {@code tickCount}。
     * これが古くなりすぎたら「群れはもう無い」とみなして所属を捨てる。
     */
    private int lastSeenTick;
    /** 編成が揃っている扱いでいられる期限({@code tickCount})。0 なら揃っていない。 */
    private int completeUntilTick;

    @Nullable
    public UUID id() {
        return this.id;
    }

    public void setId(@Nullable UUID id) {
        this.id = id;
    }

    public int lastSeenTick() {
        return this.lastSeenTick;
    }

    public void markSeen(int tick) {
        this.lastSeenTick = tick;
    }

    /**
     * <b>編成が揃っている群れの一員でいられる期限</b>({@code tickCount})。
     * この間は消えない({@link InvaderDespawn})。
     *
     * <p>期限という形にしてあるのは<b>ちらつかせない</b>ため。編成の確認は
     * {@code invaderPackBondIntervalTicks} ごとの 1 回しかやらないので、
     * その瞬間にたまたま 1 体が離れていただけで永続性が落ちると、
     * 「揃っている群れなのに時々消える」という分かりにくい挙動になる。
     */
    public boolean completeAt(int tick) {
        return tick < this.completeUntilTick;
    }

    public void markComplete(int tick, int grace) {
        this.completeUntilTick = tick + grace;
    }

    public void clearComplete() {
        this.completeUntilTick = 0;
    }

    /** {@code a} と {@code b} が一緒に動いてよいか。<b>どちらかが無所属なら一緒に動く</b>。 */
    public static boolean shares(@Nullable UUID a, @Nullable UUID b) {
        return a == null || b == null || a.equals(b);
    }

    /**
     * 保存するのは<b>所属だけ</b>。
     *
     * <p>{@link #lastSeenTick} は保存しない —— 比べる相手の {@code tickCount} が
     * <b>ロードのたびに 0 に戻る</b>ので、保存した値をそのまま読むと
     * 「最後に見たのが未来」という状態になり、解散の判定が数千 tick 効かなくなる。
     */
    public void save(CompoundTag tag) {
        if (this.id != null) {
            tag.putUUID("PackId", this.id);
        }
        tag.putBoolean("PackComplete", this.completeUntilTick > 0);
    }

    /**
     * 「編成が揃っていた」という事実だけは持ち越す。
     *
     * <p>持ち越さないと、<b>ロード直後の数十 tick だけ消える扱いに戻る</b> ——
     * 次の編成確認が来る前にプレイヤーが遠ざかると、揃っていた群れがそこで刈られてしまう。
     * 読み込み時は猶予いっぱいから数え直し、次の確認で実態に合わせ直す。
     */
    public void load(CompoundTag tag, int grace) {
        this.id = tag.hasUUID("PackId") ? tag.getUUID("PackId") : null;
        this.lastSeenTick = 0;
        this.completeUntilTick = tag.getBoolean("PackComplete") ? grace : 0;
    }

    /** 表示用の短い ID(先頭 8 桁)。群れ検査器のチャットで使う。 */
    public String shortId() {
        return this.id == null ? "-" : this.id.toString().substring(0, 8);
    }
}
