# TvRecommendationBridge

App para Android TV / Google TV que detecta cuando pulsas una tarjeta de
película o serie recomendada en el launcher nativo de Google TV, y la abre
directamente en la ficha correspondiente de **Nuvio** o **Stremio** (tú
eliges cuál) — en vez de la app de streaming "oficial" a la que apuntaría
el launcher por defecto.

## Requisitos

- Un dispositivo con el launcher **Google TV** (Chromecast con Google TV,
  o ediciones Google TV de Sony/TCL/Hisense/etc. — no vale un Android TV
  "a secas" con otro launcher, ni Fire TV).
- [Nuvio](https://nuvio.tv/) y/o [Stremio](https://www.stremio.com/)
  instalado en el dispositivo.
- Una suscripción activa a TvRecommendationBridge (ver [Suscripción](#suscripción) abajo).

## Instalación

La app no está en Google Play — se instala manualmente ("sideload") desde
el archivo APK de este repositorio. Dos formas de hacerlo:

### Opción A: con el móvil, usando "Send Files to TV" (la más fácil)

1. En el TV, instala la app **[Send files to TV](https://play.google.com/store/apps/details?id=com.jstenpal.sendfilestotv)** desde Google Play.
2. Ábrela: te mostrará una dirección o QR para conectar desde el móvil.
3. Desde el navegador del móvil, entra en esa dirección y sube el archivo
   `app-release.apk` que puedes descargar aquí:
   👉 **[Descargar la última versión](https://github.com/Yushetf33/TvReccomendationBridge/releases/latest)**
4. El TV recibirá el archivo y te ofrecerá instalarlo. Si te avisa de
   "origen desconocido", acepta — es normal al instalar fuera de Google
   Play.

### Opción B: por ADB, desde un ordenador

1. Descarga el APK desde la misma página de arriba.
2. Activa la depuración USB/red en el TV (**Ajustes → Preferencias del
   dispositivo → Información → pulsa 7 veces sobre "Build"** para activar
   Opciones de desarrollador, luego **Depuración USB** o **Depuración de
   red**).
3. Desde el ordenador:
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
