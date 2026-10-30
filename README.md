# Tian Demon TV

Pantalla de inicio para Android TV / Google TV con estilo demoníaco rojo y negro.
Basada en [Free My TV](https://github.com/simbasjungles/free-my-tv) (licencia MIT), con estos cambios:

- Íconos de apps cuadrados, con el nombre debajo del seleccionado.
- Saludo "BOO", hora en formato de 12 horas, fecha y clima en una línea.
- Música: píldora con carátula y botones, pantalla "Now Playing" completa con volumen y avance.
- Centro de control (engranaje): música, Wi-Fi, Bluetooth, audio, volumen, accesos rápidos, RAM y espacio.
- Fondos de pantalla propios: se agregan desde el celular con un código QR.
- Temporizador para apagar el TV (en X minutos, o cada noche a una hora).
- Ajustes cortos: tema, reloj, clima, ciudad.

## Instalar en el TV (con Downloader)

1. Instala **Downloader** en el TV.
2. Abre Downloader y escribe este enlace:

   `https://github.com/osmancardona/TianMyTV/releases/download/v0.1/TianMyTV.apk`

3. Descarga e instala (permite instalar apps de Downloader cuando el TV lo pida).
4. Activa **Opciones de desarrollador → Depuración USB** en el TV (la app la usa para ser tu pantalla de inicio).
5. Abre **Tian Demon TV**, entra a "Pantalla de inicio" y elige **Tian Demon TV**.

Para volver al original: abre la app → Pantalla de inicio → **Original**.

## Compilar y modificar

Necesitas JDK 17, el SDK de Android (build-tools y plataforma 34 o más nueva, con `aapt2`) y Git Bash en Windows.

```bash
export JAVA_HOME=/ruta/al/jdk17
export ANDROID_HOME=/ruta/al/sdk
./build.sh --generic
adb connect IP_DEL_TV:5555
adb install -r FreeMyTV.apk
```

- El código está en `app/src/io/freemytv/` (Java, sin Gradle). Los íconos y el logo están en `app/res/drawable-nodpi/` y `app/assets/`.
- `build.sh` crea tu propia clave (`freemytv.keystore`). **Guárdala**: sin ella, las próximas versiones no se instalan encima y hay que desinstalar.
- Tu versión firmada con otra clave no se instala encima de la mía: desinstala primero.

## Créditos y licencia

Basado en Free My TV de simbasjungles, MIT. Ver `LICENSE`.
