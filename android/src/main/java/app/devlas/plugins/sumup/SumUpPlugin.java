package app.devlas.plugins.sumup;

import android.content.pm.ApplicationInfo;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.sumup.merchant.reader.api.SumUpAPI;
import com.sumup.merchant.reader.api.SumUpLogin;
import com.sumup.merchant.reader.api.SumUpPayment;
import com.sumup.merchant.reader.api.SumUpState;
import com.sumup.merchant.reader.models.TransactionInfo;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Plugin Capacitor para el SumUp Android Reader SDK v5.
 *
 * El SDK v5 lanza actividades internamente (los métodos retornan void),
 * así que guardamos la PluginCall y recuperamos el resultado en
 * {@link #handleOnActivityResult}.
 */
@CapacitorPlugin(name = "SumUp")
public class SumUpPlugin extends Plugin {

    private static final String TAG = "SumUpPlugin";

    /* ── Request codes ──────────────────────────────────── */
    private static final int RC_LOGIN    = 10_001;
    private static final int RC_CHECKOUT = 10_002;
    private static final int RC_READER   = 10_003;

    /* ── Saved call IDs (para recuperar en onActivityResult) ── */
    private String loginCallbackId;
    private String checkoutCallbackId;
    private String readerCallbackId;

    /* ── Tap to Pay ─────────────────────────────────────── */
    private TapToPayBridge tapToPayManager;
    private String tapPaymentCallbackId;

    /** Verifica si el SDK Tap to Pay está disponible en el classpath */
    private boolean isTapToPayAvailable() {
        try {
            Class.forName("app.devlas.plugins.sumup.TapToPayManager");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Tap to Pay no funciona en builds debuggable (android:debug). */
    private boolean isDebuggableBuild() {
        if (getContext() == null) return false;
        return (getContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /* ── Helpers ────────────────────────────────────────── */

    private JSObject ok(String message) {
        JSObject r = new JSObject();
        r.put("code", 1);
        r.put("message", message);
        return r;
    }

    /**
     * Tap to Pay usa unidad menor para el monto.
     * CLP/COP/HUF se muestran sin decimales, pero igual requieren exponente 2.
     */
    private long normalizeAmountToMinorUnit(long majorAmount, String currencyCode) {
        if (currencyCode == null) return majorAmount;
        String c = currencyCode.toUpperCase(Locale.ROOT);
        if ("CLP".equals(c) || "COP".equals(c) || "HUF".equals(c)) {
            return majorAmount * 100L;
        }
        return majorAmount;
    }

    private void runOnMainThread(Runnable action) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(action);
            return;
        }
        if (getBridge() != null && getBridge().getActivity() != null) {
            getBridge().getActivity().runOnUiThread(action);
            return;
        }
        action.run();
    }

    /* ── setup ──────────────────────────────────────────── */

    @PluginMethod
    public void setup(PluginCall call) {
        runOnMainThread(() -> {
            try {
                SumUpState.init(getContext());
                call.resolve(ok("SDK inicializado"));
            } catch (Exception e) {
                call.reject("Error al inicializar SDK: " + e.getMessage(), "SETUP_ERROR");
            }
        });
    }

    /* ── login ──────────────────────────────────────────── */

    @PluginMethod
    public void login(PluginCall call) {
        String affiliateKey = call.getString("affiliateKey", "");
        String accessToken  = call.getString("accessToken", "");

        if (affiliateKey == null || affiliateKey.isEmpty()) {
            call.reject("affiliateKey es requerido", "NO_AFFILIATE_KEY");
            return;
        }

        SumUpLogin.Builder builder = SumUpLogin.builder(affiliateKey);
        if (accessToken != null && !accessToken.isEmpty()) {
            builder.accessToken(accessToken);
        }

        // Guardar la call antes de lanzar la activity
        bridge.saveCall(call);
        loginCallbackId = call.getCallbackId();

        // SDK v5: openLoginActivity retorna void, lanza la activity internamente
        runOnMainThread(() -> SumUpAPI.openLoginActivity(getActivity(), builder.build(), RC_LOGIN));
    }

    /* ── logout ─────────────────────────────────────────── */

    @PluginMethod
    public void logout(PluginCall call) {
        runOnMainThread(() -> {
            SumUpAPI.logout();
            call.resolve(ok("Sesión cerrada"));
        });
    }

    /* ── isLoggedIn ─────────────────────────────────────── */

    @PluginMethod
    public void isLoggedIn(PluginCall call) {
        boolean loggedIn = SumUpAPI.isLoggedIn();
        JSObject r = new JSObject();
        r.put("code", 1);
        r.put("isLoggedIn", loggedIn);
        call.resolve(r);
    }

    /* ── openCardReaderPage ─────────────────────────────── */

    @PluginMethod
    public void openCardReaderPage(PluginCall call) {
        bridge.saveCall(call);
        readerCallbackId = call.getCallbackId();

        // SDK v5: retorna void
        runOnMainThread(() -> SumUpAPI.openCardReaderPage(getActivity(), RC_READER));
    }

    /* ── prepareForCheckout ─────────────────────────────── */

    @PluginMethod
    public void prepareForCheckout(PluginCall call) {
        runOnMainThread(() -> {
            try {
                SumUpAPI.prepareForCheckout();
                call.resolve(ok("Lector preparado"));
            } catch (Exception e) {
                call.reject("Error al preparar lector: " + e.getMessage(), "PREPARE_ERROR");
            }
        });
    }

    /* ── checkout ───────────────────────────────────────── */

    @PluginMethod
    public void checkout(PluginCall call) {
        Double amount = call.getDouble("amount");
        if (amount == null || amount < 1.0) {
            call.reject("amount es requerido y mínimo 1.00", "INVALID_AMOUNT");
            return;
        }

        String title       = call.getString("title", "");
        String currency    = call.getString("currencyCode", "");
        Boolean tipReader  = call.getBoolean("tipOnCardReader", false);
        Double tip         = call.getDouble("tip");
        Boolean skipOk     = call.getBoolean("skipSuccessScreen", false);
        Boolean skipFail   = call.getBoolean("skipFailedScreen", false);
        String foreignTxId = call.getString("foreignTransactionId", "");

        SumUpPayment.Builder builder = SumUpPayment.builder()
                .total(new BigDecimal(String.valueOf(amount)))
                .title(title != null ? title : "");

        // Moneda
        if (currency != null && !currency.isEmpty()) {
            try {
                builder.currency(SumUpPayment.Currency.valueOf(currency));
            } catch (IllegalArgumentException e) {
                call.reject("Código de moneda inválido: " + currency, "INVALID_CURRENCY");
                return;
            }
        }

        // Propina en lector
        if (Boolean.TRUE.equals(tipReader) && SumUpAPI.isTipOnCardReaderAvailable()) {
            builder.tipOnCardReader();
        } else if (tip != null && tip > 0) {
            builder.tip(new BigDecimal(String.valueOf(tip)));
        }

        // Pantallas opcionales
        if (Boolean.TRUE.equals(skipOk))   builder.skipSuccessScreen();
        if (Boolean.TRUE.equals(skipFail)) builder.skipFailedScreen();

        // Foreign TX ID
        if (foreignTxId != null && !foreignTxId.isEmpty()) {
            builder.foreignTransactionId(foreignTxId);
        } else {
            builder.foreignTransactionId(UUID.randomUUID().toString());
        }

        SumUpPayment payment = builder.build();

        // Guardar la call antes de lanzar la activity
        bridge.saveCall(call);
        checkoutCallbackId = call.getCallbackId();

        // SDK v5: checkout retorna void, lanza la activity internamente
        runOnMainThread(() -> SumUpAPI.checkout(getActivity(), payment, RC_CHECKOUT));
    }

    /* ── closeConnection ────────────────────────────────── */

    @PluginMethod
    public void closeConnection(PluginCall call) {
        runOnMainThread(() -> {
            try {
                SumUpAPI.logout(); // El SDK v5 no expone closeConnection; logout desconecta el lector
                call.resolve(ok("Conexión cerrada"));
            } catch (Exception e) {
                call.reject("Error al cerrar conexión: " + e.getMessage(), "CLOSE_ERROR");
            }
        });
    }

    /* ═══════════════════════════════════════════════════════ */
    /* ══ TAP TO PAY (NFC en dispositivo Android)          ══ */
    /* ═══════════════════════════════════════════════════════ */

    private static final String TAP_NOT_AVAILABLE =
        "Tap to Pay SDK no disponible. Configura las credenciales Maven en .env y recompila.";

    /**
     * Inicializa el SDK Tap to Pay.
     * Requiere: affiliateKey, apiToken (bearer token del backend).
     */
    @PluginMethod
    public void initTapToPay(PluginCall call) {
        if (!isTapToPayAvailable()) {
            call.reject(TAP_NOT_AVAILABLE, "TAP_NOT_AVAILABLE");
            return;
        }
        String affiliateKey = call.getString("affiliateKey", "");
        String apiToken     = call.getString("apiToken", "");
        if (affiliateKey == null || affiliateKey.isEmpty()) {
            call.reject("affiliateKey es requerido", "NO_AFFILIATE_KEY");
            return;
        }
        if (apiToken == null || apiToken.isEmpty()) {
            call.reject("apiToken es requerido", "NO_API_TOKEN");
            return;
        }

        runOnMainThread(() -> {
            try {
                if (tapToPayManager == null) {
                    // Crear instancia vía reflexión (no referencia directa a TapToPayManager)
                    TapToPayBridge mgr = (TapToPayBridge) Class
                        .forName("app.devlas.plugins.sumup.TapToPayManager")
                        .getDeclaredConstructor()
                        .newInstance();

                    mgr.setEventCallback(new TapToPayBridge.EventCallback() {
                        @Override
                        public void onEvent(String eventName, Map<String, Object> data) {
                            JSObject eventData = new JSObject();
                            eventData.put("event", eventName);
                            for (Map.Entry<String, Object> entry : data.entrySet()) {
                                eventData.put(entry.getKey(), entry.getValue());
                            }
                            notifyListeners("tapToPayEvent", eventData);
                        }

                        @Override
                        public void onPaymentSuccess(Map<String, Object> data) {
                            PluginCall tapCall = (tapPaymentCallbackId != null) ? bridge.getSavedCall(tapPaymentCallbackId) : null;
                            tapPaymentCallbackId = null;
                            if (tapCall == null) return;
                            JSObject r = new JSObject();
                            for (Map.Entry<String, Object> entry : data.entrySet()) {
                                r.put(entry.getKey(), entry.getValue());
                            }
                            tapCall.resolve(r);
                        }

                        @Override
                        public void onPaymentError(String errorMessage, String errorCode) {
                            Log.e(TAG, "TapToPay onPaymentError code=" + errorCode + " msg=" + errorMessage);
                            JSObject eventData = new JSObject();
                            eventData.put("event", "paymentError");
                            eventData.put("message", errorMessage != null ? errorMessage : "");
                            eventData.put("code", errorCode != null ? errorCode : "TAP_PAY_ERROR");
                            notifyListeners("tapToPayEvent", eventData);

                            PluginCall tapCall = (tapPaymentCallbackId != null) ? bridge.getSavedCall(tapPaymentCallbackId) : null;
                            tapPaymentCallbackId = null;
                            if (tapCall == null) return;
                            tapCall.reject(errorMessage, errorCode != null ? errorCode : "TAP_PAY_ERROR");
                        }
                    });
                    tapToPayManager = mgr;
                }

                tapToPayManager.initialize(getContext(), affiliateKey, apiToken, new TapToPayBridge.InitCallback() {
                    @Override
                    public void onResult(boolean success, String error) {
                        if (success) {
                            call.resolve(ok("Tap to Pay SDK inicializado"));
                        } else {
                            call.reject(error != null ? error : "Error al inicializar Tap to Pay", "TAP_INIT_ERROR");
                        }
                    }
                });
            } catch (Exception e) {
                call.reject("Error al inicializar Tap to Pay: " + e.getMessage(), "TAP_INIT_ERROR");
            }
        });
    }

    /**
     * Inicia un pago Tap to Pay (NFC contactless).
     */
    @PluginMethod
    public void tapToPayCheckout(PluginCall call) {
        if (!isTapToPayAvailable() || tapToPayManager == null) {
            call.reject(TAP_NOT_AVAILABLE, "TAP_NOT_AVAILABLE");
            return;
        }
        if (!tapToPayManager.isSdkReady()) {
            call.reject("Tap to Pay no inicializado. Llama a initTapToPay primero.", "NOT_INITIALIZED");
            return;
        }

        Double amountDouble = call.getDouble("amount");
        if (amountDouble == null || amountDouble < 1.0) {
            call.reject("amount es requerido y mínimo 1.00", "INVALID_AMOUNT");
            return;
        }

        String currency     = call.getString("currency", "CLP");
        String processCard  = call.getString("processCardAs", "DEBIT");
        Integer installments = call.getInt("installments", 0);
        String desc          = call.getString("description", "");
        String foreignTxId   = call.getString("foreignTransactionId", "");

        bridge.saveCall(call);
        tapPaymentCallbackId = call.getCallbackId();
        Log.i(TAG, "tapToPayCheckout start amount=" + amountDouble + " currency=" + currency + " processCardAs=" + processCard + " installments=" + installments);

        long amountMajor = amountDouble.longValue();
        long amountMinor = normalizeAmountToMinorUnit(amountMajor, currency);

        runOnMainThread(() -> {
            Log.i(
                TAG,
                "tapToPayCheckout normalized amount major=" + amountMajor +
                " minor=" + amountMinor +
                " currency=" + (currency != null ? currency : "CLP")
            );
            tapToPayManager.startPayment(
                amountMinor,
                currency != null ? currency : "CLP",
                processCard != null ? processCard : "DEBIT",
                installments != null ? installments : 0,
                desc != null ? desc : "",
                foreignTxId != null ? foreignTxId : ""
            );
        });
    }

    /**
     * Verifica si el SDK Tap to Pay está listo.
     */
    @PluginMethod
    public void isTapToPayReady(PluginCall call) {
        JSObject r = new JSObject();
        boolean ready = tapToPayManager != null && tapToPayManager.isSdkReady();
        r.put("ready", ready);
        call.resolve(r);
    }

    /**
     * Libera recursos del SDK Tap to Pay.
     */
    @PluginMethod
    public void teardownTapToPay(PluginCall call) {
        if (tapToPayManager == null) {
            call.resolve(ok("Tap to Pay no estaba inicializado"));
            return;
        }
        runOnMainThread(() -> {
            try {
                tapToPayManager.teardown();
                tapToPayManager = null;
                call.resolve(ok("Tap to Pay SDK liberado"));
            } catch (Exception e) {
                call.reject("Error al liberar Tap to Pay: " + e.getMessage(), "TAP_TEARDOWN_ERROR");
            }
        });
    }

    /* ── handleOnActivityResult ─────────────────────────── */

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case RC_LOGIN: {
                PluginCall call = (loginCallbackId != null) ? bridge.getSavedCall(loginCallbackId) : null;
                loginCallbackId = null;
                if (call == null) return;
                handleLoginResult(data, call);
                break;
            }
            case RC_CHECKOUT: {
                PluginCall call = (checkoutCallbackId != null) ? bridge.getSavedCall(checkoutCallbackId) : null;
                checkoutCallbackId = null;
                if (call == null) return;
                handleCheckoutResult(data, call);
                break;
            }
            case RC_READER: {
                PluginCall call = (readerCallbackId != null) ? bridge.getSavedCall(readerCallbackId) : null;
                readerCallbackId = null;
                if (call == null) return;
                call.resolve(ok("Configuración de lector cerrada"));
                break;
            }
        }
    }

    /* ── Procesadores de resultado ──────────────────────── */

    private void handleLoginResult(Intent data, PluginCall call) {
        if (data == null) { call.reject("Login cancelado", "LOGIN_CANCELLED"); return; }
        Bundle extras = data.getExtras();
        int code   = extras != null ? extras.getInt(SumUpAPI.Response.RESULT_CODE, 0) : 0;
        String msg = extras != null ? extras.getString(SumUpAPI.Response.MESSAGE) : "";
        if (code == 1) call.resolve(ok(msg != null ? msg : "Login exitoso"));
        else           call.reject(msg != null ? msg : "Login fallido", String.valueOf(code));
    }

    private void handleCheckoutResult(Intent data, PluginCall call) {
        if (data == null) { call.reject("Pago sin datos", "CHECKOUT_NO_DATA"); return; }
        Bundle extras = data.getExtras();
        int code   = extras != null ? extras.getInt(SumUpAPI.Response.RESULT_CODE, 0) : 0;
        String msg = extras != null ? extras.getString(SumUpAPI.Response.MESSAGE) : "";
        if (code != 1) { call.reject(msg != null ? msg : "Pago fallido", String.valueOf(code)); return; }

        TransactionInfo tx = data.getParcelableExtra(SumUpAPI.Response.TX_INFO);
        boolean receiptSent = data.getBooleanExtra(SumUpAPI.Response.RECEIPT_SENT, false);

        JSObject r = new JSObject();
        if (tx != null) {
            r.put("transaction_code", safe(tx.getTransactionCode()));
            r.put("merchant_code",    safe(tx.getMerchantCode()));
            r.put("amount",           tx.getAmount() != null ? tx.getAmount().doubleValue() : 0);
            r.put("tip_amount",       tx.getTipAmount() != null ? tx.getTipAmount().doubleValue() : 0);
            r.put("vat_amount",       tx.getVatAmount() != null ? tx.getVatAmount().doubleValue() : 0);
            r.put("currency",         safe(tx.getCurrency()));
            r.put("status",           safe(tx.getStatus()));
            r.put("payment_type",     safe(tx.getPaymentType()));
            r.put("entry_mode",       safe(tx.getEntryMode()));
            r.put("installments",     tx.getInstallments());
            r.put("card_type",        tx.getCard() != null ? safe(tx.getCard().getType()) : "");
            r.put("last_4_digits",    tx.getCard() != null ? safe(tx.getCard().getLast4Digits()) : "");
        }
        r.put("receipt_sent", receiptSent);
        call.resolve(r);
    }

    /** Null-safe toString */
    private String safe(Object o) {
        return o != null ? o.toString() : "";
    }
}
