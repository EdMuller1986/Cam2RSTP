# CamRTSP — телефон как RTSP-камера для регистратора

Android-приложение: тыловая камера телефона → Wi-Fi → rtsp://IP:8554/live (H.264 / RTP).
Подключай VLC, видеорегистратор (Hikvision/iVMS, Dahua, Synology Surveillance Station)
или любой ONVIF-клиент, поддерживающий RTSP-over-TCP.

## Возможности
- H.264 1280x720, 25 fps, 2.5 Mbps
- 1+ одновременных зрителей (TCP-interleaved)
- Foreground service + wake lock — стримит при погасшем экране
- Таймкод в RTP PTS (90 kHz) — регистратор рисует на записи

## Сборка
Workflow .github/workflows/build.yml НЕ включён в этот коммит (PAT не имеет scope workflow).
Добавь файл вручную через GitHub UI или дай мне новый токен с workflow scope.

Пуш тега v* — APK в Releases. Пуш в main — APK в Artifacts.

## Использование
1. Установи CamRTSP-unsigned.apk (включи "Установка из неизвестных источников")
2. Подключи телефон к той же Wi-Fi, что и регистратор
3. Запусти приложение, дай разрешения, нажми START
4. На экране будет rtsp://IP:8554/live
5. В VLC: Media → Open Network Stream → вставь URL
6. В регистраторе: Custom RTSP, URL rtsp://IP:8554/live, транспорт TCP
