# Asistente de ajedrez para Android

La app juega SIEMPRE con blancas y mueve primero. El rival juega con negras. La partida siempre empieza desde cero.

## Qué hace
1. **Escanear tablero**: cámara en vivo (CameraX) + OpenCV. Propone las 4 esquinas del tablero y vos las ajustás arrastrando.
2. **Confirmar tablero** con las piezas en la posición inicial: la app detecta sola de qué lado están las blancas (el lado de piezas más claras) y guarda la imagen de referencia.
3. **Seguimiento**: compara cada casilla con la última posición confirmada y busca cuál jugada LEGAL explica los cambios (incluye enroque, captura al paso y coronación). Con 5 cuadros seguidos iguales la acepta. Las piezas y sus colores se siguen por las reglas, no se "adivinan" de una foto suelta.
4. **Stockfish** (UCI, local, sin internet) calcula la mejor jugada de blancas: flecha, casillas resaltadas, notación, explicación, evaluación, variante principal, alternativas, profundidad y tiempo ajustables.
5. Cuando hacés la jugada, la cámara la verifica: ✔ si coincide con la recomendada, ⚠ si jugaste otra.

## Correcciones
- EDITAR PIEZAS: tocás una casilla y elegís qué pieza va (o vacía), indicás quién mueve y tocás LISTO. Valida reyes, peones y jaque ilegal; reinicia el historial.
- Jugada mal detectada o no detectada: tocá la pieza y la casilla destino en el tablero digital, o usá DESHACER.
- Después de corregir a mano, el escaneo se actualiza solo cuando el tablero queda quieto. También podés usar ACTUALIZAR ESCANEO.
- Si movés el celular: REAJUSTAR ESQUINAS.
- Si el escaneo ve el tablero girado: GIRAR ESCANEO.
- GIRAR (debajo del tablero digital) solo gira el dibujo en pantalla.

## Poner Stockfish (obligatorio)
1. Descargá el binario **Android arm64 (armv8)** de https://stockfishchess.org/download/ o de GitHub (official-stockfish/Stockfish, Releases).
2. Copialo como `app/src/main/jniLibs/arm64-v8a/libstockfish.so` (nombre exacto).
3. Stockfish es GPLv3: si distribuís la app, publicá el código.

## Compilar e instalar
1. Android Studio (Koala o más nuevo) > Open > carpeta `AjedrezAsistente`. Esperá la sincronización de Gradle (necesita internet esa primera vez para bajar OpenCV y CameraX).
2. Celular arm64 con depuración USB. No sirve el emulador x86.
3. Run. Al tocar ESCANEAR TABLERO, aceptá el permiso de cámara.
4. Tests: `./gradlew test` (reglas con perft, y el decisor de jugadas de la cámara).

## Consejos para que ande bien (Etapa 8)
- Celular fijo (trípode o apoyado) mirando el tablero desde arriba o en diagonal alta; todo el tablero a la vista y sin que tape la mano de quien juega.
- Luz pareja, sin reflejos fuertes ni sombras que cambien al mover la mano.
- Las 4 esquinas van sobre el área de las 64 casillas (no el marco).
- Si detecta jugadas de más o no detecta ninguna, ajustá los valores de `VisionConfig` en `vision/MoveMatcher.kt` (el mensaje de la cámara muestra qué casillas cambiaron).
- Cuadros cada ~120 ms y análisis en 512x512: liviano para celulares modestos. Bajá la profundidad/tiempo de Stockfish si va lento.

## Límites conocidos
- Probado solo por lógica y tests de reglas; la parte de cámara necesita que la pruebes con tu tablero real y quizás ajustes constantes.
- Piezas muy altas vistas de muy costado pueden "tapar" casillas vecinas: conviene una vista más cenital.

## Obtener el .apk sin instalar Android Studio
1. Creá un repositorio en GitHub y subí todo el contenido de esta carpeta (incluida `.github`).
2. Pestaña **Actions** > "Compilar APK" > **Run workflow**. Baja Stockfish solo y compila.
3. Al terminar (unos minutos), entrá a la ejecución y bajá el artefacto `AjedrezAsistente-apk` (contiene `app-debug.apk`).
4. Pasalo al celular, permití "instalar apps desconocidas" e instalalo.
