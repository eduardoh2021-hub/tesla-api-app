# Mis Rutas EV

App Android (Android 13+, pensada para Android 16 / MagicOS) que registra automáticamente las rutas de un Tesla
(probada en diseño para el Model 3 Highland) y muestra un historial con **kilómetros, duración, velocidad media,
velocidad máxima, energía, consumo (kWh/100 km), batería, temperatura y coste**, además de estadísticas.

> Proyecto personal, sin relación con Tesla, Inc. Solo **lee** datos: nunca envía órdenes al coche ni lo despierta.

---

## Cómo obtiene los datos

Tesla ya no permite a las apps de terceros usar la antigua API "no oficial" con los coches nuevos (como el Highland).
La vía oficial, la misma que usan TeslaMate, Tessie o TeslaFi, es la **[Tesla Fleet API](https://developer.tesla.com/docs/fleet-api)**:

1. Te registras gratis como desarrollador en developer.tesla.com y creas una "app" propia → recibes un **Client ID** y un **Client Secret**.
2. Tesla exige publicar una **clave pública** en un dominio tuyo (vale uno gratuito de GitHub Pages).
3. Inicias sesión con tu cuenta Tesla en la web oficial de Tesla y autorizas a tu app a leer *Información del vehículo* y *Ubicación*.
4. La app consulta `vehicle_data` (cuentakilómetros, % y autonomía de batería, marcha, velocidad, posición, temperatura).

**Lo único que necesitas aportar:** tu cuenta Tesla (la contraseña solo se escribe en la web de Tesla), el Client ID y el
Client Secret de tu app de desarrollador y un dominio de GitHub Pages. El VIN se obtiene solo.

### Detección de rutas (y por qué es barata)

La Fleet API cobra **0,002 $ por lectura** (Tesla regala **10 $/mes** a cada cuenta). Consultar el coche cada minuto todo el día
costaría más de eso, así que la app usa el **Bluetooth del coche** como disparador:

- Al conectar el móvil al Bluetooth del Tesla arranca un servicio que lee el coche cada 60 s (configurable).
- La ruta empieza con la primera lectura en marcha (D/R/N) y termina al aparcar 5 min o al desconectarse el Bluetooth.
- Con una hora diaria de conducción salen unas 1.800 lecturas al mes ≈ 3,6 $, dentro del crédito gratuito.

### Modo respaldo (rutas sin el móvil)

Si conduces sin el móvil, o MagicOS cierra la app, esos km no se pierden:

- **Cada 24 h**, solo si en las últimas 24 h no ha habido conexión Bluetooth con el coche, la app consulta
  `vehicle_data_cached` (la última lectura que el coche envió a Tesla; **nunca lo despierta**).
- Al **empezar cada sesión** de registro también se compara con la última lectura conocida.
- Si el cuentakilómetros ha avanzado ≥ 1 km, se guarda una **ruta no detallada** con los km y el consumo
  (autonomía perdida + kWh cargados entre medias). No tiene hora exacta ni velocidad, y puede agrupar varios trayectos;
  el consumo incluye lo que gastó el coche aparcado. Cuesta 1 lectura al día (≈ 0,06 $/mes).

Se activa o desactiva en *Ajustes › 8 · Modo respaldo*, donde también se puede lanzar una comprobación al momento.

### Actualizaciones desde la app

*Ajustes › 9 · Actualizaciones › Buscar actualización* consulta la última *Release* de este repositorio. Si su etiqueta
(p. ej. `v1.2`) es más nueva que la versión instalada, descarga el APK y abre el instalador de Android para confirmar.
La primera vez Android pide permitir que *Mis Rutas EV* instale apps. Para publicar una versión basta con crear una
Release con una etiqueta `vX.Y`: GitHub Actions compila el APK firmado y lo adjunta.

### Cálculos

| Dato | Cómo se calcula |
|---|---|
| Distancia | Diferencia del cuentakilómetros del coche (exacto) |
| Duración | Del primer movimiento a la parada, interpolando entre lecturas con la velocidad |
| Velocidad media | Distancia / duración |
| Energía | Autonomía homologada perdida × (capacidad útil de fábrica / autonomía de fábrica al 100 %). Se usa la autonomía porque el % de batería es un número entero y sería poco preciso en trayectos cortos; usar las cifras de fábrica evita que la degradación infle el consumo |
| Consumo | Energía / distancia × 100 (kWh/100 km) |
| Coste | Energía × precio €/kWh configurado |

Capacidad útil por defecto: 60 kWh (Propulsión trasera LFP 2026: 63 kWh brutos, 60 kWh netos). Para Gran Autonomía / Performance usa 75 kWh.

### Degradación de la batería (pestaña Batería)

La API de lectura no da la capacidad interna del BMS (solo se ve en modo servicio o con Fleet Telemetry), así que se estima de dos formas:

1. **Por autonomía al 100 %**: cada vez que el coche se lee con ≥ 90 % de carga se guarda `autonomía / % × 100`, que es lo que el
   BMS cree que cabe. Salud = autonomía al 100 % actual (mediana de las 5 últimas) ÷ autonomía de fábrica. Como referencia de fábrica
   se usa la mayor vista o la que fijes en Ajustes. Capacidad útil hoy = capacidad de fábrica × salud.
2. **Por cargas**: si entre dos lecturas la batería ha ganado ≥ 25 puntos, capacidad ≈ `charge_energy_added ÷ % ganado`.

Con LFP conviene cargar al 100 % al menos una vez por semana (lo recomienda Tesla y recalibra el BMS); las lecturas justo después
son las más precisas. También puedes pulsar *Probar lectura* en Ajustes tras cargar. Se muestran la salud, la tendencia cada
10.000 km y las gráficas de salud por km y de capacidad por carga.

### Estadísticas

Filtro por 30 días / mes / año / todo: totales, consumo medio ponderado, coste, récords, consumo de cada ruta con tendencia,
consumo según temperatura exterior, consumo por tramos de velocidad media, km y consumo por mes (12 meses).
Exportación a CSV (separado por `;`, abre directamente en Excel en español).

---

## Puesta en marcha (≈ 30 min, una sola vez)

### A. Dominio gratuito con GitHub Pages

La clave tiene que estar en la **raíz** de un dominio, así que no sirve el repositorio del código (`usuario.github.io/Tesla-api-app/`).
Crea un segundo repositorio **público** llamado exactamente `TU_USUARIO.github.io` y sube:

```
.nojekyll                                          (vacío; necesario para que se publique la carpeta .well-known)
callback.html                                      (está en la carpeta github-pages/ de este proyecto)
.well-known/appspecific/com.tesla.3p.public-key.pem (la genera la app en el paso D-2)
```

En el repositorio: *Settings › Pages › Deploy from a branch › main / (root)*. Tu dominio será `TU_USUARIO.github.io`.
La clave pública es pública por diseño; la privada nunca sale del móvil.

### B. App de desarrollador en Tesla

1. Entra en <https://developer.tesla.com> con tu cuenta Tesla (exige verificación en dos pasos).
2. *Create application*: nombre único **solo con letras y números, sin espacios, sin acentos y sin la palabra "Tesla"**
   (p. ej. `MisRutasTuNombre2026`); si da "nombre no válido", prueba otro. Descripción y propósito: uso personal para registrar mis rutas.
3. **OAuth Grant Type:** *Authorization Code and Machine-to-Machine*.
4. **Allowed Origin(s):** `https://TU_USUARIO.github.io`
5. **Allowed Redirect URI(s):** `https://TU_USUARIO.github.io/callback.html`
6. **Scopes:** *Vehicle Information* y *Vehicle Location*.
7. Copia el **Client ID** y el **Client Secret**.
8. En *Billing* añade un método de pago y un límite (p. ej. 10 $). Sin esto Tesla desactiva la app aunque no llegues a pagar nada.

### C. Instalar el APK

Copia `MisRutasEV.apk` al móvil y ábrelo. MagicOS pedirá permitir *instalar apps desconocidas* para el gestor de archivos.

### D. Configurar la app (pestaña Ajustes)

1. **App de desarrollador:** región Europa, Client ID, Client Secret y dominio `TU_USUARIO.github.io` → Guardar.
2. **Clave pública:** *Generar clave* → *Copiar/Compartir* → súbela a GitHub como `.well-known/appspecific/com.tesla.3p.public-key.pem`. Espera 1-2 min y pulsa *Comprobar*.
3. **Registro en la región:** *Registrar* (una vez).
4. **Iniciar sesión:** se abre la web de Tesla, aceptas los permisos y vuelves a la app. Si no vuelve sola, copia la dirección de la página y pégala en el campo.
5. **Vehículo:** se elige solo si solo tienes uno. *Probar lectura* con el coche despierto.
6. **Bluetooth:** concede permisos, elige el Bluetooth de tu Tesla, quita la restricción de batería y, en MagicOS:
   *Ajustes › Batería › Inicio de aplicaciones › Mis Rutas EV* → desactiva *Gestionar automáticamente* y activa
   *Inicio automático*, *Inicio secundario* y *Ejecutar en segundo plano*. Sin esto MagicOS puede cerrar la app y perder rutas.
7. **Cálculos:** capacidad de fábrica de tu batería (60 kWh netos para la LFP 2026 de 63 kWh brutos), autonomía de fábrica al 100 % (opcional) y precio de la luz.

---

## Limitaciones

- Solo se registran los viajes en los que **tu móvil va en el coche** con el Bluetooth activo (o si inicias el registro a mano).
- Al pedir la ubicación, el coche muestra un icono de "ubicación compartida" en la pantalla: es normal.
- El consumo es una estimación basada en la autonomía que informa el coche (incluye climatización durante la marcha).
- El trazado del mapa tiene un punto por lectura (cada 60 s), no es un GPS continuo.
- Los datos se guardan solo en el móvil. Exporta CSV de vez en cuando como copia de seguridad.

## Compilar

Requiere JDK 17 y Android SDK (plataforma 36). Con Android Studio basta abrir la carpeta. Desde consola:

```bash
./gradlew assembleRelease
```

El APK queda en `app/build/outputs/apk/release/app-release.apk` (firmado con la clave de depuración, para uso personal).

## Privacidad

No hay ningún dato personal en este repositorio. Client Secret, tokens y clave privada se guardan cifrados con el
Android Keystore en el propio móvil y no se envían a ningún sitio salvo a los servidores oficiales de Tesla.
