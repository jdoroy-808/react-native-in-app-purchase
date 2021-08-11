package net.class101.iap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RNInAppPurchaseModule extends ReactContextBaseJavaModule implements PurchasesUpdatedListener {

    private final ReactApplicationContext reactContext;

    private final Map<String, SkuDetails> skuDetailsMap;

    private BillingClient client;

    public RNInAppPurchaseModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        this.skuDetailsMap = new HashMap<>();
    }

    @Override
    public String getName() {
        return "RNInAppPurchase";
    }

    /**
     * Initialize billing client.
     *
     * @param promise Promise that receives initialization result.
     */
    @ReactMethod
    public void configure(final Promise promise) {
        if (client != null && client.isReady()) {
            promise.resolve(true);
            return;
        }

        client = BillingClient.newBuilder(reactContext)
            .enablePendingPurchases()
            .setListener(this)
            .build();

        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult result) {
                int code = result.getResponseCode();

                if (code == BillingClient.BillingResponseCode.OK) {
                    promise.resolve(true);
                } else {
                    promise.reject("configure", "Billing service setup failed with code " + code);
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                promise.reject("configure", "Billing service disconnected");
            }
        });
    }

    /**
     * Retrieves a list of in-app and subscription items that match the IDs contained in productIds.
     * For iOS compatibility, this method fetches in-app and subscription items at once.
     *
     * @param productIds The IDs of the items to retrieve.
     */
    @ReactMethod
    public void fetchProducts(ReadableArray productIds) {
        tryConnect(() -> {
            final List<String> skuList = new ArrayList<>();

            for (int i = 0; i < productIds.size(); i++) {
                skuList.add(productIds.getString(i));
            }

            final SkuDetailsParams inAppParams = SkuDetailsParams.newBuilder()
                .setSkusList(skuList)
                .setType(BillingClient.SkuType.INAPP).build();

            final SkuDetailsParams subscribeParams = SkuDetailsParams.newBuilder()
                .setSkusList(skuList)
                .setType(BillingClient.SkuType.SUBS).build();

            final WritableArray items = new WritableNativeArray();

            // Query in-app items
            client.querySkuDetailsAsync(inAppParams, (inAppResult, inAppDetailsList) -> {
                if (inAppResult.getResponseCode() != BillingClient.BillingResponseCode.OK || inAppDetailsList == null) {
                    sendBillingError("iap:onFetchProductsFailure", inAppResult);
                    return;
                }

                for (SkuDetails skuDetails : inAppDetailsList) {
                    WritableMap item = Arguments.createMap();
                    item.putString("productId", skuDetails.getSku());
                    item.putString("price", skuDetails.getPrice());
                    item.putString("currency", skuDetails.getPriceCurrencyCode());
                    item.putString("title", skuDetails.getTitle());
                    item.putString("description", skuDetails.getDescription());
                    item.putString("iconUrl", skuDetails.getIconUrl());
                    items.pushMap(item);
                    skuDetailsMap.put(skuDetails.getSku(), skuDetails);
                }

                // Query subscription items
                client.querySkuDetailsAsync(subscribeParams, (subscribeResult, subscribeDetailsList) -> {
                    if (subscribeResult.getResponseCode() != BillingClient.BillingResponseCode.OK || subscribeDetailsList == null) {
                        sendBillingError("iap:onFetchProductsFailure", subscribeResult);
                        return;
                    }

                    for (SkuDetails skuDetails : subscribeDetailsList) {
                        WritableMap item = Arguments.createMap();
                        item.putString("productId", skuDetails.getSku());
                        item.putString("price", skuDetails.getPrice());
                        item.putString("currency", skuDetails.getPriceCurrencyCode());
                        item.putString("title", skuDetails.getTitle());
                        item.putString("description", skuDetails.getDescription());
                        item.putString("iconUrl", skuDetails.getIconUrl());
                        items.pushMap(item);
                        skuDetailsMap.put(skuDetails.getSku(), skuDetails);
                    }

                    sendEvent("iap:onFetchProductsSuccess", items);
                });
            });
        });
    }

    /**
     * Purchase in-app or subscription item.
     *
     * @param productId Unique ID of item to purchase.
     * @param originalPurchaseToken When upgrading or downgrading a subscription, purchase token
     *                              of the subscription that user originally used.
     */
    @ReactMethod
    public void purchase(String productId, @Nullable String originalPurchaseToken) {
        tryConnect(() -> {
            if (getCurrentActivity() == null) {
              return;
            }

            BillingFlowParams.Builder builder = BillingFlowParams.newBuilder();

            if (originalPurchaseToken != null) {
                builder.setSubscriptionUpdateParams(
                    BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                        .setOldSkuPurchaseToken(originalPurchaseToken)
                        .build()
                );
            }

            BillingFlowParams params = builder.setSkuDetails(skuDetailsMap.get(productId)).build();

            client.launchBillingFlow(getCurrentActivity(), params);
        });
    }

    /**
     * For consumable items, call the {@link BillingClient#consumeAsync} method, otherwise call the
     * {@link BillingClient#acknowledgePurchase} method and finish the purchase flow.
     *
     * @param purchase Purchase object received after payment is made.
     * @param isConsumable Whether it is consumable or not.
     */
    @ReactMethod
    public void finalize(ReadableMap purchase, boolean isConsumable, final Promise promise) {
        tryConnect(() -> {
            String token = purchase.getString("purchaseToken");

            if (token == null) {
                return;
            }

            if (isConsumable) {
                ConsumeParams params = ConsumeParams.newBuilder()
                    .setPurchaseToken(token)
                    .build();

                client.consumeAsync(params, (result, purchaseToken) -> {
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        promise.reject("finalize", result.getDebugMessage());
                        return;
                    }

                    WritableMap event = Arguments.createMap();
                    event.putString("message", result.getDebugMessage());

                    promise.resolve(event);
                });
                return;
            }

            AcknowledgePurchaseParams acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(token)
                .build();

            client.acknowledgePurchase(acknowledgePurchaseParams, (result) -> {
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    promise.reject("finalize", result.getDebugMessage());
                    return;
                }

                WritableMap event = Arguments.createMap();
                event.putString("message", result.getDebugMessage());

                promise.resolve(event);
            });
        });
    }

    /**
     * Retrieves all in-app and subscription items that were purchased but not finalized. You should
     * validate these payments on your backend server and complete the purchase flow by calling
     * {@link #finalize} method.
     */
    @ReactMethod
    public void flush(final Promise promise) {
        tryConnect(() -> client.queryPurchasesAsync(BillingClient.SkuType.INAPP, (inAppResult, inAppPurchases) -> {
            if (inAppResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                promise.reject("flush", inAppResult.getDebugMessage());
                return;
            }

            client.queryPurchasesAsync(BillingClient.SkuType.SUBS, (subscriptionResult, subscriptionPurchases) -> {
                if (subscriptionResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                  promise.reject("flush", subscriptionResult.getDebugMessage());
                  return;
                }

                List<Purchase> purchaseList = new ArrayList<>();

                purchaseList.addAll(inAppPurchases);
                purchaseList.addAll(subscriptionPurchases);

                WritableArray items = new WritableNativeArray();

                for (Purchase purchase : purchaseList) {
                    if (purchase.isAcknowledged()) {
                        continue;
                    }

                    WritableMap item = this.buildPurchaseJSON(purchase);
                    items.pushMap(item);
                }

                promise.resolve(items);
            });
        }));
    }

    private void sendEvent(String eventName, Object params) {
        getReactApplicationContext()
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(eventName, params);
    }

    private void sendBillingError(String errorName, BillingResult result) {
        WritableMap event = Arguments.createMap();
        event.putInt("code", result.getResponseCode());
        event.putString("message", result.getDebugMessage());

        sendEvent(errorName, event);
    }

    private void tryConnect(final Runnable runnable) {
        if (client.isReady()) {
            runnable.run();
            return;
        }

        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    runnable.run();
                } else {
                  sendBillingError("iap:onConnectionFailure", result);
                }
            }

            @Override
            public void onBillingServiceDisconnected() {}
        });
    }

    @Override
    public void onPurchasesUpdated(BillingResult result, @Nullable List<Purchase> purchases) {
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            sendBillingError("iap:onPurchaseFailure", result);
            return;
        }

        if (purchases != null) {
            for (Purchase purchase : purchases) {
                ReadableMap item = this.buildPurchaseJSON(purchase);
                sendEvent("iap:onPurchaseSuccess", item);
            }
        }
    }

    private WritableMap buildPurchaseJSON(Purchase purchase) {
        WritableMap item = Arguments.createMap();
        WritableArray productIds = Arguments.createArray();
        for (String sku : purchase.getSkus()) {
            productIds.pushString(sku);
        }
        item.putArray("productIds", productIds);
        item.putString("transactionId", purchase.getOrderId());
        item.putString("transactionDate", String.valueOf(purchase.getPurchaseTime()));
        item.putString("receipt", purchase.getOriginalJson());
        item.putString("purchaseToken", purchase.getPurchaseToken());

        return item;
    }
}
