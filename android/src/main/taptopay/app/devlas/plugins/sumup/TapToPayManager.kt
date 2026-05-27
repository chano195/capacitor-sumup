package app.devlas.plugins.sumup

import android.content.Context
import android.util.Log
import com.sumup.taptopay.TapToPay
import com.sumup.taptopay.TapToPayApiProvider
import com.sumup.taptopay.auth.AuthTokenProvider
import com.sumup.taptopay.payment.domain.model.api.AffiliateModel
import com.sumup.taptopay.payment.domain.model.api.CheckoutData
import com.sumup.taptopay.payment.domain.model.api.PaymentEvent
import com.sumup.taptopay.payment.domain.model.api.ProcessCardAs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch

class TapToPayManager : TapToPayBridge {

    companion object {
        private const val TAG = "TapToPayManager"
    }

    private var sdk: TapToPay? = null
    private var isInitialized = false
    private var affiliateKey: String = ""
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var eventCallback: TapToPayBridge.EventCallback? = null

    private fun throwableDebugMap(t: Throwable?): Map<String, Any> {
        if (t == null) {
            return mapOf(
                "exceptionClass" to "",
                "message" to "",
                "causeClass" to "",
                "causeMessage" to "",
                "stackTop" to "",
                "sdkErrorCode" to ""
            )
        }

        val stackTop = t.stackTrace.firstOrNull()?.toString() ?: ""
        val sdkErrorCode = runCatching {
            val getter = t.javaClass.methods.firstOrNull { it.name == "getCode" && it.parameterCount == 0 }
            getter?.invoke(t)?.toString() ?: ""
        }.getOrDefault("")

        return mapOf(
            "exceptionClass" to t.javaClass.name,
            "message" to (t.message ?: ""),
            "causeClass" to (t.cause?.javaClass?.name ?: ""),
            "causeMessage" to (t.cause?.message ?: ""),
            "stackTop" to stackTop,
            "sdkErrorCode" to sdkErrorCode
        )
    }

    override fun setEventCallback(callback: TapToPayBridge.EventCallback) {
        eventCallback = callback
    }

    override fun initialize(
        context: Context,
        affiliateKey: String,
        apiToken: String,
        callback: TapToPayBridge.InitCallback
    ) {
        if (isInitialized && sdk != null) {
            callback.onResult(true, null)
            return
        }

        this.affiliateKey = affiliateKey

        scope.launch {
            try {
                val instance = TapToPayApiProvider.provide(context.applicationContext)
                val result = instance.init(object : AuthTokenProvider {
                    override fun getAccessToken() = apiToken
                })
                result
                    .onSuccess {
                        sdk = instance
                        isInitialized = true
                        callback.onResult(true, null)
                        eventCallback?.onEvent("sdkReady", emptyMap())
                    }
                    .onFailure { error ->
                        Log.e(TAG, "init failure", error)
                        isInitialized = false
                        callback.onResult(false, error.message ?: "Error al inicializar Tap to Pay")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "init exception", e)
                callback.onResult(false, "Error al crear SDK Tap to Pay: ${e.message}")
            }
        }
    }

