# Guía Completa del Subsistema de Llamadas de Voz (VoIP) sobre UDP y ZeroC Ice

Este documento detalla el funcionamiento técnico de las llamadas de voz en el proyecto, cómo probarlo entre múltiples computadores en una red local (LAN/Wi-Fi), y los comandos Git para gestionar tu rama y fusionarla a `main`.

---

## 1. ¿Cómo es esto una "llamada" si no usa servicio telefónico?

Las llamadas telefónicas convencionales viajan a través de la red celular o la red telefónica pública conmutada (PSTN), asignando circuitos dedicados mediante números de teléfono.

En cambio, este proyecto implementa **VoIP (Voice over Internet Protocol)**, el mismo principio fundamental en el que operan aplicaciones como **Discord, WhatsApp, Telegram, Microsoft Teams o Zoom**:

```
+--------------------------------------------------------------------------+
|                         CANAL DE CONTROL (ZeroC Ice / TCP)               |
|  Alice  ----[ "¿Aceptas llamada? Mi endpoint UDP es 192.168.1.15:52341" ]---> Bob |
|  Bob    <---[ "¡Sí, acepto! Mi endpoint UDP es 192.168.1.20:58122" ]-------- Alice |
+--------------------------------------------------------------------------+
                                     |
                                     v
+--------------------------------------------------------------------------+
|                         CANAL DE AUDIO EN VIVO (Datagramas UDP)           |
|  Micrófono Alice -> Bytes PCM -> DatagramPacket UDP ------------> Bob (Altavoces) |
|  Micrófono Bob   -> Bytes PCM -> DatagramPacket UDP ------------> Alice (Altavoces) |
+--------------------------------------------------------------------------+
```

### El proceso físico y digital en tiempo real:
1. **Captura y Muestreo (ADC):** Tu voz produce ondas de presión de aire que mueven la membrana del micrófono. La tarjeta de sonido digitaliza esa señal a través de la API estándar de Java (`javax.sound.sampled.TargetDataLine`) en formato **PCM (16 kHz, 16 bits, Mono)**.
2. **Empaquetamiento (Framing):** Cada 20 milisegundos se capturan 640 bytes de audio crudo (`16000 muestras/seg * 2 bytes/muestra * 0.02 seg = 640 bytes`).
3. **Transporte de Datagramas UDP (`DatagramPacket` / `DatagramSocket`):** Ese bloque de 640 bytes se envuelve en un datagrama UDP y se transmite directamente a la dirección IP y puerto UDP del receptor.
4. **¿Por qué UDP y no TCP?**
   * TCP retransmite paquetes perdidos y detiene el flujo hasta confirmarlos (retransmisión y control de congestión), lo que provocaría retrasos acumulativos (*jitter* y cortes).
   * En voz humana en vivo, si un paquete de 20 ms se pierde por fluctuación de red, es preferible ignorarlo y reproducir el siguiente de inmediato para mantener **latencia ultra baja**.
5. **Reproducción (DAC):** Un hilo receptor dedicado escucha en el socket UDP y vuelca los bytes a los altavoces (`javax.sound.sampled.SourceDataLine`), reconstruyendo las ondas acústicas audibles.

---

## 2. Arquitectura de Llamadas del Proyecto

El sistema divide limpiamente las responsabilidades:

### A. Canal de Señalización (ZeroC Ice sobre TCP)
Gestiona la lógica de sesión (quién llama a quién, si el usuario está ocupado, timbrado, aceptar, rechazar y colgar).
* **`startCall(from, to, ip, port)`**: El usuario emisor le indica al servidor que desea llamar a un destinatario y publica su IP y puerto UDP de audio.
* **`onIncomingCall(caller, ip, port)`**: Notificación asíncrona que timbra en el cliente destinatario.
* **`acceptCall(from, to, ip, port)`**: Al aceptar, el destinatario reporta su propio puerto UDP y el servidor se lo despacha al llamante original (`onCallAccepted`).
* **`rejectCall(from, to, reason)`**: Notifica rechazo y libera la sesión.
* **`endCall(from, to)`**: Notifica cuelgue, cerrando sockets y liberando líneas de hardware.

### B. Conferencia Grupal (RF-06: Malla Mesh sobre UDP)
* Al ejecutar `/vjoin <sala>`, el servidor registra al usuario en la sesión de audio de esa sala y le devuelve la lista de IP y puertos UDP de los demás participantes ya conectados.
* Simultáneamente, notifica a los demás mediante `onVoiceRoomJoined`.
* Cada cliente transmite copias de sus paquetes UDP de micrófono a todos los participantes de la sala de audio, sin necesidad de que el servidor procese audio pesado.
* `/mute` y `/unmute` suspenden temporalmente el envío de paquetes UDP sin desconectar al usuario de la sala.

---

## 3. ¿Cómo probarlo entre varios computadores en la misma red (LAN / Wi-Fi)?

Para probar entre dos o más computadores físicos (o una laptop y una PC de escritorio conectadas al mismo Wi-Fi o router):

### Paso 1: Obtener la IP del Computador donde correrá el Servidor
En la computadora que será el **Servidor (PC 1)**:
1. Abre una terminal de PowerShell o CMD.
2. Ejecuta:
   ```powershell
   ipconfig
   ```
3. Busca tu adaptador Wi-Fi o Ethernet y copia la **Dirección IPv4** (ejemplo: `192.168.1.15`).

