package com.yayuy.overgrownvegetation.td;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.menu.TargetStorageMenu;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * ターゲットブロックの中身(TD イベント、2026-09-25)。<b>耐久(HP)</b>と<b>収納</b>({@link TargetStorage}。同日のユーザー指定
 * 「ラージチェスト 4 つ分のインベントリ。TD 中は mob のドロップを自動で入れる」)を持つ。収納の画面は右クリック({@link TargetBlock#useWithoutItem})。
 *
 * <h2>耐久</h2>
 * <ul>
 *     <li>最大値は {@code design/td.json} の {@code targetMaxHp}(仮決め 2026-09-25: 1000)。
 *     <b>最初に置いたとき</b>に決めて NBT に保存する。あとで td.json を変えても、置いてあるものの最大値は
 *     変わらない({@link #resetHp} を呼ぶまで)—— 侵攻の途中で数値を読み直しても、いまの戦いの前提が崩れないように。</li>
 *     <li>減らすのは群れの攻撃だけ({@code TdSiege} → {@link #damage})。剣で殴っても減らない
 *     (プレイヤーがうっかり自分で削らないように。仮決め 2026-09-25)。</li>
 *     <li>0 になった一撃で {@link TdEvents#onTargetDestroyed} を呼ぶ(= 敗北)。ブロックを消すのは TdEvents 側で、
 *     <b>消す前に</b>知らせることで、消えたときの {@link TargetBlock#onRemove} が「耐久 0 以外で無くなった」
 *     と取り違えない({@link TdEvents#isDefeating} で見分ける)。</li>
 *     <li>自然には回復しない。侵攻の始まりに満タンへ戻すかどうかは TdEvents が決める({@link #resetHp})。</li>
 * </ul>
 *
 * <h2>見た目での知らせ(クライアントへ HP は送らない)</h2>
 * 右クリックで「耐久 x / y」と侵攻の状態がアクションバーに出る({@link TargetBlock#useWithoutItem})。
 * それとは別に、攻撃を受けたら削れた割合ぶんの<b>ひび</b>(採掘のひびと同じ 10 段階)・叩かれる音・
 * 残り 50% / 25% / 10% を切ったときのチャットを近くのプレイヤーへ出す(どれも仮決め 2026-09-25)。
 * ひびはクライアントが 400 tick 更新されないと消すので、「いま殴られている」の目印にもなる。
 */
public class TargetBlockEntity extends BlockEntity implements MenuProvider {

    private static final String TAG_HP = "Hp";
    private static final String TAG_MAX_HP = "MaxHp";
    /** クライアントへの同期だけに載せる「進行中で掘れない」の印(保存しない)。 */
    private static final String TAG_LOCKED = "Locked";
    /** 収納の中身({@code ContainerHelper} の書き方。チェストと同じ名前)。 */
    private static final String TAG_ITEMS = "Items";

    /** td.json が読めない・壊れた値のときの最大耐久(td.json の既定値と同じ。仮決め 2026-09-25)。 */
    private static final float FALLBACK_MAX_HP = 1000.0F;
    /** この割合を切ったらチャットで知らせる(仮決め 2026-09-25)。大きい順。 */
    private static final float[] WARN_FRACTIONS = {0.5F, 0.25F, 0.1F};
    /** 知らせを受け取るプレイヤーの範囲(ブロック。仮決め 2026-09-25)。 */
    private static final double WARN_RADIUS = 64.0D;
    /** 叩かれる音の間隔(tick)。群れは 20 tick ごとに何体ぶんもまとめて殴るので、1 秒に 1 回へ間引く。 */
    private static final int SOUND_INTERVAL_TICKS = 20;
    /** ひびを送り直す間隔(tick)。段階が変わったときは待たずに送る。 */
    private static final int CRACK_RESEND_TICKS = 100;

    /** 残りの耐久。 */
    private float hp;
    /** 最大耐久。0 以下 = まだ一度も決めていない(置いたばかり)。 */
    private float maxHp;

    /**
     * クライアントだけが使う: 進行中(予告中・侵攻中)のターゲットか。サーバーは送るときに台帳から決め({@link #getUpdateTag})、
     * 保存はしない。{@link TargetBlock#getDestroyProgress} がクライアントでも 0 を返し、掘るひびが進んで戻る見た目を出さないため。
     */
    private boolean lockedOnClient;

    // 以下は演出の間引き用(保存しない)。
    private long lastSoundTick = Long.MIN_VALUE;
    private long lastCrackTick = Long.MIN_VALUE;
    private int lastCrackStage = -1;

    /** 収納(ラージチェスト 4 つ分。{@link TargetStorage})。 */
    private final TargetStorage storage = new TargetStorage(this);

    public TargetBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TARGET_BLOCK.get(), pos, state);
    }

    // ------------------------------------------------------------------ 収納(2026-09-25)

    /** 収納(攻め手のドロップの入れ先・右クリックの画面・ホッパー)。 */
    public TargetStorage storage() {
        return storage;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.overgrown_vegetation.target_block");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new TargetStorageMenu(containerId, inventory, storage, hpData());
    }

    /** 画面に出す耐久(残り・最大を 16 ビットずつに分けて渡す。{@code ContainerData} の値は short で送られるため)。 */
    private ContainerData hpData() {
        return new ContainerData() {
            @Override
            public int get(int index) {
                int value = Mth.ceil(index < 2 ? hp() : maxHp());
                return (index & 1) == 0 ? value & 0xFFFF : (value >>> 16) & 0xFFFF;
            }

            @Override
            public void set(int index, int value) {
                // 画面からは変えない
            }

            @Override
            public int getCount() {
                return TargetStorageMenu.HP_DATA_COUNT;
            }
        };
    }

    // ------------------------------------------------------------------ 耐久

    /** 残りの耐久。 */
    public float hp() {
        ensureInitialized();
        return hp;
    }

    /** 最大耐久。 */
    public float maxHp() {
        ensureInitialized();
        return maxHp;
    }

    /** 最大耐久を td.json の今の値で決め直し、満タンにする(ひびも消す)。 */
    public void resetHp() {
        maxHp = configuredMaxHp();
        hp = maxHp;
        if (level instanceof ServerLevel server) {
            clearCracks(server, worldPosition);
        }
        lastCrackStage = -1;
        setChanged();
    }

    /**
     * 耐久を削る(サーバー側だけ)。
     *
     * @param amount 削る量(0 以下・NaN は無視)
     * @param source 殴ったもの(演出・記録用。null 可)
     * @return <b>この一撃で</b>耐久が 0 になったら true。そのときは戻る前に
     *         {@link TdEvents#onTargetDestroyed} を呼んである。すでに 0 だったとき・削れなかったときは false。
     */
    public boolean damage(float amount, @Nullable Entity source) {
        if (!(level instanceof ServerLevel server) || isRemoved() || !(amount > 0.0F)) {
            return false;
        }
        ensureInitialized();
        if (hp <= 0.0F) {
            return false; // もう壊れている(敗北処理が終わるまでの間に届いた攻撃)
        }
        float before = hp;
        hp = Math.max(0.0F, hp - amount);
        setChanged();
        if (hp <= 0.0F) {
            // ブロックを消すより先に知らせる(SHARED API)。TdEvents は敗北の状態を立ててからブロックを消す。
            TdEvents.onTargetDestroyed(server, worldPosition);
            return true;
        }
        feedback(server, before);
        return false;
    }

    /** 置いたばかり(NBT に最大耐久が無い)なら、td.json の値で満タンにする。 */
    private void ensureInitialized() {
        if (maxHp <= 0.0F) {
            maxHp = configuredMaxHp();
            hp = maxHp;
            setChanged();
        }
    }

    private static float configuredMaxHp() {
        float value;
        try {
            value = (float) DesignData.td().targetMaxHp();
        } catch (RuntimeException e) {
            value = FALLBACK_MAX_HP; // 設計データの読み込み前など
        }
        return Float.isFinite(value) && value > 0.0F ? value : FALLBACK_MAX_HP;
    }

    // ------------------------------------------------------------------ 演出

    private void feedback(ServerLevel server, float before) {
        long now = server.getGameTime();
        float max = Math.max(1.0E-3F, maxHp);

        // ひび(0〜9 段階 = 削れた割合)。段階が変わったとき、またはクライアント側で消える前に送り直す。
        int stage = Mth.clamp((int) Math.floor((1.0F - hp / max) * 10.0F), 0, 9);
        if (stage != lastCrackStage || now - lastCrackTick >= CRACK_RESEND_TICKS) {
            server.destroyBlockProgress(crackId(worldPosition), worldPosition, stage);
            lastCrackStage = stage;
            lastCrackTick = now;
        }

        // 叩かれる音(ゾンビが鉄の扉を叩く音。「拠点が殴られている」と分かる音を借りる)。
        if (now - lastSoundTick >= SOUND_INTERVAL_TICKS) {
            lastSoundTick = now;
            server.playSound(null, worldPosition, SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, SoundSource.BLOCKS,
                    0.6F, 0.8F + server.random.nextFloat() * 0.3F);
        }

        // 残り 50% / 25% / 10% を切った瞬間だけチャットで知らせる(状態を持たずに済むよう、前後の値で判定)。
        for (float fraction : WARN_FRACTIONS) {
            float threshold = max * fraction;
            if (before > threshold && hp <= threshold) {
                warnNearby(server, Math.round(fraction * 100.0F));
                break;
            }
        }
    }

    private void warnNearby(ServerLevel server, int percent) {
        Component line = Component.translatable("message.overgrown_vegetation.target_block.damaged",
                worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), percent);
        double radiusSq = WARN_RADIUS * WARN_RADIUS;
        for (ServerPlayer player : server.players()) {
            if (player.distanceToSqr(worldPosition.getCenter()) <= radiusSq) {
                player.sendSystemMessage(line);
            }
        }
    }

    /**
     * ひびの送り主の番号。エンティティの番号(正の数)とぶつからないよう負の数にする
     * (クライアントはこの番号ごとにひびを 1 つ覚える)。
     */
    private static int crackId(BlockPos pos) {
        return -1 - (pos.hashCode() & 0x3FFFFFFF);
    }

    /** ひびを消す(満タンに戻したとき・ブロックが無くなったとき)。 */
    static void clearCracks(ServerLevel server, BlockPos pos) {
        server.destroyBlockProgress(crackId(pos), pos, -1);
    }

    // ------------------------------------------------------------------ 右クリックの表示

    /** 「耐久 x / y — 侵攻の状態」の 1 行(アクションバー用)。 */
    Component statusLine(ServerLevel server) {
        ensureInitialized();
        return Component.translatable("message.overgrown_vegetation.target_block.status",
                Mth.ceil(hp), Mth.ceil(maxHp), eventState(server));
    }

    private Component eventState(ServerLevel server) {
        if (TdEvents.isActiveTarget(server, worldPosition)) {
            return Component.translatable(TdEvents.isPaused(server)
                    ? "message.overgrown_vegetation.target_block.state.paused"
                    : "message.overgrown_vegetation.target_block.state.targeted");
        }
        BlockPos other = TdEvents.activeTarget(server);
        if (other != null) {
            return Component.translatable("message.overgrown_vegetation.target_block.state.elsewhere_at",
                    other.getX(), other.getY(), other.getZ());
        }
        if (TdEvents.activeEventId(server) != null) {
            return Component.translatable("message.overgrown_vegetation.target_block.state.elsewhere");
        }
        return Component.translatable("message.overgrown_vegetation.target_block.state.none");
    }

    // ------------------------------------------------------------------ 台帳(TdTargets)

    /** 台帳にはここで載せる —— {@code onLoad} は次の tick まで遅れるので(TdTargets の説明)。 */
    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level != null && !level.isClientSide()) {
            TdTargets.add(level, this);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide()) {
            TdTargets.add(this.level, this);
            ensureInitialized(); // 置いたばかりなら、ここで最大耐久が決まる
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (this.level != null && !this.level.isClientSide()) {
            TdTargets.remove(this.level, this.worldPosition, this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (this.level != null && !this.level.isClientSide()) {
            TdTargets.remove(this.level, this.worldPosition, this);
        }
    }

    // ------------------------------------------------------------------ 掘れない印の同期

    /** クライアント: 進行中のターゲットか(掘れない)。サーバーでは使わない(台帳 {@link TdEvents#isActiveTarget} を見る)。 */
    public boolean lockedOnClient() {
        return lockedOnClient;
    }

    /**
     * 掘れない印をクライアントへ送り直す(イベントが始まった・終わったとき、{@code TdTicker} から)。読み込まれていなければ何もしない
     * (読み込まれたときのチャンクのデータに {@link #getUpdateTag} が載る)。
     */
    static void syncLock(ServerLevel level, BlockPos pos) {
        TargetBlockEntity target = TdTargets.get(level, pos);
        if (target != null && !target.isRemoved() && level.getBlockState(pos).getBlock() instanceof TargetBlock) {
            BlockState state = target.getBlockState();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** クライアントへ送る中身: 掘れない印だけ(耐久は送らない)。空だと受け手が読まないので、false も必ず書く。 */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG_LOCKED, level instanceof ServerLevel server && TdEvents.isActiveTarget(server, worldPosition));
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ------------------------------------------------------------------ 保存

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(TAG_MAX_HP)) {
            maxHp = tag.getFloat(TAG_MAX_HP);
            hp = tag.contains(TAG_HP) ? Mth.clamp(tag.getFloat(TAG_HP), 0.0F, Math.max(0.0F, maxHp)) : maxHp;
        }
        if (tag.contains(TAG_LOCKED)) {
            // クライアントへの同期の中身だけに入っている(保存には書かない)。
            lockedOnClient = tag.getBoolean(TAG_LOCKED);
        }
        if (tag.contains(TAG_ITEMS)) {
            // 同期の中身には入れない(掘れない印だけ)ので、保存から読むときだけ。
            storage.load(tag, registries);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (maxHp > 0.0F) {
            tag.putFloat(TAG_HP, hp);
            tag.putFloat(TAG_MAX_HP, maxHp);
        }
        storage.save(tag, registries);
    }
}