    override fun startPayment(
        amount: Long,
        currency: String,
        processCardAs: String,
        installments: Int,
        description: String,
        foreignTransactionId: String
    ) {
        val currentSdk = sdk
        if (!isInitialized || currentSdk == null) {
            eventCallback?.onPaymentError(
                "SDK Tap to Pay no inicializado. Llama a initTapToPay primero.",
                "NOT_INITIALIZED"
            )
            return
        }

        val txId = foreignTransactionId.ifEmpty { java.util.UUID.randomUUID().toString() }

        eventCallback?.onEvent("paymentStarting", mapOf(
            "amount" to amount,
            "currency" to currency,
            "processCardAs" to processCardAs,
            "installments" to installments,
            "description" to description,
            "foreignTransactionId" to txId
        ))

        val checkoutData = CheckoutData(
            totalAmount = amount,
            tipsAmount = null,
            vatAmount = null,
            clientUniqueTransactionId = txId,
            customItems = emptyList(),
            priceItems = emptyList(),
            products = emptyList(),
            processCardAs = when (processCardAs.uppercase()) {
                "DEBIT" -> ProcessCardAs.Debit
                else -> ProcessCardAs.Credit(if (installments > 1) installments else 1)
            },
            affiliateData = AffiliateModel(
                key = affiliateKey,
                foreignTransactionId = txId,
                tags = emptyMap()
            )
        )

        scope.launch {
            try {
                currentSdk.startPayment(checkoutData, false)
                    .catch { e ->
                        Log.e(TAG, "payment flow catch", e)
                        val msg = buildString {
                            append(e.javaClass.simpleName)
                            if (!e.message.isNullOrBlank()) append(": ${e.message}")
                            e.cause?.let { append(" | causa: ${it.javaClass.simpleName}: ${it.message}") }
                        }
                        val debug = throwableDebugMap(e)
                        eventCallback?.onEvent(
                            "paymentFlowError",
                            mapOf(
                                "message" to msg,
                                "exceptionClass" to (debug["exceptionClass"] ?: ""),
                                "causeClass" to (debug["causeClass"] ?: ""),
                                "causeMessage" to (debug["causeMessage"] ?: ""),
                                "stackTop" to (debug["stackTop"] ?: ""),
                                "sdkErrorCode" to (debug["sdkErrorCode"] ?: ""),
                                "currency" to currency,
                                "processCardAs" to processCardAs,
                                "installments" to installments,
                                "foreignTransactionId" to txId
                            )
                        )
                        eventCallback?.onPaymentError(msg, "PAYMENT_FAILED")
                    }
                    .collect { event ->
                        when (event) {
                            is PaymentEvent.CardRequested -> eventCallback?.onEvent(
                                "cardRequested",
                                mapOf("message" to "Acerca la tarjeta al dispositivo")
                            )
                            is PaymentEvent.CardPresented -> eventCallback?.onEvent(
                                "cardPresented",
                                mapOf("message" to "Tarjeta detectada, procesando...")
                            )
                            is PaymentEvent.CVMPresented -> eventCallback?.onEvent(
                                "pinRequired",
                                mapOf("message" to "Ingrese su PIN en la pantalla")
                            )
                            is PaymentEvent.TransactionDone -> {
                                val output = event.paymentOutput
                                eventCallback?.onPaymentSuccess(mapOf(
                                    "transaction_code" to output.txCode,
                                    "server_transaction_id" to (output.serverTransactionId ?: ""),
                                    "status" to "SUCCESSFUL",
                                    "card_type" to (output.cardType ?: ""),
                                    "last_4_digits" to (output.lastFour ?: ""),
                                    "card_scheme" to (output.cardScheme ?: ""),
                                    "merchant_code" to (output.merchantCode ?: ""),
                                    "payment_type" to "TAP_TO_PAY",
                                    "entry_mode" to "NFC",
                                    "installments" to installments
                                ))
                            }
                            is PaymentEvent.TransactionFailed -> {
                                val ex = event.tapToPayException
                                Log.e(TAG, "transaction failed", ex)
                                val msg = buildString {
                                    append(ex?.javaClass?.simpleName ?: "TransactionFailed")
                                    val m = ex?.message ?: ex?.cause?.message
                                    if (!m.isNullOrBlank()) append(": $m")
                                    ex?.cause?.let { append(" | causa: ${it.javaClass.simpleName}: ${it.message}") }
                                }
                                val debug = throwableDebugMap(ex)
                                eventCallback?.onEvent(
                                    "transactionFailed",
                                    mapOf(
                                        "message" to msg,
                                        "exceptionClass" to (debug["exceptionClass"] ?: ""),
                                        "causeClass" to (debug["causeClass"] ?: ""),
                                        "causeMessage" to (debug["causeMessage"] ?: ""),
                                        "stackTop" to (debug["stackTop"] ?: ""),
                                        "sdkErrorCode" to (debug["sdkErrorCode"] ?: ""),
                                        "currency" to currency,
                                        "processCardAs" to processCardAs,
                                        "installments" to installments,
                                        "foreignTransactionId" to txId
                                    )
                                )
                                eventCallback?.onPaymentError(msg, "PAYMENT_FAILED")
                            }
                            is PaymentEvent.TransactionCanceled -> eventCallback?.onPaymentError(
                                "Pago cancelado por el usuario",
                                "PAYMENT_CANCELLED"
                            )
                            is PaymentEvent.TransactionResultUnknown -> eventCallback?.onPaymentError(
                                "Resultado de pago incierto. Verifique con el servidor.",
                                "PAYMENT_UNCERTAIN"
                            )
                            else -> { /* PaymentFlowClosedSuccessfully y otros — ignorar */ }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "startPayment exception", e)
                val msg = buildString {
                    append(e.javaClass.simpleName)
                    if (!e.message.isNullOrBlank()) append(": ${e.message}")
                    e.cause?.let { append(" | causa: ${it.javaClass.simpleName}: ${it.message}") }
                }
                eventCallback?.onPaymentError(msg, "PAYMENT_ERROR")
            }
        }
    }

    override fun teardown() {
        scope.launch {
            try { sdk?.tearDown() } catch (_: Exception) {}
        }
        sdk = null
        isInitialized = false
    }

    override fun isSdkReady(): Boolean = isInitialized && sdk != null
}
