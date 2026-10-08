# Music (app Android)

Reproductor de música que lee las canciones descargadas en tu teléfono, sin internet.
Muestra la portada de cada canción, tiene controles en la notificación y en la pantalla de bloqueo,
y en Ajustes puedes elegir cuánto tiempo no se repite una canción en modo aleatorio (por defecto 1 hora).

## Opción A: sin instalar nada (GitHub genera el APK)
1. Crea una cuenta gratis en github.com y un repositorio nuevo (privado está bien).
2. Sube TODO el contenido de esta carpeta al repositorio. Importante: debe incluir la carpeta oculta .github
   (si tu sistema no la muestra, crea el archivo en GitHub con "Add file > Create new file", escribe
   .github/workflows/build.yml y pega el contenido de ese archivo).
3. Ve a la pestaña Actions y espera a que termine "Construir APK" (3 a 6 minutos).
   Si no empieza sola: Actions > Construir APK > Run workflow.
4. Entra a la ejecución terminada, baja a Artifacts y descarga "Music-apk" (es un zip con app-debug.apk).
5. Pasa el APK al teléfono, ábrelo y acepta "Instalar apps desconocidas" cuando Android lo pida.

## Opción B: con Android Studio
1. Instala Android Studio, elige Open y selecciona esta carpeta. Espera a que termine la sincronización.
2. Menú Build > Build Bundle(s) / APK(s) > Build APK(s).
3. Pulsa "locate" y copia app/build/outputs/apk/debug/app-debug.apk al teléfono.
   (O conecta el teléfono con depuración USB y pulsa Run.)

## Primer uso
Al abrir Music, acepta el permiso para leer tu música y el de notificaciones.
Si descargas canciones nuevas, toca "Actualizar".
