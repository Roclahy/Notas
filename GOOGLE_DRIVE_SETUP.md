# Notas — Google Drive cifrado opcional

Notas es una aplicación offline-first. Solo se utiliza Google Drive cuando el usuario activa expresamente el respaldo automático.

## Configuración necesaria antes de probar

1. Crear o seleccionar un proyecto en Google Cloud Console.
2. Habilitar **Google Drive API** en ese proyecto.
3. Configurar la pantalla de consentimiento OAuth para la app Notas.
4. Añadir el alcance `https://www.googleapis.com/auth/drive.file` (permite operar únicamente con archivos creados o abiertos por la aplicación).
5. Crear un cliente OAuth de **Android**, con paquete `com.rocla.notas` y SHA-1 del certificado Release persistente: `4C:12:50:EE:09:B4:D3:CB:B1:98:3F:3F:3F:82:22:5A:DC:57:A9:06`. Este certificado corresponde al APK Release de Notas 1.2.0. No usar el certificado Debug en este cliente. Las compilaciones Debug requieren un cliente registrado por separado.
6. La persona usuaria debe aceptar la autorización desde **Ajustes → Google Drive** en su dispositivo. No se guardan access tokens en el código de Notas.

## Destino real

La app crea una carpeta visible **Mi unidad/Notas** mediante Google Drive API v3. Cada copia nueva se cifra localmente en formato `.rnotas` usando AES-256-GCM y PBKDF2 antes de subirla. Se conservan las últimas 7 copias que Notas creó, eliminando las más antiguas después de una carga exitosa. El archivo cifrado temporal en caché se elimina al terminar.

El respaldo diario utiliza WorkManager con NetworkType.CONNECTED y espera exponencial. La versión 1.4.0 detecta también cada cambio de notas/listas, espera 30 segundos para agrupar las escrituras y programa otro respaldo cifrado automático. Puede pedirse una copia inmediata pulsando el indicador RefreshCw junto a Ajustes en Inicio o antes del PIN en el editor. La frecuencia es aproximada, pues Android puede posponer tareas por ahorro de batería. La restauración actual permite seleccionar el archivo `.rnotas` desde el proveedor Google Drive del selector de documentos Android y escribir su contraseña de recuperación; no requiere que el respaldo automático esté activado.

## Limitaciones

- Play Services y conectividad son necesarios solo para el respaldo en Drive; notas, listas, dibujos y búsquedas permanecen offline.
- OAuth exige un proyecto Google Cloud configurado correctamente. No se ha validado la autorización ni se ha probado la API en un dispositivo.
- La app no pide alcance completo sobre Drive: usa `drive.file`.
- La contraseña cifrada en Android Keystore permite trabajos automáticos; el usuario debe conservar la contraseña original para restaurar en otro dispositivo.
- Al desactivar el respaldo, Notas cancela los trabajos y elimina la contraseña almacenada en Keystore, pero no borra automáticamente las copias de Google Drive.
- No publicar la aplicación ni presentar Drive como funcional hasta probar autorización, subida, listados y restauración con una cuenta real.

Fuentes oficiales:
https://developer.android.com/identity/authorization
https://developers.google.com/workspace/drive/api/guides/api-specific-auth
https://developers.google.com/workspace/drive/api/guides/manage-uploads

## Estado de implementación

Código integrado en la rama de desarrollo. El disparador automático de GitHub Actions fue desactivado: las pruebas de compilación y la verificación de subida/restauración están pendientes de autorización explícita de la propietaria.


### 1.4.0 — significado real de los indicadores

* **RefreshCwIcon**: la versión de la nota aparece en la última copia cifrada que Google Drive aceptó. En Inicio indica que todas las notas, listas, eliminaciones y preferencias incluidas en el respaldo coinciden con esa copia.
* **RefreshCwOffIcon**: no hay una copia de Drive confirmada para los datos actuales, falta autorización o quedan cambios locales.
* **Forzar**: guarda primero la nota si está en edición y añade un trabajo de copia inmediato con Internet obligatorio. Hasta confirmar la subida, no se muestra el icono sincronizado.
* No es sincronización bidireccional ni resolución automática de conflictos entre dispositivos. La restauración sigue siendo manual mediante una copia .rnotas y su contraseña.
* Es preciso configurar OAuth (paquete com.rocla.notas y SHA-1 Release), habilitar Google Drive API y comprobar autorización y subida reales en un dispositivo. Sin ello el indicador permanece pendiente.
* Se utiliza Jetpack WindowInsetsCompat con bordes del sistema y teclado en vez de barras opacas. Las pruebas en Android 7/14/15 siguen pendientes.
