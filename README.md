# Rose

Rose es una aplicación de notas para Android desarrollada por **Rosanna Moreno** ([roclahy.me](https://roclahy.me)).

Proyecto de código abierto inspirado en la sencillez de las interfaces de Apple, sin afiliación a Apple.

## Funciones

- Notas y listas de tareas, imágenes, grabaciones, etiquetas, recordatorios, copias de seguridad y widgets.
- Temas claro, oscuro, AMOLED y sistema.
- Acento dorado #F1C45B, navegación inferior flotante y tarjetas redondeadas.
- Editor con contador de caracteres y menú contextual.
- Traducción española completa y tipografía Notas Sans (derivada de Inter).

## Compilación

Requiere JDK 21 y Android SDK 36. La variante de pruebas se compila mediante ./gradlew :app:assembleDebug.
La variante de distribución optimizada con R8 y reducción de recursos se obtiene mediante ./gradlew :app:assembleRelease.

El repositorio no contiene claves privadas de firma. La variante Release requiere una firma persistente para futuras actualizaciones.

## Procedencia y licencias

Este proyecto deriva de **Notally**, creado por Om Godse, y mantiene la licencia GNU GPLv3. El historial del fork y LICENSE.md se preservan para conservar la atribución y las condiciones del software libre.

La fuente Notas Sans está basada en Inter, bajo SIL Open Font License 1.1 (copia en app/src/main/assets/OFL.txt). El nombre se cambió por los requisitos de denominación de la licencia.

Repositorio de Notas: https://github.com/Roclahy/Notas

## Respaldo privado (1.2.0)

Notas funciona sin Internet y no solicita `android.permission.INTERNET`. Los respaldos `.rnotas` se cifran **antes** de enviarse al proveedor elegido con AES-256-GCM y PBKDF2 (250 000 iteraciones, sal e IV aleatorios por copia). La contraseña de recuperación no se sube al proveedor; para el respaldo automático se almacena cifrada usando Android Keystore del dispositivo (Android 6+).

El almacenamiento en Google Drive es opcional a través del selector de archivos de Android (Storage Access Framework), únicamente si el proveedor ofrece carpetas persistentes con permiso de escritura. La frecuencia es cada 24 horas, gestionada por WorkManager; si falta conexión o falla el proveedor, se reintenta con backoff exponencial. También se pueden exportar/importar copias cifradas manualmente desde Drive sin habilitar respaldo automático.

Incluye base de notas y tareas, imágenes y grabaciones adjuntas, y preferencias de visualización. Los PIN, contraseña de copia y destinos de nube **no** se exportan. Las copias no cifradas que ya existieran no se transforman automáticamente; no deben conservarse en un almacenamiento compartido si contienen datos sensibles.

La opción de PIN protege la interfaz; no cifra el contenido de la base de datos local. Las copias cifradas sí reciben cifrado autenticado con contraseña. Android Auto Backup del sistema queda deshabilitado.

## Identidad Rose

Icono de rosa blanca sobre negro basado en la ilustración aportada por la propietaria. Entrada animada nativa de 950 ms en negro puro, sin dependencias de animación ni recursos GIF en el APK.

## Google Drive

El respaldo automático de Rose está diseñado para la carpeta visible `Mi unidad/Rose` mediante OAuth y Google Drive API v3, en lugar de una carpeta del almacenamiento interno. Consulta [GOOGLE_DRIVE_SETUP.md](GOOGLE_DRIVE_SETUP.md) para configurar Google Cloud y la firma. La funcionalidad está pendiente de compilación y pruebas en Android.

### Icono oficial del launcher
La rosa blanca se incorpora como imagen WebP reducida de la ilustración aportada por la propietaria, sin redibujar su forma. El interior de la aplicación continúa llamándose «Notas». La rama de desarrollo activa la compilación solo cuando cambia `.github/rose-build-request.txt` o mediante un disparo manual explícito.
