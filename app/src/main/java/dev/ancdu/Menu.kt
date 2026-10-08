package dev.ancdu

import android.app.Activity
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.security.MessageDigest

/** Чистый Kotlin: тексты «О приложении». */
object About {
    /** Исходный код (только текст, без сетевых запросов). */
    const val SOURCE = "https://github.com/minerdear0-jpg/ancdu"

    /** Байты как «0A:FF:…» (прописные, через двоеточие). */
    fun hex(b: ByteArray): String = b.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    /** SHA-256 сертификата [cert] (DER) в виде [hex]. */
    fun sha256(cert: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(cert))
}

/** Лист у нижнего края (как панель пути): скобки сверху, затемнение, не выше 85% экрана; «назад» — BACK. */
/** Лист у нижнего края в стиле панели пути (скобки, PANEL, до 85% экрана): меню, «О приложении», точка отсчёта. */
fun Activity.sheetDialog(title: String, body: View): Dialog =
    Dialog(this, R.style.Theme_Ancdu_Sheet).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(MaxHeightBox(this@sheetDialog, 0.85f).apply {
            background = Brackets(this@sheetDialog, C.PANEL, bottom = false)
            setPadding(dp(20), dp(20), dp(20), dp(16))
            addView(ScrollView(this@sheetDialog).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
        })
        bottomSheet()
        setTitle(title)
        // Назад или тап мимо листа — BACK; выбор пункта закрывает лист без него (TAP уже был).
        setOnCancelListener { Feedback.cue(window?.decorView, Cue.BACK) }
    }

/**
 * Меню «···» главного экрана: «Язык: …», «Звук и вибрация: …», «Тема: …», «О приложении» — строки 48dp.
 * Пункт закрывает меню и открывает свой выбор.
 */
class MenuSheet(private val act: Activity, onLang: () -> Unit, onFx: () -> Unit, onTheme: () -> Unit, onAbout: () -> Unit) {
    private val t: Txt = act.tx
    /** Для тестов: пункты по порядку (язык, звук, тема, о приложении). */
    val rows = ArrayList<TextView>()
    val dialog: Dialog = act.sheetDialog(t.s(R.string.menu), act.vbox().apply {
        addView(act.caps(t.s(R.string.menu)), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = act.dp(6) })
        item(t.s(R.string.menu_lang, t.s(Lang.choice(act).label)), onLang)
        item(t.s(R.string.fx_item, t.s(Feedback.mode.label)), onFx)
        item(t.s(R.string.theme_item, t.s(Theme.choice(act).label)), onTheme)
        item(t.s(R.string.about), onAbout)
    })

    private fun LinearLayout.item(text: String, onPick: () -> Unit) {
        hairline()
        val row = act.label(text, 15f, C.TEXT).apply {
            minHeight = act.dp(48)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(act.dp(8), act.dp(6), act.dp(8), act.dp(6))
            background = act.pressable(Color.TRANSPARENT)
            isClickable = true; isFocusable = true
            feedbackClick { dialog.dismiss(); onPick() }
        }
        rows += row
        addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun show() = dialog.show()
    fun dismiss() = dialog.dismiss()
}

/**
 * «О приложении»: версия (versionName), исходный код (адрес выделяется, сети нет), лицензии шрифтов
 * и SHA-256 сертификата подписи установленного APK.
 */
class AboutSheet(private val act: Activity) {
    private val t: Txt = act.tx
    lateinit var versionText: TextView
        private set
    lateinit var sourceText: TextView
        private set
    lateinit var certText: TextView
        private set
    val dialog: Dialog = act.sheetDialog(t.s(R.string.about), act.vbox(10).apply {
        addView(act.caps(t.s(R.string.about)))
        addView(act.label("ANCDU", 20f, mono = true, bold = true).apply { letterSpacing = 0.18f })
        versionText = act.label(t.s(R.string.about_version, version()), 14f, C.TEXT, mono = true)
        addView(versionText)
        addView(act.caps(t.s(R.string.about_source)))
        sourceText = act.label(About.SOURCE, 14f, C.BLUE_HI, mono = true).apply { setTextIsSelectable(true) }
        addView(sourceText)
        addView(act.label(t.s(R.string.about_licenses), 13f, C.MUTED))
        addView(act.caps(t.s(R.string.about_cert)))
        certText = act.label(cert() ?: t.s(R.string.about_cert_none), 12f, C.TEXT, mono = true).apply { setTextIsSelectable(true) }
        addView(certText)
        addView(act.action(t.s(R.string.close), null, primary = false, cue = Cue.BACK) { dismiss() },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    })

    private fun version(): String =
        runCatching { act.packageManager.getPackageInfo(act.packageName, 0).versionName }.getOrNull().orEmpty()

    /** SHA-256 первого сертификата подписи установленного пакета или null. */
    private fun cert(): String? = runCatching {
        val info = act.packageManager.getPackageInfo(act.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let(About::sha256)
    }.getOrNull()

    fun show() = dialog.show()
    fun dismiss() = dialog.dismiss()
}
