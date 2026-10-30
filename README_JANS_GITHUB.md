# Jan's — compilación automática en GitHub

Este repositorio conserva la base nativa de Auxio y prepara GitHub Actions para compilar una APK de depuración en GitHub.

## Qué hace la automatización

- Se ejecuta al hacer `push` o abrir un `pull request` hacia `main`, `master` o `jans-development`.
- También se puede iniciar manualmente desde la pestaña **Actions** mediante **Run workflow**.
- Descarga los submódulos fijados por el repositorio, instala Java 21 y los componentes del Android SDK requeridos.
- Ejecuta `:app:assembleDebug`.
- Si la compilación termina correctamente, publica la APK como artefacto durante 14 días.

## Cómo obtener la APK

1. Sube este proyecto a un repositorio de GitHub.
2. Abre la pestaña **Actions** del repositorio y habilita los workflows si GitHub lo solicita.
3. Haz un `push` a `main` o `jans-development`, o inicia **Jan's Android Build** manualmente.
4. Abre la ejecución más reciente que tenga el indicador verde.
5. En **Artifacts**, descarga `Jans-debug-...` y extrae el archivo APK.

## Compilación local (opcional)

Se requiere Java 21, Android SDK con API 37, NDK `28.2.13676358`, CMake y los submódulos inicializados:

```bash
git submodule update --init --recursive
chmod +x ./gradlew
./gradlew --no-daemon :app:assembleDebug
```

La APK de depuración se genera en `app/build/outputs/apk/debug/`.

## Importante: estado del producto

La automatización de compilación no significa que todas las funciones solicitadas para Jan's estén implementadas. Este repositorio es la base de trabajo y la configuración de CI; las modificaciones funcionales deben incorporarse y probarse por etapas antes de considerar Jan's terminado. No se debe publicar una compilación como versión final hasta superar las verificaciones de compilación y pruebas.
