# CamRTSP — телефон как RTSP-камера

Android-приложение: тыловая камера телефона → Wi-Fi → `rtsp://IP:8554/live` (H.264 / RTP-over-TCP).
Подключай VLC, видеорегистраторы (Hikvision/iVMS, Dahua, Synology Surveillance Station) или
любой RTSP-клиент.

## Возможности

- H.264 энкодер, целевое разрешение 1280×720 @ 25 fps, 2.5 Mbps
- **Авто-ресайз**: если устройство игнорирует `setTargetResolution` (Xiaomi и т.п. отдают 1088×1088),
  энкодер пересоздаётся на реальный размер первого кадра
- Цветовая схема — NV12 (YUV420 semi-planar), самый совместимый формат для Android H.264
- Транспорт **RTP-over-TCP (interleaved)** — проходит через NAT/файрволы, работает с регистраторами
- Несколько одновременных зрителей (RTSP-сессии)
- Foreground service + persistent notification — стримит при погасшем экране
- Не убивается при свайпе из recents (`onTaskRemoved`)
- `packetization-mode=1` в SDP + правильный FU-A fragmentation для больших NALU

## Отладка прямо в приложении

Главный экран:
- IP и RTSP-URL
- Кнопки **START / STOP**
- Кнопка **Copy log to clipboard** + длинный тап на тексте лога
- Живой лог снизу (INFO — голубой, WARN — жёлтый, ERROR — красный) с таймстампами

**Все логи** параллельно пишутся в `filesDir/camrtsp.log` (внутренняя память) — переживают
смерть процесса. При перезапуске Activity подгружает последние 200 строк.

**Глобальный обработчик крэшей** (`Thread.setDefaultUncaughtExceptionHandler` в сервисе):
ловит необработанные исключения во **всех** потоках, пишет стек-трейс в файл и шлёт
broadcast `com.camrtsp.app.CRASH` — Activity показывает FATAL прямо в UI красным.
Скопируй лог через «Copy log to clipboard» — в нём будет видна причина, даже если приложение
упало.

## CI: авто-сборка подписанного APK

`.github/workflows/build.yml` запускается на каждый push в `main` и тег `v*`:

1. Checkout → JDK 17 → Android SDK 34
2. **Генерирует keystore** `app/keystore/release-debug.keystore` (RSA 2048, 10000 дней,
   пароль `android`, alias `camrtsp`)
3. `gradle assembleRelease` — собирает подписанный APK
4. `apksigner verify --verbose` — проверяет подпись
5. На теге `v*` — публикует в GitHub Release
6. На push в `main` — отдаёт APK артефактом `CamRTSP-signed`

Keystore добавляется в `.gitignore`, генерируется заново в каждом CI-запуске.
APK подписан **debug-grade** сертификатом (Android принимает, но Google Play — нет).
Этого хватает для установки через боковую загрузку и для регистраторов, которые
не проверяют источник сертификата.

## Сборка локально

```bash
./gradlew assembleRelease   # подписанный debug-grade APK
./gradlew assembleDebug     # unsigned debug APK
```

Минимальный Android SDK — 24 (Android 7.0), target — 34 (Android 14).

## Использование

1. Скачай `CamRTSP-signed.apk` со страницы Actions последнего успешного билда
   (или из Releases, если есть тег `v*`)
2. Установи на телефон: разреши «Установка из неизвестных источников»
3. Подключи телефон к той же Wi-Fi, что и регистратор/комп с VLC
4. Запусти приложение, дай разрешения (камера, микрофон, уведомления)
5. Нажми **START** — увидишь `rtsp://192.168.x.x:8554/live`
6. Подключайся:
   - **VLC**: Медиа → Открыть URL → вставить
   - **Регистратор**: Custom RTSP, транспорт **TCP**
   - **ffplay**: `ffplay -rtsp_transport tcp rtsp://IP:8554/live`

## Архитектура

```
MainActivity (UI + log view)
   └─ start/stop → RtspServerService (LifecycleService, foreground)
         ├─ RtspServer (ServerSocket :8554, per-client CS)
         ├─ MediaCodec H.264 encoder (1280×720, NV12, 2.5 Mbps)
         ├─ CameraX ImageAnalysis (YUV_420_888) → YUV-to-NV12 → encoder
         └─ Drain thread: encoder output → RtspServer.push (per-NAL RTP)
```

### Ключевые файлы

- `app/src/main/java/com/camrtsp/app/MainActivity.kt` — UI + log viewer
- `app/src/main/java/com/camrtsp/app/RtspServerService.kt` — оркестратор
  (camera bind, encoder lifecycle, drain loop, crash handler)
- `app/src/main/java/com/camrtsp/app/RtspServer.kt` — RTSP/RTP сервер на голых сокетах
- `app/src/main/res/layout/activity_main.xml` — UI layout
- `.github/workflows/build.yml` — CI

## Известные ограничения

- Аудио не транскодируется (в `ImageAnalysis` только видео). Микрофон запрашивается для
  будущей поддержки
- Сертификат debug-grade: Google Play не примет, но боковая установка и регистраторы работают
- Кодек зависит от устройства — некоторые старые чипсеты могут не иметь нужного H.264 encoder
- MTU: NALU фрагментируются по 1400 байт (FU-A) — для гигабитных сетей хватает,
  для очень медленного Wi-Fi может лагать

## Лицензия

MIT
