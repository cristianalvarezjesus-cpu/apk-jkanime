# Visor Web

App Android (WebView) para `https://jkanime.net/` con bloqueo de anuncios, sin popups ni redirecciones a otros sitios, y con zoom y navegación pensados para el celular. El APK se compila solo con GitHub Actions: no necesitas instalar Android Studio.

## Qué hace

- **Bloqueo de anuncios**: cancela las peticiones a dominios de `blocklist.txt` y oculta banners con CSS/JS (`inject.js`).
- **Sin popups**: `window.open`, `alert`, `confirm` y `prompt` neutralizados; no se crean ventanas nuevas.
- **Sin redirecciones**: solo se navega dentro de `jkanime.net`. Cualquier otro dominio, `intent://` o `market://` se bloquea sin abrir nada.
- **Descargas deshabilitadas**.
- **Video**: pantalla completa con rotación automática a horizontal.
- **Zoom**: pellizco con dos dedos, doble toque (nativo de WebView) y opciones *Acercar/Alejar* en el menú.
- **Navegación**: botón atrás recorre el historial, deslizar hacia abajo recarga, barra de progreso, pantalla de error con *Reintentar*.
- **Menú flotante (⋮)**: Inicio, Recargar, texto más grande/pequeño, zoom, pantalla completa, modo oscuro de la página y bloqueo estricto de iframes.

## 1. Subir el proyecto a GitHub

```bash
cd VisorWeb
git init
git add .
git commit -m "Primer commit"
git branch -M main
git remote add origin https://github.com/TU_USUARIO/TU_REPO.git
git push -u origin main
```

(También puedes crear el repositorio en la web y subir los archivos arrastrándolos.)

## 2. Descargar el APK

1. En tu repositorio abre la pestaña **Actions** y entra a la ejecución *Build APK* (tarda unos minutos).
2. Al terminar, baja a **Artifacts** y descarga `VisorWeb-apk`. Es un ZIP que contiene el APK.
3. Pásalo al celular e instálalo (Android te pedirá permitir instalar apps de orígenes desconocidos).

Sin configurar nada, el workflow genera un **APK debug** (`VisorWeb-debug.apk`), firmado con la clave de depuración. Sirve perfectamente para uso personal.

## 3. (Opcional) APK release firmado con tu propia clave

Con tu propio keystore, las actualizaciones se instalan encima de la versión anterior sin desinstalar.

**Generar el keystore** (en tu PC, con JDK instalado):

```bash
keytool -genkeypair -v -keystore release.keystore -alias visor -keyalg RSA -keysize 2048 -validity 10000
```

**Convertirlo a base64**:

```bash
# Linux
base64 -w0 release.keystore > keystore.base64
# macOS
base64 -i release.keystore -o keystore.base64
# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore")) | Out-File keystore.base64
```

**Cargar los Secrets**: en GitHub ve a *Settings → Secrets and variables → Actions → New repository secret* y crea:

| Secret | Valor |
|---|---|
| `KEYSTORE_BASE64` | contenido de `keystore.base64` |
| `KEYSTORE_PASSWORD` | contraseña del keystore |
| `KEY_ALIAS` | `visor` (el alias que usaste) |
| `KEY_PASSWORD` | contraseña de la clave |

Desde el siguiente push, el workflow compila `assembleRelease` firmado y publica `VisorWeb.apk`.
**Guarda tu keystore en un lugar seguro y nunca lo subas al repositorio** (el `.gitignore` ya lo excluye).

**Release con enlace de descarga fijo**: sube un tag, por ejemplo `git tag v1.0 && git push origin v1.0`. El APK queda adjunto en la sección **Releases**.

## 4. Si algo falla

### Se cuela un anuncio
1. Averigua su dominio (ver más abajo cómo inspeccionar).
2. Agrégalo a `app/src/main/assets/blocklist.txt`.
3. Haz push: GitHub recompila el APK.

Si el anuncio es un elemento de la página (no una petición externa), agrega un selector CSS en la lista `AD_SELECTORS` de `app/src/main/assets/inject.js`.

Además puedes activar **Bloqueo estricto de iframes** en el menú ⋮: bloquea cualquier iframe que no sea del sitio ni de `allowlist_players.txt`. Es más agresivo contra anuncios, pero puede impedir que cargue algún reproductor.

### Un video no carga
Probablemente su dominio está bloqueado o (con el modo estricto) no está permitido. Agrégalo a `app/src/main/assets/allowlist_players.txt`. La lista que viene incluida es solo un punto de partida con servidores comunes: no está verificada contra el sitio.

### Cómo inspeccionar dominios
1. Compila un APK **debug** (el que genera el workflow sin Secrets) e instálalo.
2. Activa la depuración USB en el celular y conéctalo a la PC.
3. En Chrome de escritorio abre `chrome://inspect`, elige el WebView de *Visor Web* y mira la pestaña **Network** y los iframes en **Elements**.

### El sitio cambió de dirección
Edita `SITE_DOMAINS` en `app/src/main/java/com/ejemplo/visor/AdBlocker.kt` y la constante `HOME_URL` en `MainActivity.kt`.

## Estructura

```
.github/workflows/build-apk.yml      compilación automática
app/build.gradle.kts                 configuración del módulo
app/src/main/AndroidManifest.xml
app/src/main/java/com/ejemplo/visor/
    MainActivity.kt                  WebView, menú, pantalla completa, zoom
    AdBlocker.kt                     reglas de bloqueo
app/src/main/assets/
    blocklist.txt                    dominios de anuncios
    allowlist_players.txt            dominios de reproductores
    inject.js                        script anti-anuncios
app/src/main/res/                    layouts, temas, íconos
```

## Notas

- El proyecto no incluye `gradlew` ni `gradle-wrapper.jar`: el workflow instala Gradle 8.9 por su cuenta. Si abres el proyecto en Android Studio, usará su propio Gradle.
- Ningún bloqueador es perfecto: las redes publicitarias cambian de dominio seguido, por eso las listas son editables.
- Permisos: solo `INTERNET` y `ACCESS_NETWORK_STATE`.
