# Lector de contador (Android)

App Android (Kotlin + Jetpack Compose) que lee un contador eléctrico a través de una **sonda óptica USB** con los protocolos **IEC 62056-21 (modo C)** y **DLMS/COSEM**. Contadores con perfil propio: **ZIV 5CTB (ZIVT4)** y **Enel CERM1 (1G)**.

## Funciones
- **Leer y guardar**: lectura puntual (energía activa total, **hasta 6 periodos P1–P6**, potencia, tensión, intensidad) que se guarda en el histórico. Solo se muestran los periodos que el contador devuelve (2.0TD: P1–P3; 3.0TD/6.1TD: P1–P6).
- **Tres tipos de lectura** (botones *Consumo*, *FV* y *Todo*): el contador devuelve siempre todos sus registros; el botón elige qué se muestra y se guarda en detalle.
  - **Consumo**: energía activa importada (1.x.x), reactiva importada (3.x.x), instantáneos (tensión, intensidad…) e identificadores.
  - **FV**: energía y potencia activa/reactiva exportadas (2.x.x, 4.x.x: `2.8.0` total, `2.8.1`…`2.8.6` por periodo, `2.7.0` potencia), más instantáneos e identificadores. Si el contador no los devuelve, la app lo indica.
  - **Todo**: todos los códigos OBIS, también los no clasificados.
- **Todos los valores de cada línea**: si una línea trae varios paréntesis (p. ej. máxima demanda con su fecha, `1.6.1(02.5*kW)(2610011500)`), se conservan todos. Los códigos conocidos se muestran con su descripción en castellano.
- **Modo en vivo**: lecturas repetidas cada 10/30/60 s sin guardarlas. Se detiene solo tras 3 errores seguidos.
- **Histórico**: lista de lecturas y gráfica de consumo diario (últimos 14 días).
- **Exportar Excel**: un libro `lecturas_<contador>_*.xlsx` con el mismo formato que el informe «Sant Llorenç»: hoja **Resum Executiu** (cierres mensuales con energía activa, reactiva y cos φ, incremento de consumo entre cierres y gráfico de barras), **Lectures Actuals** (última lectura), una hoja **Tancament NN - Mes** por cada mes (la 01 es la más reciente; el cierre de un mes es la primera lectura guardada de ese mes; máximo 12) y, si hay datos, una hoja **OBIS complet** con todos los códigos guardados. Cada hoja tiene energía activa y reactiva P1–P6 con sus totales y fórmulas de Excel. Las columnas «Codi OBIS» muestran `1.8.x` / `3.8.x` (ZIV) o `1.18.x` / `1.58.x` (Enel). La ubicación del título se cambia en **Ajustes**. La reactiva solo aparece si el contador la entrega (3.8.x / 1.58.x); las lecturas guardadas antes de esta versión no la tienen.
- **Accesos rápidos a cada contador**: en la pestaña Lectura hay una tarjeta con la foto de cada contador (ZIV 5CTB y Enel CERM1). Al tocarla se elige ese contador y se abre **Ajustes**. Cada contador guarda sus propios ajustes, histórico y exportaciones. Las fotos están en `app/src/main/res/drawable-nodpi/` (`meter_ziv.jpg`, `meter_enel.jpg`; se pueden sustituir con el mismo nombre). Para añadir otro contador, agrega una entrada a `MeterProfile` en `MeterConfig.kt`.
- **DLMS/COSEM (ZIV 5CTB y otros contadores inteligentes)**: el contador se identifica por IEC 62056-21 (modo E), la app cambia a la velocidad acordada (8N1), abre el enlace HDLC, se asocia y lee los registros OBIS estándar por GET (con escalador y unidad). Ajustes DLMS: cliente, contraseña de lectura (LLS) y dirección de servidor. En Diagnóstico: «Diagnóstico DLMS» (prueba servidores y clientes habituales, solo lectura) y «Listar objetos».
- Interfaz íntegramente en castellano.
- Ver todos los registros OBIS que devuelve el contador (útil para depurar).

- **Diagnóstico**: para contadores de protocolo desconocido. Prueba 300/1200/2400/4800/9600 baud en 7E1 y 8N1, envía solo la petición de lectura `/?!` y muestra los bytes recibidos (ASCII y hex). El informe se puede compartir.

## Cómo abrirlo
1. Android Studio (Ladybug o posterior) → *Open* → carpeta `LectorContador`.
2. Deja que sincronice Gradle (necesita internet; la librería USB viene de JitPack).
3. Conecta el móvil con depuración USB y pulsa *Run*.
4. Los tests del protocolo están en `app/src/test` (`./gradlew test` o clic derecho → *Run*).

