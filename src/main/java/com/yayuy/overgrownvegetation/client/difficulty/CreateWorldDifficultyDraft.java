package com.yayuy.overgrownvegetation.client.difficulty;

import com.yayuy.overgrownvegetation.difficulty.DifficultySetting;

import java.util.Map;

/**
 * バニラのワールド作成画面({@code CreateWorldScreen})に {@code CreateWorldScreenMixin} が足す口。
 * 葉っぱのボタンで開いた難易度の画面で決めた「このワールドの値」を、「ワールド作成」を押すまで持っておく
 * (画面を行き来しても消えない。キャンセルで作成画面ごと閉じれば捨てられる)。
 */
public interface CreateWorldDifficultyDraft {

    /** 書き換えてよい表(入っていない項目は既定値)。 */
    Map<DifficultySetting, Double> overgrownvegetation$difficultyDraft();
}
