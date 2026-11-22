package com.yayuy.overgrownvegetation.entity.g1.skill;

import software.bernie.geckolib.animation.RawAnimation;

import javax.annotation.Nullable;

/**
 * G1 の 1 段目の種の技の段(2026-11-12、能力の第 1 回)。1 段 = アニメ 1 本 = 同期データ {@code ACTION} の 1 つの値。
 * 値は {@link #BASE} + 順番(段階 0 の攻撃の 3 段 1〜3・クリーパーの puff 4・evolve_in 10 と重ならない)。
 *
 * <p><b>後の回の足し方</b>: ここへ行を足すだけ(アニメの名前・繰り返すか・入るときの補間)。並びを入れ替えると番号がずれるので、末尾へ足す。
 * 段の進め方は {@link G1TechniqueGoal}(前触れ → 実行 → 隙)、繰り返しの段(扉を叩く・巣を作る・取り付き)は各 Goal が持つ。
 *
 * <ul>
 *   <li>{@code loop}: アニメが繰り返し({@code thenLoop})か、最後の姿勢で止まる({@code thenPlayAndHold})か。どの段も、終わりの時刻は
 *   サーバーが決める(アニメの長さと同じ tick)ので、1 回で終わるアニメも最後の姿勢で止めて待つ。</li>
 *   <li>{@code blendIn}: その段に入るときの補間(tick)。技の頭(前触れ・単独の段)は 2〜4、段と段の間は 0
 *   (3〜4 tick の実行が補間に食われないように。段階 0 の 3 段の 1 撃と同じ考え)。</li>
 * </ul>
 */
public enum G1Move {

    // --- ゾンビ(仕様: Vault「G1 ゾンビ(1 段目)」6 節。モデル developed_parasitized_zombie_r12)
    /** (b-1) 扉へ根腕を振り上げる(20 tick)。 */
    DOOR_BASH_WINDUP("door_bash_windup", false, 3),
    /** (b-1) 叩く(20 tick)。前触れと合わせて 1 回 40 tick。 */
    DOOR_BASH("door_bash", false, 0),
    /** (c) 肩の蔦が持ち上がってうねる(16 tick)。 */
    VINE_WINDUP("vine_windup", false, 3),
    /** (c) 蔦が伸びる(4 tick)。 */
    VINE_LASH("vine_lash", false, 0),
    /** (c) 蔦を引く(10 tick)。 */
    VINE_RECOVER("vine_recover", false, 0),

    // --- チビゾンビ(「G1 チビゾンビ(1 段目)」6 節。developed_parasitized_baby_zombie_r5)
    /** (a) の取り付き中(繰り返し)。 */
    LATCH("latch", true, 3),
    /** (b) 低い隙間を潜る(繰り返し、潜っている間)。 */
    SQUEEZE_UNDER("squeeze_under", true, 4),
    /** (b) 柵に前脚を掛けて乗り越える(24 tick)。 */
    CLIMB_OVER("climb_over", false, 3),
    /** (c) 頭の袋が脈打って膨らむ(16 tick)。 */
    SPORE_PUFF_WINDUP("spore_puff_windup", false, 2),
    /** (c) 袋が開いて胞子を噴く(10 tick)。 */
    SPORE_PUFF("spore_puff", false, 0),

    // --- 村人(「G1 村人(1 段目)」6 節。developed_parasitized_villager_r8)
    // (b) 夜に眠って嚢を開く(11-13 に「巣」から作り直し)。アニメは 11-13 にアニメのセッションが足した sleep_*(ユーザー了承済み。
    // それまでは前の版の nest_* を代わりに流していた。nest_* はもう使っていない)。
    /** (b) 座り込み、嚢がゆっくり開く(40 tick)。 */
    SLEEP_WINDUP("sleep_windup", false, 3),
    /** (b) 眠って嚢を開いている(4 秒の繰り返し)。 */
    SLEEP("sleep", true, 0),
    /** (b) 目を覚まして起き上がる(16 tick)。眠りに入る途中で起こされたときも流すので、少し補間する。 */
    SLEEP_RECOVER("sleep_recover", false, 3),
    /** (c) 背の瘤が膨らみシダと蔦が逆立つ(20 tick)。 */
    SPORE_WINDUP("spore_windup", false, 2),
    /** (c) 一気に縮んで霧(4 tick)。 */
    SPORE("spore", false, 0),
    /** (c) 隙(12 tick)。 */
    SPORE_RECOVER("spore_recover", false, 0),

