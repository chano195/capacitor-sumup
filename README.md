# @devlas/capacitor-sumup-android

Plugin de Capacitor para pagos con SumUp en Android. Soporta dos familias de integración:

- **Reader SDK** — lectores de tarjetas Bluetooth (SumUp Air, PIN+)
- **Tap to Pay** — NFC directo en el dispositivo Android (condicional, requiere credenciales Maven privadas)

Incluye fallback web seguro para desarrollar la UI en el navegador sin errores.

## Requisitos

| Dependencia | Versión |
|---|---|
| `@capacitor/core` | `>= 5.0.0` |
| SumUp Merchant SDK | `5.0.3` |
| Android `minSdk` | `30` |
| Android `compileSdk` / `targetSdk` | `36` |
| Java | `17` |
| Kotlin | `1.9.22` |

### Requisitos Tap to Pay (opcional)

Tap to Pay depende del SDK privado `com.sumup.tap-to-pay:utopia-sdk:1.0.4`. Para incluirlo en el build es necesario proveer credenciales Maven de SumUp. El plugin las busca en este orden de prioridad:

1. **`android/local.properties`** (recomendado)
   ```properties
   sumupMavenUser=TU_USUARIO
   sumupMavenPassword=TU_PASSWORD
   ```
2. **`.env`** en la raíz del proyecto host (legacy)
   ```env
   sumupMavenUser=TU_USUARIO
   sumupMavenPassword=TU_PASSWORD
   ```
3. **Variables de entorno del sistema**
   ```
   SUMUP_MAVEN_USER=TU_USUARIO
   SUMUP_MAVEN_PASSWORD=TU_PASSWORD
   ```

Si no hay credenciales válidas, el plugin **compila igual** pero Tap to Pay queda no disponible en runtime (los métodos retornan `code: TAP_NOT_AVAILABLE`). Los fuentes de Tap to Pay se compilan desde `android/src/main/taptopay/` solo cuando las credenciales están presentes.

## Instalación

```bash
npm install @devlas/capacitor-sumup-android
npx cap sync android
```

## Uso rápido

```typescript
import { SumUp } from '@devlas/capacitor-sumup-android'

// 1. Inicializar una vez al arrancar la app
await SumUp.setup()

// 2. Iniciar sesión (con token OAuth o pantalla nativa)
await SumUp.login({
  affiliateKey: 'TU_AFFILIATE_KEY',
  accessToken: 'token-oauth-opcional',
})

// 3. Verificar sesión
const { isLoggedIn } = await SumUp.isLoggedIn()

// 4. Cobro con lector de tarjetas
const resultado = await SumUp.checkout({
  amount: 15000,
  currencyCode: 'CLP',
  title: 'Pedido #42',
  skipSuccessScreen: true,
})

console.log(resultado.transaction_code, resultado.status)

// 5. Cobro Tap to Pay (NFC)
await SumUp.initTapToPay({ affiliateKey: 'TU_KEY', apiToken: 'TOKEN_BACKEND' })

SumUp.addListener('tapToPayEvent', (event) => {
  console.log(event.event, event.message) // cardRequested, cardPresented, etc.
})

const pago = await SumUp.tapToPayCheckout({
  amount: 15000,
  currency: 'CLP',
  processCardAs: 'DEBIT',
})
```

## Referencia de API

### Reader SDK (lector Bluetooth)

#### `setup(): Promise<SumUpResponse>`

Inicializa el SDK (`SumUpState.init`). Debe llamarse una vez antes de cualquier otro método.

#### `login(options): Promise<SumUpResponse>`

| Parámetro | Tipo | Requerido | Descripción |
|---|---|---|---|
| `affiliateKey` | `string` | ✅ | Clave de afiliado SumUp |
| `accessToken` | `string` | ❌ | Token OAuth2. Si se omite, se abre la pantalla nativa de login |

#### `logout(): Promise<SumUpResponse>`

Cierra la sesión del comerciante.

#### `isLoggedIn(): Promise<SumUpLoginStatus>`

Retorna `{ code: number, isLoggedIn: boolean }`.

#### `openCardReaderPage(): Promise<SumUpResponse>`

Abre la página nativa de configuración Bluetooth del lector.

#### `prepareForCheckout(): Promise<SumUpResponse>`

Pre-conecta el lector BLE para acelerar el siguiente cobro.

#### `checkout(options): Promise<SumUpPaymentResult>`

| Parámetro | Tipo | Requerido | Descripción |
|---|---|---|---|
| `amount` | `number` | ✅ | Monto total (mínimo 1.00) |
| `title` | `string` | ❌ | Descripción de la transacción |
| `currencyCode` | `string` | ❌ | ISO 4217 (ej: `CLP`, `EUR`) |
| `tipOnCardReader` | `boolean` | ❌ | Solicitar propina en el hardware del lector |
| `tip` | `number` | ❌ | Monto fijo de propina |
| `skipSuccessScreen` | `boolean` | ❌ | Omitir pantalla de éxito del SDK |
| `skipFailedScreen` | `boolean` | ❌ | Omitir pantalla de error del SDK |
| `foreignTransactionId` | `string` | ❌ | ID externo único (máx 128 caracteres) |

