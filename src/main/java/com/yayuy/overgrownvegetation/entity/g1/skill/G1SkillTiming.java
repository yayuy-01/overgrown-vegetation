package com.yayuy.overgrownvegetation.entity.g1.skill;

/**
 * G1 の 1 段目の技の溜め(前触れ)と狙いの、手触りで直す値をまとめた所(2026-11-19、溜めを外す 2 便)。
 *
 * <p>ユーザー決定(11-19「案どおりでよい」。Vault「Mob の攻撃の溜めを外す(11-19)」):
 * <ul>
 *   <li>残す(前触れそのまま): 爆ぜ・根の叩きつけ・掴み・蔦の鞭・胞子の霧・乳の噴き・天井からの落下・扉叩き。蔦の鞭だけ 20 → 10。</li>
 *   <li>短く残す: 跳びかかり 11 → 5・唾 16 → 6。</li>
 *   <li>外す: 矢は引く間も歩く。喉の胞子・吐き戻し・背の投げは前触れ 5。取り付きの胞子は溜め無し。</li>
 *   <li>扇・まっすぐ飛ぶ物で当てる技(蔦の鞭・掴み・跳びかかり・唾・矢・喉の胞子・吐き戻し)は、当たる tick まで標的を向き続ける。
 *   ただし 1 tick に回れる角度に上限 {@link #AIM_TURN_DEG}(素早く横へ回り込めばよけられる)。盾で受けられる技は今どおり盾で止まる。</li>
 * </ul>
 * ここの数はどれも【仮】。前触れの数は「技を始めてから当たる(放つ・跳ぶ)までの tick」で、各技の最初の段の長さはそこから決まる。
 * 前触れのアニメ({@code *_windup})は元の長さで作ってあるので、縮めた技は途中で次の段へ移る。
 */
public final class G1SkillTiming {

    /** 当たる tick まで向き続ける技の、1 tick に回れる角度(度)【仮】。小さいほど回り込んでよけやすい。 */
    public static final float AIM_TURN_DEG = 18.0F;

    /** 蔦の鞭: 振り下ろす段(4 tick)の前の段の長さ。前触れ = これ + 4 = 10【仮】(元 16 + 4 = 20)。 */
    public static final int VINE_WINDUP = 6;
    /** 跳びかかり: 構えの段の長さ。跳ぶのは次の tick = 5【仮】(元 10 → 11)。 */
    public static final int LEAP_WINDUP = 4;
    /** 唾: 構えの段の長さ。吐くのは次の tick = 6【仮】(元 15 → 16)。 */
    public static final int SPIT_WINDUP = 5;
    /** 喉の胞子: 構えの段の長さ。吹くのは次の tick = 5【仮】(元 15 → 16)。 */
    public static final int THROAT_PUFF_WINDUP = 4;
    /** 吐き戻し: 構えの段の長さ。1 回目を吐くのは次の tick = 5【仮】(元 20 → 21)。 */
    public static final int SPEW_WINDUP = 4;
    /** 背の投げ: 構えの段の長さ。投げるのは投げの段の 2 tick 目 = 5【仮】(元 10 → 12)。 */
    public static final int TOSS_WINDUP = 3;
    /** 取り付きの胞子: 構えの段の長さ。霧を出すのは次の tick = 2(溜め無し。段は 1 tick だけ残す)(元 16 → 17)。 */
    public static final int SPORE_PUFF_WINDUP = 1;

    private G1SkillTiming() {
    }
}
