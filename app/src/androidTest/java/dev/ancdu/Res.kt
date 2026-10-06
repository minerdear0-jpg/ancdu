package dev.ancdu

import android.content.Context

/** Часть шаблона ресурса до первого аргумента («освобождено » / «freed »). */
fun Context.prefixOf(id: Int): String = resources.getString(id).substringBefore('%')

/** Часть шаблона после последнего аргумента («· остаток в списке»). */
fun Context.suffixOf(id: Int): String = resources.getString(id).substringAfterLast("\$s")