    // --- 豚(「G1 豚(1 段目)」6 節。developed_parasitized_pig_r5)
    /** (b) 伏せて背の根が持ち上がり、小さな G1 を乗せる(30 tick)。 */
    CROUCH_LOAD("crouch_load", false, 3),
    /** (b) 急に止まって背を跳ね上げる構え(10 tick)。 */
    TOSS_WINDUP("toss_windup", false, 2),
    /** (b) 乗り手を前へ放り出す(10 tick)。 */
    TOSS("toss", false, 0),
    /** (c) 首を下げて喉袋が 2 回脈打つ・えずく(15 tick)。 */
    PUFF_WINDUP("puff_windup", false, 2),
    /** (c) 前へ胞子を噴く(8 tick)。 */
    PUFF("puff", false, 0),
    /** (c) 隙(12 tick)。 */
    PUFF_RECOVER("puff_recover", false, 0),

    // --- 牛(「G1 牛(1 段目)」6 節。能力の第 2 回 2026-11-13)
    /** (b) 乳嚢が 3 回脈打って膨らみ、腹を下げて後ろ脚を開く(20 tick)。 */
    MILK_WINDUP("milk_windup", false, 3),
    /** (b) 腹の下から後ろ・横へ胞子の乳を噴く(10 tick)。 */
    MILK("milk", false, 0),
    /** (b) 隙(10 tick)。 */
    MILK_RECOVER("milk_recover", false, 0),

    // --- 羊(「G1 羊(1 段目)」6 節)
    /** (b) 頭を下げて草を食べる(40 tick)。 */
    GRAZE("graze", false, 4),
    /** (c) 綿毛の房が白く膨らみ、体を震わせて房を立てる(30 tick)。 */
    LOOSE_WINDUP("loose_windup", false, 3),
    /** (c) 房が弾けて綿毛を空へ放つ(10 tick)。 */
    LOOSE("loose", false, 0),

    // --- 鶏(「G1 鶏(1 段目)」6 節)
    /** (b) うずくまり、尻が震えてそ嚢が脈打つ(40 tick)。 */
    LAY_WINDUP("lay_windup", false, 4),
    /** (b) 卵を産む(10 tick)。 */
    LAY("lay", false, 0),
    /** (b) 立ち上がる(20 tick)。 */
    LAY_RECOVER("lay_recover", false, 0),
    /** (c) 立ち止まって首を下げ、そ嚢が 3 回脈打つ(20 tick)。 */
    SPEW_WINDUP("spew_windup", false, 2),
    /** (c) 前へ胞子の雲を吐き続ける(30 tick)。 */
    SPEW("spew", false, 0),
    /** (c) 隙(14 tick)。 */
    SPEW_RECOVER("spew_recover", false, 0),

    // --- クリーパー(「G1 クリーパー(1 段目)」6 節。能力の第 3 回 2026-11-13)
    /** (b) 導火: 低く構え、背の莢が脈打って膨らむ(40 tick)。 */
    FUSE("fuse", false, 3),
    /** (b) 莢が一斉に割れて開き、しぼむ(10 tick)。 */
    BURST("burst", false, 0),
    /** (c) 体を揺すって莢を 1 つ落とす(12 tick)。 */
    DROP_POD("drop_pod", false, 2),

    // --- スケルトン(「G1 スケルトン(1 段目)」6 節)
    /** (b) 左手を胸の肋骨の間へ突っ込んで塊をちぎり取り、弦を引く(20 tick)。 */
    SHOOT_WINDUP("shoot_windup", false, 3),
    /** (b) 放つ(2 tick)。 */
    SHOOT("shoot", false, 0),
    /** (b) 隙(16 tick)。 */
    SHOOT_RECOVER("shoot_recover", false, 0),
    /** (c) 足の根を地面へ突き刺す(20 tick)。 */
    ROOT_ANCHOR("root_anchor", false, 3),
    /** (c) 根を張って据えている(4 秒の繰り返し)。 */
    ANCHORED_IDLE("anchored_idle", true, 0),
    /** (c) 根を抜く(20 tick)。 */
    ROOT_RELEASE("root_release", false, 0),

