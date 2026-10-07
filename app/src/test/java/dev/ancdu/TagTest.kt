package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Метки безопасности строк: sys > app > cache > dl > media. */
class TagTest {
    private val S = "/storage/emulated/0"
    private val SELF = "dev.ancdu"

    private fun of(path: String, flags: Int = 0, block: Block? = null, root: String = S, owner: String? = Owner.packageOf(path)) =
        Tag.of(path, flags, owner, block, root, SELF)?.kind

    @Test fun sys() {
        assertEquals(TagKind.SYS, of("$S/Android", F_DIR, Block.ANDROID_DIR))
        assertEquals(TagKind.SYS, of("/data/system", F_DIR, Block.SYSTEM, root = "/"))
        assertEquals(TagKind.SYS, of("$S/mnt", F_DIR, Block.OTHER_FS))
        // «дерево устарело» и «нет быстрого пути» — не про системность
        assertNull(of("$S/Documents", F_DIR, Block.REFRESH_FAILED))
        assertNull(of("$S/Documents", F_DIR, Block.NO_FAST))
        assertEquals(TagKind.DL, of("$S/Download", F_DIR, Block.REFRESH_FAILED))
    }

    @Test fun app() {
        val t = Tag.of("$S/Android/data/com.whatsapp/files", F_DIR, "com.whatsapp", null, S, SELF)!!
        assertEquals(TagKind.APP, t.kind)
        assertEquals("com.whatsapp", t.pkg)
        assertEquals("app:WhatsApp", t.text("WhatsApp"))
        assertEquals("app", t.text(null))
        assertEquals("app", t.text(""))
        // ярлык из чужих данных — одной строкой, bidi видимыми
        assertEquals("app:A⟨U+202E⟩B", t.text("A‮\nB"))
        // свои данные — не «чужие»
        assertNull(of("/data/user/0/dev.ancdu/files/x", root = "/"))
        assertEquals(TagKind.APP, of("/data/data/com.x.y/files", F_DIR, root = "/"))
    }

    @Test fun cache() {
        // путь в Android/data без имени пакета (Owner его не признаёт): решает правило cache
        assertEquals(TagKind.CACHE, of("$S/Android/data/x/cache", F_DIR))
        assertEquals(TagKind.CACHE, of("$S/Android/data/x/cache/a/img.jpg"))   // cache > media
        assertEquals(TagKind.CACHE, of("$S/Android/data/x/.cache", F_DIR))
        assertEquals(TagKind.CACHE, of("$S/Android/data/x/Cache", F_DIR))
        assertEquals(TagKind.CACHE, of("/data/data/x/cache/f", root = "/"))
        assertEquals(TagKind.CACHE, of("/data/user/10/x/cache", F_DIR, root = "/"))
        assertEquals(TagKind.CACHE, of("/data/user_de/0/x/cache/f", root = "/"))
        assertEquals(TagKind.CACHE, of("/data/media/0/Android/data/x/cache/f", root = "/"))
        // свой кэш — тоже кэш (не «чужие данные»)
        assertEquals(TagKind.CACHE, of("/data/user/0/dev.ancdu/cache/f", root = "/"))
        // осторожно: файл «cache», не прямо под каталогом приложения, похожие имена
        assertNull(of("$S/Android/data/x/cache"))
        assertNull(of("$S/Android/data/x/files/cache", F_DIR))
        assertNull(of("$S/Android/data/x/code_cache", F_DIR))
        assertNull(of("$S/Android/data/x/caches", F_DIR))
        assertNull(of("$S/Android/data/x", F_DIR))
        assertNull(of("$S/Documents/cache", F_DIR))
        assertEquals(TagKind.MEDIA, of("$S/DCIM/cache/a.jpg"))                  // cache глубоко в медиа
        assertEquals(TagKind.MEDIA, of("$S/DCIM/.cache", F_DIR))
    }

    @Test fun cacheAboveTheTreeRootIsNotCache() {
        // дерево построено внутри своего cacheDir: метка «кэш» на каждой строке была бы шумом
        val r = "/data/user/0/dev.ancdu/cache/t1"
        assertNull(of("$r/x.bin", root = r))
        assertNull(of("$r/sub", F_DIR, root = r))
        assertEquals(TagKind.DL, of("$r/Download", F_DIR, root = r))
        assertEquals(TagKind.CACHE, of("$r/Android/data/x/cache", F_DIR, root = r))
        assertEquals(TagKind.CACHE, of("$r/Android/data/x/cache/f", root = r))
    }

    @Test fun precedence() {
        assertEquals(TagKind.SYS, of("$S/Android/data/com.x/cache", F_DIR, Block.SYSTEM, owner = "com.x"))
        assertEquals(TagKind.APP, of("$S/Android/data/com.x/cache", F_DIR))     // app > cache
        assertEquals(TagKind.DL, of("$S/Download/movie.mp4"))                  // dl > media
        assertEquals(TagKind.DL, of("$S/Download/DCIM/a.jpg"))
    }

    @Test fun downloads() {
        assertEquals(TagKind.DL, of("$S/Download", F_DIR))
        assertEquals(TagKind.DL, of("$S/Download/a.pdf"))
        assertEquals(TagKind.DL, of("/storage/emulated/10/Download/a.pdf", root = "/storage/emulated/10"))
        assertEquals(TagKind.DL, of("$S/Download/a.pdf", root = "/"))          // root-скан «/»
        assertEquals(TagKind.DL, of("/data/media/0/Download/a.pdf", root = "/"))
        assertNull(of("$S/Download2/a.pdf"))
        assertNull(of("$S/Download2", F_DIR))
        assertNull(of("$S/Downloads/a.pdf"))
        assertNull(of("$S/download/a.pdf"))
        assertNull(of("$S/Documents/Download/a.pdf"))                          // не прямо под корнем
        assertNull(of("/Download/a.pdf", root = "/"))
    }

