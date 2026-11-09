# Transcriptor YT

App Android de uso personal: pegas un enlace de YouTube (o lo compartes desde la app de YouTube) y muestra la transcripción.

- Elige automáticamente subtítulos en español, luego inglés (manuales antes que automáticos). Si hay más pistas, aparece un selector.
- Opción de mostrar marcas de tiempo.
- Muestra el título y el canal del vídeo; al copiar, compartir o exportar el título va incluido.
- **Colección**: ve añadiendo transcripciones con «+ Colección» (se guardan en el móvil aunque cierres la app).
- **Varios enlaces a la vez**: pega uno por línea y se transcriben todos y se añaden a la colección.
- **Exportar** una sola o toda la colección a **PDF** (índice + cada vídeo en su página con título) o **TXT**.

## Conseguir el APK sin instalar nada (GitHub Actions)

1. Crea un repositorio nuevo (privado) en GitHub.
2. Sube el contenido de esta carpeta (incluida la carpeta oculta `.github`). Desde el móvil: «Add file → Upload files» para el resto, y para el workflow «Add file → Create new file», escribe como nombre `.github/workflows/build.yml` y pega su contenido.
3. Ve a la pestaña **Actions** → se ejecuta "Compilar APK" (unos 3-5 min).
4. Entra en la ejecución terminada → sección **Artifacts** → descarga `TranscriptorYT-apk` (un .zip con `app-debug.apk`).
5. Pásalo al móvil, ábrelo y permite "instalar apps de origen desconocido".

## Con Android Studio

Abrir la carpeta → Build → Build APK(s).

## Límites

Solo funciona con vídeos que tengan subtítulos (manuales o automáticos). No transcribe el audio por su cuenta.
Usa endpoints no oficiales de YouTube: si un día deja de funcionar, suele bastar con actualizar la versión de cliente en `TranscriptFetcher.java`.