    // --- クモ(2026-11-13 能力の第 4 回。「G1 クモ(1 段目)」6 節)
    /** (b1) 壁に張り付いて登る(繰り返し。胴を壁に平らに倒す)。追うのは止めない({@code DevelopedParasitized#busy})。 */
    CLIMB("climb", true, 4),
    /** (b1) 高い所で壁に張り付いて待つ(繰り返し)。 */
    HANG_IDLE("hang_idle", true, 4),
    /** (b1) 落ちる前に脚を縮めて揺れる(10 tick)。 */
    DROP_WINDUP("drop_windup", false, 2),
    /** (b1) 落ちている間(繰り返し)。張り付いた姿から落ちる姿へ 102° 変わるので、入りの補間は 3 tick(11-13 アニメのセッションの依頼・ユーザー了承)。 */
    DROP("drop", true, 3),
    /** (b2) 低く沈んで杭の脚を地面に刺す(10 tick)。 */
    LEAP_WINDUP("leap_windup", false, 2),
    /** (b2) 跳んでいる間(10 tick)。 */
    LEAP("leap", false, 0),
    /** (b2) 着地してつんのめる(16 tick)。(b1) の着地にも使う。 */
    LEAP_RECOVER("leap_recover", false, 0),
    /** (c) 腹を持ち上げて相手へ向け、袋が膨らむ(15 tick)。 */
    SPIT_WINDUP("spit_windup", false, 3),
    /** (c) 根糸を吐く(3 tick)。 */
    SPIT("spit", false, 0),
    /** (c) 隙(12 tick)。 */
    SPIT_RECOVER("spit_recover", false, 0),

    // --- エンダーマン(第 4 回。「G1 エンダーマン(1 段目)」6 節)
    /** (b) 転移の溜め(15 tick。輪郭がちらつき、後頭部の袋が縮む)。 */
    TELEPORT_WINDUP("teleport_windup", false, 2),
    /** (b) 着いた所で崩れて立て直す(5 tick)。 */
    TELEPORT_IN("teleport_in", false, 0),
    /** (b) 目が合って首ごと頭がこちらを向く(10 tick。そのまま溜めへ)。 */
    GAZE("gaze", false, 2),
    /** (c-1) 左腕の根の束がほどけて前へ垂れる(14 tick)。 */
    GRAB_WINDUP("grab_windup", false, 2),
    /** (c-1) 根が伸びて絡む(3 tick)。 */
    GRAB("grab", false, 0),
    /** (c-1) 抱えている(20 tick。終わりに一緒に転移)。 */
    GRAB_HOLD("grab_hold", false, 0),
    /** (c-2) 根の腕を頭より高く振り上げる(14 tick)。 */
    SLAM_WINDUP("slam_windup", false, 2),
    /** (c-2) 地面に叩きつける(3 tick)。 */
    SLAM("slam", false, 0),
    /** (c-2) 根を引き抜いて立て直す(14 tick)。 */
    SLAM_RECOVER("slam_recover", false, 0),

    // --- 豚の (a) 食らいつき(2026-11-13 ユーザー「入れようか」。「G1 豚(1 段目)」3 節 (a)。番号をずらさないよう末尾に足した)
    /** (a) 噛みついたまま離さない(1 秒の繰り返し。最大 40 tick)。3 段の 1 撃の実行 attack(口を閉じた姿)から続く。 */
    BITE_HOLD("attack_hold", true, 2),
    /** (a) 口を離す・振りほどかれてよろける(14 tick)。 */
    BITE_RECOVER("attack_recover", false, 2);

    /** 同期データ ACTION の値の始まり。 */
    public static final int BASE = 20;

    private static final G1Move[] VALUES = values();

    public final String animation;
    public final boolean loop;
    public final int blendIn;
    private final RawAnimation raw;

    G1Move(String animation, boolean loop, int blendIn) {
        this.animation = animation;
        this.loop = loop;
        this.blendIn = blendIn;
        this.raw = loop ? RawAnimation.begin().thenLoop(animation) : RawAnimation.begin().thenPlayAndHold(animation);
    }

    /** 同期データ ACTION に入れる値。 */
    public int id() {
        return BASE + ordinal();
    }

    public RawAnimation raw() {
        return raw;
    }

    /** ACTION の値から。技の段でなければ null。 */
    @Nullable
    public static G1Move byId(int action) {
        int i = action - BASE;
        return i >= 0 && i < VALUES.length ? VALUES[i] : null;
    }
}
