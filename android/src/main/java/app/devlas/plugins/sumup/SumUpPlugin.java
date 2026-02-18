package app.devlas.plugins.sumup;

import android.content.Intent;
import android.os.Bundle;

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

    /* ── Request codes ──────────────────────────────────── */
    private static final int RC_LOGIN    = 10_001;
    private static final int RC_CHECKOUT = 10_002;
    private static final int RC_READER   = 10_003;

    /* ── Saved call IDs (para recuperar en onActivityResult) ── */
    private String loginCallbackId;
    private String checkoutCallbackId;
    private String readerCallbackId;

    /* ── Helpers ────────────────────────────────────────── */

    private JSObject ok(String message) {
        JSObject r = new JSObject();
        r.put("code", 1);
        r.put("message", message);
        return r;
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
