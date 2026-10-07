# ancdu

Быстрый анализатор занятого места для Android в духе `ncdu`: нативное ядро на C, тонкий JNI-слой, интерфейс на Kotlin Views без AndroidX. Android 11+ (minSdk 30), arm64-v8a и x86_64.

*A fast ncdu-like disk usage analyzer for Android — C core, Kotlin Views, no AndroidX.*

## Возможности

- Сканирование общей памяти (`/storage/emulated/0`) и, при наличии root, `/data` целиком. Например, 69 тыс. файлов в `/data` сканируются за ~0,2 с.
- Дерево каталогов с сортировкой по размеру или имени, размером на диске или видимым размером, и фоновым обновлением без потери текущего пути.
- Удаление с прогрессом и кнопкой «Стоп». Пакетное удаление через MediaStore вместо медленного пофайлового через FUSE.
- Ответственное удаление:
  - для крупных папок (≥ 1 ГиБ) и данных других приложений — пауза 1,5 с с отсчётом 2-1;
  - для любого удаления через root — пауза 2,5 с с отсчётом 3-2-1.
- Звуки и вибрация в стиле пульта, синтезируемые на устройстве. Настройка «Звук и вибрация»: как в системе / вкл / выкл.
- Интерфейс на английском и русском, переключение языка в приложении.

## Установка

Скачайте `ancdu-<версия>.apk` со страницы [Releases](../../releases) и сверьте его SHA-256 с файлом `.sha256` рядом. Сертификат подписи релизов:

```
SHA-256: 44:C4:FC:74:F8:9E:FC:FB:99:52:D6:43:48:E1:6C:18:50:89:6B:E7:33:26:A8:6D:E0:95:77:C3:28:E0:97:C9
```

Сборки до 1.0.0 были подписаны другим ключом. Чтобы поставить 1.0.0 поверх них, старую версию нужно сначала удалить.

## Сборка

Нужны:

- JDK 17+;
- Android SDK с cmdline-tools;
- `cmake` ≥ 3.22 и `ninja`.

Gradle скачивать не нужно: его подтягивает wrapper (9.8.0, с проверкой контрольной суммы). Недостающие NDK 27.0.12077973, `platforms;android-34` и `build-tools;34.0.0` скрипт ставит сам через `sdkmanager`.

```bash
./build.sh                  # release APK → dist/ancdu-1.0.0.apk + .sha256
./build.sh --debug          # debug APK
./build.sh --test           # сначала JVM-тесты
./build.sh --install -s SERIAL   # собрать и поставить через adb
./build.sh --clean          # чистая сборка
```

Путь к SDK берётся из `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `local.properties` или по умолчанию `~/Android/Sdk`.

### Подпись release

Ключ хранится вне репозитория. Есть два способа его указать:

- файл `~/.android/ancdu-release.properties` (путь можно переопределить через `ANCDU_SIGNING`):

  ```properties
  storeFile=/путь/к/ancdu-release.jks
  storePassword=…
  keyAlias=ancdu
  keyPassword=…
  ```

- или переменные окружения `ANCDU_KEYSTORE`, `ANCDU_KEYSTORE_PASSWORD`, `ANCDU_KEY_ALIAS`, `ANCDU_KEY_PASSWORD`. Это удобно для CI.

Без ключа release подписывается debug-ключом. Такой APK годится только для своей проверки.

### Тесты

```bash
./gradlew testDebugUnitTest                 # JVM
./gradlew connectedDebugAndroidTest         # на подключённом устройстве
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ancdu.JankTest \
  -Pandroid.testInstrumentationRunnerArguments.jank=true   # бюджет кадров
```

Нативное ядро тестируется отдельно, в `core-tests/`.

## Шрифты

JetBrains Mono и Exo 2 распространяются по лицензии SIL Open Font License 1.1. Тексты лицензий лежат в [`licenses/`](licenses/).
