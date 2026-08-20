# TvRecommendationBridge

Aplicación para Android TV / Google TV que permite personalizar el comportamiento de las recomendaciones del launcher de Google TV.

Cuando el usuario selecciona una tarjeta de película o serie compatible, TvRecommendationBridge identifica el contenido y permite abrir su ficha en **Nuvio** o **Stremio**, según la aplicación configurada por el usuario.

TvRecommendationBridge es una herramienta independiente de automatización y redirección. **No aloja, almacena, distribuye ni proporciona películas, series, streams, torrents ni ningún otro contenido audiovisual.**

## Requisitos

- Un dispositivo con el launcher **Google TV** (Chromecast con Google TV, o dispositivos Sony, TCL, Hisense, etc. con Google TV).
- **Nuvio** y/o **Stremio** instalado en el dispositivo.
- Una suscripción activa a TvRecommendationBridge (ver [Suscripción](#suscripción) abajo).

> **Nota:** TvRecommendationBridge está diseñada específicamente para dispositivos que utilizan el launcher de Google TV. No está diseñada para dispositivos Android TV que utilicen otros launchers ni para Fire TV.

## Instalación

TvRecommendationBridge no se distribuye actualmente a través de Google Play. La aplicación se instala manualmente ("sideload") mediante el archivo APK disponible en la sección de releases de este repositorio.

### Opción A: con el móvil usando Send Files to TV

1. En el TV, instala **[Send Files to TV](https://play.google.com/store/apps/details?id=com.jstenpal.sendfilestotv)** desde Google Play.
2. Abre la aplicación en el TV. Te mostrará una dirección o un código QR para establecer la conexión con el móvil.
3. Desde el navegador del móvil, accede a la dirección indicada y selecciona el archivo `app-release.apk`, que puedes descargar desde la sección de releases:
   👉 **[Descargar la última versión](https://github.com/Yushetf33/TvReccomendationBridge/releases/latest)**
4. Una vez transferido el archivo, el TV permitirá iniciar la instalación.
5. Si Android TV muestra un aviso relacionado con la instalación desde fuentes desconocidas, será necesario autorizar temporalmente la instalación desde esa fuente.

### Opción B: mediante ADB

1. Descarga el APK desde la sección de releases.
2. Activa las opciones de desarrollador en el TV:
   **Ajustes → Preferencias del dispositivo → Información → pulsa 7 veces sobre "Build"**.
3. Activa **Depuración USB** o **Depuración de red**, según el dispositivo.
4. Desde el ordenador:

```bash
adb connect <ip-del-tv>:5555
adb install app-release.apk