    @Test fun media() {
        for (d in listOf("DCIM", "Pictures", "Movies", "Music", "Recordings")) {
            assertEquals(d, TagKind.MEDIA, of("$S/$d", F_DIR))
            assertEquals(d, TagKind.MEDIA, of("$S/$d/x/notes.txt"))
        }
        assertNull(of("$S/DCIMx/a.txt"))
        assertNull(of("$S/Documents/Pictures/a.txt"))
        // по расширению — у файлов, без учёта регистра
        assertEquals(TagKind.MEDIA, of("$S/Documents/a.JPG"))
        assertEquals(TagKind.MEDIA, of("$S/a.mp4"))
        assertEquals(TagKind.MEDIA, of("$S/Documents/voice.opus"))
        assertNull(of("$S/Documents/x.mp4", F_DIR))                            // каталог с «расширением»
        assertNull(of("$S/Documents/a.pdf"))
        assertNull(of("$S/Documents/mp4"))
        assertNull(of("$S/Documents/.jpg"))
    }

    @Test fun none() {
        assertNull(of("$S/Documents", F_DIR))
        assertNull(of("$S/Documents/report.pdf"))
        assertNull(of("", F_DIR))
    }

    @Test fun colorsAndText() {
        assertEquals(C.MUTED, TagKind.SYS.color)
        assertEquals(C.MUTED, TagKind.APP.color)
        assertEquals(C.BLUE_HI, TagKind.CACHE.color)
        assertEquals(C.BLUE_HI, TagKind.DL.color)
        assertEquals(C.MUTED, TagKind.MEDIA.color)
        assertEquals(listOf("sys", "app", "cache", "dl", "media"), TagKind.entries.map { Tag(it).text(null) })
    }

    @Test fun descriptions() {
        assertEquals(", system", Tag(TagKind.SYS).desc(EN, null))
        assertEquals(", системное", Tag(TagKind.SYS).desc(RU, null))
        assertEquals(", WhatsApp app data", Tag(TagKind.APP, "com.whatsapp").desc(EN, "WhatsApp"))
        assertEquals(", данные приложения WhatsApp", Tag(TagKind.APP, "com.whatsapp").desc(RU, "WhatsApp"))
        assertEquals(", app data", Tag(TagKind.APP, "com.x").desc(EN, null))
        assertEquals(", данные приложения", Tag(TagKind.APP, "com.x").desc(RU, null))
        assertEquals(", cache", Tag(TagKind.CACHE).desc(EN, null))
        assertEquals(", кэш", Tag(TagKind.CACHE).desc(RU, null))
        assertEquals(", downloads", Tag(TagKind.DL).desc(EN, null))
        assertEquals(", загрузки", Tag(TagKind.DL).desc(RU, null))
        assertEquals(", media", Tag(TagKind.MEDIA).desc(EN, null))
        assertEquals(", медиа", Tag(TagKind.MEDIA).desc(RU, null))
    }

    /** Метка рядом с именем: имя теряет не больше 3 знаков, иначе метка короче или её нет. */
    @Test fun fitBesideName() {
        val m: (String) -> Float = { it.codePointCount(0, it.length).toFloat() }
        fun fit(name: String, tag: String, avail: Float) = Tag.fit(name, tag, avail, 1f, m, m)
        assertEquals("cache", fit("report.pdf", "cache", 20f))   // всё влезает
        assertEquals("cache", fit("report.pdf", "cache", 16f))   // ровно
        assertEquals("cache", fit("report.pdf", "cache", 14f))   // имя «rep….pdf»: −3
        assertNull(fit("report.pdf", "cache", 13f))              // имя «report…»: −4 — без метки
        assertEquals("app", fit("report.pdf", "app:WhatsApp", 20f))   // полная съела бы 4 — короткая
        assertEquals("app:WhatsApp", fit("report.pdf", "app:WhatsApp", 23f))
        assertNull(fit("report.pdf", "app:WhatsApp", 10f))
        assertNull(fit("a", "cache", 3f))                        // метка не влезает вовсе
        assertEquals("dl", fit("", "dl", 3f))
        // суррогатные пары считаются знаками, не половинами
        assertEquals("dl", fit("🎉🎉🎉🎉🎉.txt", "dl", 11f))   // имя «🎉🎉🎉….txt»: −2
    }

    /** Лист группы: метки строк «крупнейших» — в том же порядке, что строки (по размеру). */
    @Test fun groupSheetTopTags() {
        val cache = Tag(TagKind.CACHE).resolve(EN, null)
        val dl = Tag(TagKind.DL).resolve(EN, null)
        fun item(n: String, disk: Long, tag: TagText?) = GroupItem(n, false, disk, disk, 1, 0, null, null, false, tag)
        val (p, _) = GroupSheet.preview(EN, listOf(item("a", 10, cache), item("b", 30, null), item("c", 20, dl)),
            "/storage/emulated/0/x", "dev.ancdu", false, Kind.SCAN, null, RootState.UNKNOWN, 0)
        assertEquals(listOf("b", "c", "a"), p.top.map { it.first })
        assertEquals(listOf(null, "dl", "cache"), p.topTags.map { it?.text })
        assertEquals(", downloads", p.topTags[1]!!.desc)
        assertNull(p.tag)
    }
}
