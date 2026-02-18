/**
 * capacitor-sumup – TypeScript definitions
 *
 * Tipos compartidos entre la capa web (fallback) y el bridge nativo Android.
 * Basado en el SumUp Android Reader SDK v5 y los tipos del plugin Cordova original.
 */

// ── Request types ──────────────────────────────────────────

export interface SumUpLoginOptions {
  /** Affiliate key generada en el dashboard de SumUp */
  affiliateKey: string
  /** Token OAuth2 (opcional). Si se omite, se muestra la pantalla de login nativa */
  accessToken?: string
}

export interface SumUpPaymentOptions {
  /** Monto total a cobrar (mínimo 1.00) */
  amount: number
  /** Título descriptivo de la transacción */
  title?: string
  /** Código ISO de moneda (ej: "CLP", "EUR"). Si se omite, usa la del comerciante */
  currencyCode?: string
  /** Solicitar propina directamente en el lector (si el hardware lo soporta) */
  tipOnCardReader?: boolean
  /** Monto de propina fijo (ignorado si tipOnCardReader es true) */
  tip?: number
  /** Omitir pantalla de éxito del SDK */
  skipSuccessScreen?: boolean
  /** Omitir pantalla de error del SDK */
  skipFailedScreen?: boolean
  /** ID de transacción externo (máx 128 chars, debe ser único) */
  foreignTransactionId?: string
}

// ── Response types ─────────────────────────────────────────

export interface SumUpResponse {
  code: number
  message: string
}

export interface SumUpLoginStatus {
  code: number
  isLoggedIn: boolean
}

export interface SumUpPaymentResult {
  transaction_code: string
  merchant_code: string
  amount: number
  tip_amount: number
  vat_amount: number
  currency: string
  /** PENDING | SUCCESSFUL | CANCELLED | FAILED */
  status: string
  /** CASH | POS | ECOM | UNKNOWN | RECURRING | BITCOIN | BALANCE */
  payment_type: string
  /** Ej: CHIP, CONTACTLESS */
  entry_mode: string
  installments: number
  /** Ej: MASTERCARD, VISA */
  card_type: string
  last_4_digits: string
  receipt_sent: boolean
}

// ── Plugin interface ───────────────────────────────────────

export interface SumUpPlugin {
  /** Inicializa el SDK (SumUpState.init). Llamar una vez al iniciar la app. */
  setup(): Promise<SumUpResponse>

  /** Abre la pantalla de login nativa de SumUp */
  login(options: SumUpLoginOptions): Promise<SumUpResponse>

  /** Cierra la sesión del comerciante */
  logout(): Promise<SumUpResponse>

  /** Verifica si hay una sesión activa */
  isLoggedIn(): Promise<SumUpLoginStatus>

  /** Abre la página de configuración del lector de tarjetas */
  openCardReaderPage(): Promise<SumUpResponse>

  /** Pre-conecta el lector BLE para acelerar el siguiente pago */
  prepareForCheckout(): Promise<SumUpResponse>

  /** Inicia el flujo de cobro con el lector de tarjetas */
  checkout(options: SumUpPaymentOptions): Promise<SumUpPaymentResult>

  /** Cierra la conexión con el lector de tarjetas */
  closeConnection(): Promise<SumUpResponse>
}
