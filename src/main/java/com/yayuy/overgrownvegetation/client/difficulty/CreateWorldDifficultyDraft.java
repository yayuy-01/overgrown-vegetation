package com.yayuy.overgrownvegetation.client.difficulty;

import com.yayuy.overgrownvegetation.difficulty.DifficultyValues;

/**
 * バニラのワールド作成画面({@code CreateWorldScreen})に {@code CreateWorldScreenMixin} が足す口。
 * 葉っぱのボタンで開いた設定のページで決めた「このワールドの値」を、「ワールド作成」を押すまで持っておく
 * (画面を行き来しても消えない。キャンセルで作成画面ごと閉じれば捨てられる)。
 */
public interface CreateWorldDifficultyDraft {

    /** 書き換えてよい下書き(入っていない項目は既定値)。 */
    DifficultyValues overgrownvegetation$difficultyDraft();
}
