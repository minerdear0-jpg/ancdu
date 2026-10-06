package dev.ancdu

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.TextView

/** Палитра макета. */
object C {
    const val BG = 0xFF101214.toInt()
    const val SURFACE = 0xFF1A1D21.toInt()
    const val LINE = 0xFF23272C.toInt()
    const val TEXT = 0xFFE8E6E1.toInt()
    const val MUTED = 0xFF9AA0A6.toInt()
    const val ACCENT = 0xFFF2A93B.toInt()
    const val FILE = 0xFF5B9BD5.toInt()
    const val CACHE = 0xFF8A6A3A.toInt()
    const val WARN = 0xFFFFB74D.toInt()
    const val DANGER = 0xFFC9372C.toInt()
    const val FREE_TXT = 0xFFFF8A80.toInt()
    const val OK_TXT = 0xFF8FD18F.toInt()
    const val OK_BG = 0xFF1F2A1F.toInt()
    const val AUDIO = 0xFF8FD18F.toInt()
    const val APPS = 0xFFB48EAD.toInt()
    const val OTHER = 0xFF9AA0A6.toInt()
    const val SYS = 0xFF5A5F66.toInt()
    const val CHIP = 0xFF2A2E33.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = radius }

fun Context.label(s: CharSequence, sp: Float = 15f, color: Int = C.TEXT, mono: Boolean = false,
                  bold: Boolean = false): TextView = TextView(this).apply {
    text = s
    textSize = sp
    setTextColor(color)
    typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF,
        if (bold) Typeface.BOLD else Typeface.NORMAL)
}

private fun Context.spacer(w: Int, h: Int) = GradientDrawable().apply { setSize(w, h) }

fun Context.vbox(gapDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (gapDp > 0) { dividerDrawable = spacer(0, dp(gapDp)); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE }
}

fun Context.hbox(gapDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (gapDp > 0) { dividerDrawable = spacer(dp(gapDp), 0); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE }
}

const val ON_ACCENT = 0xFF15120C.toInt()

/**
 * Включена ли доступность. Любая отправка AccessibilityEvent / announceForAccessibility при
 * выключенной обязана быть за этой проверкой: на Android 17 AccessibilityManager бросает
 * IllegalStateException("Accessibility off"). Не чистая функция (нужен системный сервис),
 * поэтому без JVM-теста.
 */
fun View.a11yOn(): Boolean =
    (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isEnabled == true

fun Context.chip(text: String, selected: Boolean, onClick: () -> Unit): TextView =
    label(text, 13f, if (selected) ON_ACCENT else C.TEXT, mono = true, bold = selected).apply {
        gravity = Gravity.CENTER
        minHeight = dp(44); minWidth = dp(44)
        setPadding(dp(12), 0, dp(12), 0)
        background = rounded(if (selected) C.ACCENT else C.CHIP, dp(10).toFloat())
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

fun Context.action(title: String, sub: String?, primary: Boolean, onClick: () -> Unit): LinearLayout =
    vbox().apply {
        minimumHeight = dp(64)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = rounded(if (primary) C.ACCENT else C.SURFACE, dp(14).toFloat())
        addView(label(title, 16f, if (primary) ON_ACCENT else C.TEXT, bold = true))
        if (sub != null) addView(label(sub, 12f, if (primary) 0xFF3A2E14.toInt() else C.MUTED, mono = true))
        isClickable = true; isFocusable = true
        contentDescription = if (sub != null) "$title, $sub" else title
        setOnClickListener { onClick() }
    }

fun Context.backButton(onClick: () -> Unit): TextView = label("‹", 28f).apply {
    gravity = Gravity.CENTER
    minWidth = dp(44); minHeight = dp(44)
    contentDescription = "Назад"
    isClickable = true; isFocusable = true
    setOnClickListener { onClick() }
}

fun Activity.darkBars() {
    window.statusBarColor = C.BG
    window.navigationBarColor = C.BG
}

/** [onDismiss] — после закрытия любым путём (кнопка, «назад», тап вне диалога). */
fun Activity.alert(title: String, msg: String, ok: String = "OK", cancel: String? = null,
                   onDismiss: (() -> Unit)? = null, onOk: () -> Unit = {}): AlertDialog =
    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(title).setMessage(msg)
        .setPositiveButton(ok) { _, _ -> onOk() }
        .apply { if (cancel != null) setNegativeButton(cancel, null) }
        .apply { if (onDismiss != null) setOnDismissListener { onDismiss() } }
        .show()
