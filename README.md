# TvRecommendationBridge

Accessibility Service para Android TV / Google TV que detecta cuando pulsas
una tarjeta de película o serie recomendada en el launcher nativo de Google
(`com.google.android.apps.tv.launcherx`), resuelve el título a su IMDb ID
vía [TMDb](https://www.themoviedb.org/), y abre [Nuvio](https://nuvio.tv/)
directamente en la ficha de esa película o serie — en vez de la app de
streaming "oficial" a la que apuntaría el launcher por defecto.

Los clics en cualquier otro elemento (iconos de apps, pestañas de
navegación, banners patrocinados, etc.) se ignoran por completo y el
sistema procesa el clic con su comportamiento normal.

## Requisitos

- Un dispositivo con el launcher **Google TV** (Chromecast con Google TV,
  o ediciones Google TV de Sony/TCL/Hisense/etc. — no vale un Android TV
  "a secas" con otro launcher, ni Fire TV).
- [Nuvio](https://nuvio.tv/) instalado en el dispositivo.
- Una API key gratuita de TMDb: https://www.themoviedb.org/settings/api

## Instalación

1. Clona el repo y ábrelo en Android Studio.
2. Crea/edita `local.properties` en la raíz del proyecto y añade tu key:
   ```
   TMDB_API_KEY=tu_key_aqui
   ```
3. Conecta el TV por ADB (o USB) y ejecuta la app (▶ en Android Studio, o
   `./gradlew installDebug`).
4. En el TV: **Ajustes → Accesibilidad → TvRecommendationBridge → activar**.

En algunos dispositivos (sobre todo cajas Android TV de fabricantes
chinos con gestores de batería agresivos) puede hacer falta además
excluir la app de cualquier "optimizador"/limpiador de RAM del propio
fabricante, o el sistema matará el proceso del servicio a los pocos
segundos. Si el servicio aparece activado en Ajustes pero deja de
detectar clics al rato, ese suele ser el motivo — revisa los ajustes de
batería/segundo plano específicos de tu TV.

## Ver logs en tiempo real

```bash
adb -s <ip>:5555 logcat -s TvRecService:D StremioLauncher:D TmdbClient:D
```

Al pulsar una tarjeta deberías ver algo como:

```
D/TvRecService: Película/serie detectada: Iron Man
D/TvRecService: IMDb ID resuelto: Iron Man -> tt0371746 (MOVIE)
D/StremioLauncher: Abriendo Nuvio: nuvio://movie/tt0371746
```

## Cómo funciona

El launcher de Google TV expone el título y algo de metadata (precio,
puntuación de Rotten Tomatoes, o la plataforma) en el `content-description`
de accesibilidad de cada tarjeta cuando se pulsa. El servicio:

1. Escucha eventos `TYPE_VIEW_CLICKED` limitados al paquete del launcher.
2. Si el `content-description` (o, en el caso del carrusel superior de la
   pestaña Inicio, el texto del evento) tiene pinta de tarjeta de
   contenido — y no de icono de app o banner patrocinado — extrae el
   título.
3. Busca el título en `TMDb /search/multi` (películas y series mezcladas,
   ordenadas por relevancia) para resolver el `imdb_id` y el tipo de
   contenido.
4. Abre Nuvio con `nuvio://movie/{imdbId}` o `nuvio://detail/tv/{imdbId}`
   según corresponda.

## Limitaciones conocidas

- **Comando de voz de Google Assistant** ("Ok Google, pon X"): no es
  interceptable. Assistant resuelve el contenido usando su propio
  Knowledge Graph y solo ofrece como destino apps que son partners
  oficiales de streaming registrados con Google — Nuvio y apps de
  comunidad similares nunca aparecen ahí, sin importar qué haga esta app.
  No es un límite de permisos de Android, es de partnership con Google.
- **Algunas tarjetas de la fila "Recomendaciones destacadas para ti" de
  la pestaña Inicio** (p.ej. contenido de RTVE Play) pueden no
  detectarse: el launcher rellena su `content-description` con retraso
  tras el clic, y a veces sigue vacío incluso con el reintento que hace
  el servicio.
- Títulos con paréntesis de versión ("(VO)", "(VE)", "(VOSE)") se limpian
  antes de buscar en TMDb, pero coincidencias con títulos homónimos poco
  conocidos siguen siendo posibles (TMDb no es infalible).