### Paso 2: Permitir puertos en el Firewall de Windows (en la PC del Servidor)
Para evitar que Windows Firewall bloquee las conexiones de red entrantes desde el otro computador:
1. Abre PowerShell como Administrador en la PC del servidor y corre:
   ```powershell
   New-NetFirewallRule -DisplayName "Taller Chat Ice TCP" -Direction Inbound -LocalPort 10000 -Protocol TCP -Action Allow
   New-NetFirewallRule -DisplayName "Taller Chat Audio UDP" -Direction Inbound -Protocol UDP -Action Allow
   ```
   *(O simplemente permite Java cuando aparezca la ventana emergente del Firewall de Windows).*

### Paso 3: Iniciar el Servidor (en PC 1)
En la PC 1:
```bash
./gradlew runServer
```
El servidor quedará a la espera de conexiones en el puerto TCP `10000`.

### Paso 4: Iniciar el Cliente 1 (en PC 1)
En una nueva terminal en la PC 1:
```bash
./gradlew runClient
```
* Cuando te pregunte: `IP del servidor Ice (Enter para 127.0.0.1):`, presiona **Enter** (porque está en la misma máquina).
* Inicia sesión:
  ```
  /login daniel
  ```

### Paso 5: Iniciar el Cliente 2 (en PC 2)
Copia o clona el proyecto en la segunda computadora (PC 2).
En la terminal de la PC 2:
```bash
./gradlew runClient
```
* Cuando te pregunte: `IP del servidor Ice (Enter para 127.0.0.1):`, escribe la IP de la PC 1 (por ejemplo `192.168.1.15`) y presiona Enter.
* Inicia sesión con otro nombre:
  ```
  /login santiago
  ```

### Paso 6: Probar la Llamada 1 a 1 (RF-05)
1. En la PC 1 (`daniel`), escribe:
   ```
   /call santiago
   ```
2. En la PC 2 (`santiago`), verás inmediatamente la alerta de timbrado en tiempo real:
   ```
   [LLAMADA ENTRANTE] *** De: 'daniel' (IP: 192.168.1.15, Puerto UDP: 54123) ***
   Escribe '/accept' para contestar o '/reject' para rechazar.
   ```
3. En la PC 2, contesta con:
   ```
   /accept
   ```
4. **¡Listo!** Habla por el micrófono de una PC y escucharás tu voz en los altavoces de la otra PC.
5. Puedes probar silenciar tu micrófono con `/mute` y volver a hablar con `/unmute`.
6. Para colgar, escribe en cualquiera de las dos consolas:
   ```
   /hangup
   ```

### Paso 7: Probar Conferencia Grupal (RF-06)
1. Ambos usuarios se unen a una sala de chat:
   * En PC 1: `/create general`
   * En PC 2: `/join general`
2. Ambos entran a la sala de voz:
   * En PC 1: `/vjoin general`
   * En PC 2: `/vjoin general`
3. Ambas computadoras transmitirán y escucharán el audio de la sala simultáneamente.
4. Para salir de la conferencia de voz:
   ```
   /vleave
   ```

---

## 4. Resumen de Comandos de la Aplicación

| Comando | Acción |
| :--- | :--- |
| `/login <nick>` | Inicia sesión |
| `/logout` | Cierra sesión |
| `/users` | Ver usuarios conectados |
| `/msg <user> <texto>` | Mensaje privado directo |
| `/create <sala>` | Crea una sala de chat |
| `/join <sala>` | Entra a una sala de chat |
| `/send [sala] <texto>` | Envía mensaje a una sala |
| `/sendfile <target> <ruta>` | Envía archivo a un usuario o `#sala` por fragmentos |
| **`/call <usuario>`** | **Inicia llamada 1 a 1 sobre UDP** |
| **`/accept`** | **Acepta llamada entrante** |
| **`/reject [motivo]`** | **Rechaza llamada entrante** |
| **`/hangup`** | **Finaliza llamada 1 a 1** |
| **`/vjoin [sala]`** | **Entra a la llamada grupal de una sala** |
| **`/vleave`** | **Sale de la llamada grupal** |
| **`/mute` / `/unmute`** | **Silencia / Activa el micrófono** |
| `/status` | Ver estado actual y puertos asignados |
| `/help` | Muestra el menú de ayuda |
| `/quit` | Cierra la aplicación |

---

## 5. Instrucciones Git: Crear tu rama `cabalini`, hacer Push y Merge a `main`

A continuación tienes el procedimiento exacto paso a paso para guardar tu trabajo en tu rama personal `cabalini` y luego integrarla a `main`:

### Paso 1: Crear y cambiarte a la rama `cabalini`
```bash
git checkout -b cabalini
```

### Paso 2: Agregar todos los cambios realizados
```bash
git add .
```

### Paso 3: Confirmar los cambios con un commit descriptivo
```bash
git commit -m "feat: Implementacion completa de llamadas UDP 1-a-1, grupales Mesh y cliente interactivo"
```

### Paso 4: Subir tu rama al repositorio remoto (GitHub)
```bash
git push -u origin cabalini
```

### Paso 5: Fusionar tu rama con `main`
Una vez que tu rama está subida:
1. Cambia a la rama `main`:
   ```bash
   git checkout main
   ```
2. Asegúrate de tener la última versión de `main` (por si algún compañero subió algo):
   ```bash
   git pull origin main
   ```
3. Fusiona tu rama `cabalini` en `main`:
   ```bash
   git merge cabalini
   ```
4. Sube la rama `main` actualizada al repositorio remoto:
   ```bash
   git push origin main
   ```

### Paso 6 (Opcional): Si trabajas con Pull Requests en GitHub
Si en tu equipo o el profesor prefieren que los merges se hagan mediante Pull Request en GitHub:
1. Después de ejecutar el **Paso 4** (`git push -u origin cabalini`), ve a la página de tu repositorio en GitHub.
2. Verás un botón verde: **"Compare & pull request"**.
3. Creas el PR desde la rama `cabalini` hacia `main` y haces clic en **"Merge pull request"**.
