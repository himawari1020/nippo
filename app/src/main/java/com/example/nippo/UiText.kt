package com.example.nippo

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * ViewModelからUIへ文字列を伝えるためのラッパークラス。
 * リソースID(R.string.xxx)と生の文字列(String)の両方を統一して扱います。
 */
sealed class UiText {
    // APIのエラーメッセージなど、動的な文字列用
    data class DynamicString(val value: String) : UiText()

    // strings.xml のID用
    class StringResource(
        @StringRes val resId: Int,
        vararg val args: Any
    ) : UiText()

    // Composable関数内（画面描画時）で使用
    @Composable
    fun asString(): String {
        return when (this) {
            is DynamicString -> value
            is StringResource -> stringResource(resId, *args)
        }
    }

    // Contextが必要な場所（ToastやViewModel外のロジック）で使用
    fun asString(context: Context): String {
        return when (this) {
            is DynamicString -> value
            is StringResource -> context.getString(resId, *args)
        }
    }
}
