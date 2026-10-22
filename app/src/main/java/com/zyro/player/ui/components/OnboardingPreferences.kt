package com.zyro.player.ui.components

import android.content.Context
import android.content.SharedPreferences

/**
 * Флаг первого запуска / прохождения онбординга.
 *
 * SharedPreferences `zyro_onboarding` → `completed: Boolean`.
 * Пока `completed == false`, MainActivity показывает [com.zyro.player.ui.screens.OnboardingScreen]
 * поверх основного контента.
 */
class OnboardingPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Пройден ли онбординг хотя бы раз. */
    var isCompleted: Boolean
        get() = prefs.getBoolean(KEY_COMPLETED, false)
        set(v) = prefs.edit().putBoolean(KEY_COMPLETED, v).apply()

    /** Отмечает онбординг как пройденный. */
    fun markCompleted() {
        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    /** Сбрасывает флаг — онбординг покажется снова. */
    fun reset() {
        prefs.edit().putBoolean(KEY_COMPLETED, false).apply()
    }

    companion object {
        private const val PREFS = "zyro_onboarding"
        private const val KEY_COMPLETED = "completed"
    }
}
