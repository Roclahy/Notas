# Notas

Aplicación de notas para Android desarrollada por **Rosanna Moreno** ([roclahy.me](https://roclahy.me)).

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
