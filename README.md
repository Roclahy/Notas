# Notas


Notas es una aplicación gratuita y de código abierto de notas para Android: ligera, minimalista y con cifrado. Desarrollada por **Rosanna Moreno** ([roclahy.me](https://roclahy.me)).

Sitio oficial: [notas.roclahy.com](https://notas.roclahy.com/)


## Funciones


- Notas de texto y listas de tareas, con etiquetas, recordatorios, notas fijadas y archivo.
- Adjuntar fotos y grabaciones de audio a las notas.
- Dibujar con el dedo y guardar el dibujo dentro de la nota.
- Carpetas para organizar las notas, con fondo propio y opción de protegerlas con contraseña.
- Fondos personalizados en las notas y en las carpetas.
- Bloqueo de notas individuales con PIN y PIN de acceso a la aplicación (4 dígitos). El PIN restringe el acceso desde la app; no cifra las notas.
- Enlaces internos: al seleccionar una palabra o frase en el editor, el menú contextual permite enlazarla a otra nota («Enlazar nota») o a una carpeta («Enlazar carpeta»), además de enlaces web.
- Editor limpio para escribir sin distracciones, con contador de caracteres y formato de texto (negrita, cursiva, monoespaciado, tachado).
- Widgets para la pantalla de inicio.
- Temas claro, oscuro, AMOLED y según el sistema, con acento dorado #F1C45B y navegación inferior flotante.
- Interfaz en español e inglés, con tipografía Notas Sans (derivada de Inter).
- Funciona sin Internet: las notas se guardan solo en el dispositivo. Sin publicidad, analíticas ni rastreadores.


## Copia de seguridad cifrada en Google Drive


- Respaldo opcional en el Google Drive de cada usuario, mediante Google Drive API v3 y el permiso `drive.file`: la app solo puede ver y gestionar los archivos que ella misma crea.
- Cada copia se cifra en el dispositivo con AES-256-GCM, con clave derivada de la contraseña que elige el usuario (PBKDF2), antes de subirla. La contraseña no se envía a Google ni a la desarrolladora: sin ella, la copia no se puede leer.
- Las copias se guardan en la carpeta visible «Notas» de Mi unidad y se conservan las últimas 7.
- El respaldo es automático cuando cambian las notas y una vez al día (WorkManager); si no hay conexión, la copia queda pendiente y se reintenta al recuperarla. También hay respaldo manual y exportación e importación de copias cifradas.
- La copia incluye las notas, las listas, los adjuntos (imágenes y grabaciones) y las preferencias de visualización. No incluye el PIN ni la contraseña de respaldo.
- Es una copia de seguridad para recuperar las notas, no una sincronización entre dispositivos.
- Si el respaldo no está activado, la app no envía nada por Internet. La copia automática de Android (Auto Backup) está deshabilitada.


## Compilación


Requiere JDK 21 y Android SDK 36. La variante de pruebas se compila mediante ./gradlew :app:assembleDebug.

La variante de distribución optimizada con R8 y reducción de recursos se obtiene mediante ./gradlew :app:assembleRelease.

El repositorio no contiene claves privadas de firma. La variante Release requiere una firma persistente para futuras actualizaciones.


## Procedencia y licencias


Este proyecto deriva de **Notally**, creado por Om Godse, y mantiene la licencia GNU GPLv3. El historial del fork y LICENSE.md se preservan para conservar la atribución y las condiciones del software libre.

La fuente Notas Sans está basada en Inter, bajo SIL Open Font License 1.1 (copia en app/src/main/assets/OFL.txt). El nombre se cambió por los requisitos de denominación de la licencia.

Repositorio de Notas: https://github.com/Roclahy/Notas