## Si Android Studio no abre o sincroniza el proyecto
- Abre la carpeta que contiene directamente `settings.gradle.kts` (no la subcarpeta `app`).
- Incluye `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.9), que Android Studio usa para descargar Gradle. Si ofrece «actualizar/crear el Gradle wrapper», acepta.
- Si algo falla, copia el texto completo del error de la pestaña **Build** (abajo) para poder corregirlo.

## Obtener el APK sin Android Studio (GitHub Actions)
1. Crea un repositorio en https://github.com/new y sube todo el contenido de esta carpeta (incluida `.github`).
2. En la pestaña **Actions** se ejecuta «Build APK» (o lánzalo con *Run workflow*).
3. Al terminar, entra en la ejecución y descarga `LectorContador-debug-apk` desde **Artifacts**. Descomprime el ZIP para obtener `app-debug.apk`.

## Uso
1. Conecta la sonda al móvil con un adaptador **USB OTG**. Android pedirá permiso USB.
2. Coloca la sonda sobre el puerto óptico del contador (imán/alineada con el LED).
3. Pulsa **Consumo**, **FV** o **Todo** para leer y guardar.

## Cómo funciona el protocolo
1. Envía `/?!\r\n` a 300 baud, 7E1.
2. El contador responde `/XXXZ<identificación>`; `Z` es la velocidad propuesta (5 = 9600).
3. La app envía `ACK 0 Z 0 \r\n` y cambia a esa velocidad.
4. Recibe `STX <líneas OBIS> ! ETX BCC`, comprueba el BCC y lee los registros.

Registros que interpreta: `1.8.0` (total), `1.8.1` … `1.8.6` (periodos P1–P6), `1.7.0` (potencia), `32.7.0` (tensión), `31.7.0` (intensidad), `0.0.0` (nº de serie). Convierte W/Wh a kW/kWh automáticamente.

## Enel / e-distribuzione CERM1 (1ª generación)
Contador italiano con puerto óptico. Su protocolo no es público, así que el protocolo **Enel 1G (automático)** prueba por el puerto óptico, en orden, **IEC 62056-21 modo C** (texto con códigos OBIS, p. ej. `1.18.2` / `1.58.2`) y, si no envía datos, **DLMS/COSEM** (modo E) con los ajustes DLMS del contador. Si ninguno responde, el error resume por qué falló cada uno. Suele tener clave de acceso y **no se ha probado con un contador real**: ejecuta el diagnóstico y comparte el informe si falla.

## Importante: contadores en España
- **ZIV 5CTB / contadores inteligentes**: antes la app solo hablaba el modo C de IEC 62056-21 y estos contadores se identifican pero no envían datos en modo C («Tiempo agotado esperando los datos del contador»). Ahora se usa DLMS/COSEM. El cliente público (16) suele dar solo identificación y reloj; para la energía normalmente hace falta el cliente y la contraseña de lectura que fija la distribuidora. No está probado con un contador real: si falla, ejecuta «Diagnóstico DLMS» y comparte el informe.
- Comprueba el modelo de tu contador y su documentación de puerto óptico antes de comprar la sonda.
- Si lees solo parte de los registros, pulsa **Todo** y abre «Ver valores OBIS» para ver qué códigos devuelve tu modelo; los nuevos se pueden clasificar en `ObisInfo.kt`.
- Usa solo el puerto óptico de **tu** contador y respeta el precinto: no lo manipules.

## Estructura
- `Iec62056.kt`: protocolo, BCC y parser OBIS (sin dependencias de Android).
- `MeterLink.kt`: secuencia de lectura sobre el puerto serie.
- `UsbConnector.kt`: detección de la sonda y permiso USB.
- `ReadingStore.kt`: histórico (CSV interno) y exportación a Excel.
- `Xlsx.kt`: escritor de .xlsx sin dependencias (estilos, celdas combinadas, fórmulas, gráfico). `ExcelReport.kt`: construye el informe con el formato del Excel de referencia.
- `Dlms.kt`, `DlmsDiscovery.kt`, `MeterDlmsLink.kt`, `DiagnosticsDlms.kt`: DLMS/COSEM sobre HDLC. `MeterConfig.kt`, `SettingsScreen.kt`: ajustes y perfiles de contador.
- `Diagnostics.kt`, `DiagnosticScreen.kt`, `ByteFormat.kt`: modo de diagnóstico.
- `Stats.kt`, `MeterModels.kt`, `MeterViewModel.kt`, `Ui.kt`, `MainActivity.kt`.

## Novedades de esta versión

- **Contraseñas DLMS**: no existe una contraseña universal; la fija la distribuidora para cada cliente. En Ajustes se escribe tal cual (ASCII, o bytes con prefijo `0x`). «Probar mi contraseña con cada cliente» (Diagnóstico) la prueba una sola vez con los clientes 1, 2, 3, 17, 32 y 48 y guarda el que funcione. La app no prueba contraseñas por fuerza bruta.
- **Lectura parcial**: si el contador solo entrega la identificación (cliente público), se muestra con un aviso en vez de un error, y no se guarda en el histórico.
- **Rendimiento**: en modo en vivo la sesión DLMS se mantiene abierta (no se renegocia cada lectura) y se reconecta sola si se cae; los registros que el contador niega o no tiene no se vuelven a pedir; ajustes e histórico se cargan fuera del hilo principal; las lecturas se guardan en segundo plano.
- **Diseño**: tema azul/ámbar claro y oscuro, tarjeta principal con barras por periodo, mosaicos de potencia/tensión/intensidad, accesos rápidos con foto, iconos de navegación.
