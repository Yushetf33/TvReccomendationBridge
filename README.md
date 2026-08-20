# TvRecommendationBridge

Aplicación para Android TV / Google TV que permite personalizar el comportamiento de las recomendaciones del launcher de Google TV.

Cuando el usuario selecciona una tarjeta de película o serie compatible, TvRecommendationBridge identifica el contenido y permite abrir su ficha en **Nuvio** o **Stremio**, según la aplicación configurada por el usuario.

TvRecommendationBridge es una herramienta independiente de automatización y redirección. **No aloja, almacena, distribuye ni proporciona películas, series, streams, torrents ni ningún otro contenido audiovisual.** No tiene ninguna relación con Nuvio, Stremio, ni con el origen o la legalidad del contenido al que el usuario acceda a través de esas aplicaciones — eso depende únicamente de qué apps y complementos tenga instalados y configurados cada usuario, bajo su propia responsabilidad.

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
```

### Activar el servicio

Una vez instalada, abre la app **TvRecommendationBridge** desde el
launcher del TV y pulsa **"Activar el servicio en Accesibilidad"** (te
llevará directamente a la pantalla correspondiente). Actívala ahí.

En algunos dispositivos (sobre todo cajas Android TV de fabricantes
chinos con gestores de batería agresivos) puede hacer falta además
excluir la app de cualquier "optimizador"/limpiador de RAM del propio
fabricante, o el sistema matará el proceso del servicio a los pocos
segundos. Si el servicio aparece activado en Ajustes pero deja de
detectar clics al rato, ese suele ser el motivo.

## Suscripción

TvRecommendationBridge requiere una suscripción de pago para funcionar:

| Plan | Precio |
|---|---|
| Mensual | 2,50 € / mes |
| Semestral | 7,99 € / 6 meses |
| Anual | 12,99 € / año |

Dentro de la app verás un QR para cada plan — escanéalo con el móvil y
paga con el mismo email que luego vas a introducir en el TV (tarjeta,
Google Pay, Apple Pay... lo que te ofrezca la pasarela de pago). Una vez
pagado, vuelve a la app, escribe ese email y pulsa **"Verificar
suscripción"**.

Un mismo email puede vincularse hasta a **3 dispositivos** a la vez (por
si tienes varios TVs en casa).

## Cómo funciona

El launcher de Google TV expone el título de cada recomendación al
pulsarla. La app reconoce esa pulsación, busca el título en una base de
datos pública de películas y series para identificar de qué contenido se
trata, y abre directamente su ficha en Nuvio o Stremio (según cuál
hayas elegido en los ajustes de la app).

## Limitaciones conocidas

- Ocasionalmente, un título puede coincidir con el de otra película o
  serie homónima poco conocida y abrir la ficha equivocada.

## Aviso legal

TvRecommendationBridge es una herramienta independiente de navegación y automatización para dispositivos Android TV / Google TV.

La aplicación no aloja, almacena, distribuye ni proporciona películas, series, streams, torrents ni fuentes de contenido audiovisual.

Su función se limita a detectar determinadas recomendaciones mostradas por el launcher de Google TV, identificar el contenido seleccionado y facilitar la apertura de su ficha mediante aplicaciones de terceros instaladas y configuradas por el usuario.

TvRecommendationBridge no proporciona ni controla las fuentes de contenido disponibles dentro de dichas aplicaciones.

El usuario es responsable del uso que haga de las aplicaciones de terceros y de asegurarse de que dicho uso cumple la legislación aplicable y los términos de servicio correspondientes.

TvRecommendationBridge no está afiliada, patrocinada, autorizada ni respaldada por Google, Google TV, Nuvio o Stremio.

Google, Google TV, Android TV, Nuvio y Stremio son marcas o productos de sus respectivos propietarios.

## Créditos

TvRecommendationBridge utiliza la API de **The Movie Database (TMDB)** para identificar películas y series.

[TMDB](https://www.themoviedb.org/)

> This product uses the TMDB API but is not endorsed or certified by TMDB.

The Movie Database (TMDB) y su logotipo son marcas de sus respectivos propietarios.