#### `closeConnection(): Promise<SumUpResponse>`

Desconecta el lector de tarjetas.

### Tap to Pay (NFC en dispositivo Android)

#### `initTapToPay(options): Promise<SumUpResponse>`

| Parámetro | Tipo | Requerido | Descripción |
|---|---|---|---|
| `affiliateKey` | `string` | ✅ | Clave de afiliado SumUp |
| `apiToken` | `string` | ✅ | Bearer token obtenido desde el backend |

#### `tapToPayCheckout(options): Promise<SumUpPaymentResult>`

| Parámetro | Tipo | Requerido | Descripción |
|---|---|---|---|
| `amount` | `number` | ✅ | Monto a cobrar |
| `currency` | `string` | ✅ | ISO 4217 (ej: `CLP`) |
| `processCardAs` | `'CREDIT' \| 'DEBIT'` | ✅ | Tipo de proceso (requerido en Chile) |
| `installments` | `number` | ❌ | Número de cuotas (solo crédito; 0 = sin cuotas) |
| `description` | `string` | ❌ | Descripción del cobro |
| `foreignTransactionId` | `string` | ❌ | ID externo único |

#### `isTapToPayReady(): Promise<{ ready: boolean }>`

Retorna si el SDK Tap to Pay está inicializado y listo para cobrar.

#### `teardownTapToPay(): Promise<SumUpResponse>`

Libera recursos del SDK Tap to Pay.

#### `addListener('tapToPayEvent', fn): Promise<PluginListenerHandle>`

Escucha eventos intermedios del flujo de cobro NFC.

```typescript
SumUp.addListener('tapToPayEvent', (event) => {
  // event.event: 'sdkReady' | 'cardRequested' | 'cardPresented' | 'pinRequired' | 'paymentStarting'
  // event.message: descripción opcional
})
```

### Tipos de retorno comunes

```typescript
interface SumUpResponse {
  code: number
  message: string
}

interface SumUpPaymentResult {
  transaction_code: string
  merchant_code: string
  amount: number
  tip_amount: number
  vat_amount: number
  currency: string
  status: 'PENDING' | 'SUCCESSFUL' | 'CANCELLED' | 'FAILED'
  payment_type: string  // CASH | POS | ECOM | UNKNOWN | RECURRING | TAP_TO_PAY | ...
  entry_mode: string    // CHIP | CONTACTLESS | NFC | ...
  installments: number
  card_type: string     // MASTERCARD | VISA | ...
  last_4_digits: string
  receipt_sent: boolean
}
```

## Fallback Web

En plataformas no-Android, todos los métodos retornan `{ code: -1, message: 'SumUp no disponible en web' }` de forma silenciosa. Los métodos `checkout()` y `tapToPayCheckout()` lanzan una excepción. Esto permite desarrollar la UI en el navegador sin errores en runtime.

## Notas de implementación

- **Hilo principal**: todas las llamadas al SDK se despachan automáticamente al hilo UI de Android.
- **Activities nativas**: `login()`, `checkout()` y `openCardReaderPage()` lanzan Activities. La Promise se resuelve cuando la Activity termina.
- **Permisos Bluetooth**: el plugin declara `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `ACCESS_FINE_LOCATION` y `ACCESS_COARSE_LOCATION` en su `AndroidManifest.xml`.

## Publicar / actualizar

El paquete ejecuta `npm run build` automáticamente en `prepack` y `prepublishOnly`.

```bash
# Validar antes de publicar
npm run build
npm pack --dry-run

# Publicar
npm publish --access public
```

El directorio `dist/` se mantiene versionado en el repositorio para permitir consumo directo desde Git sin paso de build adicional.

## Atribución

Este proyecto es **software libre** bajo licencia MIT. Puedes usarlo, modificarlo y distribuirlo libremente, incluso en proyectos comerciales. Solo te pedimos una cosa:

> **Si usas este plugin en tu proyecto, da crédito al autor original.**

Formas válidas de dar crédito:

- Mención en el README de tu proyecto: _"Usa [capacitor-sumup-android](https://github.com/devlas-cl/capacitor-sumup-android-sdk) por DEVLAS SPA"_
- Mención en la sección "Acerca de" o "Créditos" de tu aplicación
- Mantener la nota de copyright en el archivo LICENSE (esto es **obligatorio** por la licencia MIT)

No es obligatorio pedir permiso para usarlo, pero un ⭐ en GitHub siempre se agradece.

## Licencia

MIT — Copyright (c) 2026 [DEVLAS SPA](https://devlas.cl)

Consulta el archivo [LICENSE](LICENSE) para los términos completos.
