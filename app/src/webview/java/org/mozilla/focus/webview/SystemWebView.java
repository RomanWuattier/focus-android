/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.webview;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;

import org.mozilla.focus.BuildConfig;
import org.mozilla.focus.session.Session;
import org.mozilla.focus.telemetry.TelemetryWrapper;
import org.mozilla.focus.utils.AppConstants;
import org.mozilla.focus.utils.FileUtils;
import org.mozilla.focus.utils.UrlUtils;
import org.mozilla.focus.utils.ViewUtils;
import org.mozilla.focus.web.Download;
import org.mozilla.focus.web.IWebView;
import org.mozilla.focus.web.WebViewProvider;

import java.util.HashMap;
import java.util.Map;

import mozilla.components.support.utils.ThreadUtils;

public class SystemWebView extends NestedWebView implements IWebView, SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "WebkitView";

    private Callback callback;
    private final FocusWebViewClient client;
    private final LinkHandler linkHandler;

    public SystemWebView(Context context, AttributeSet attrs) {
        super(context, attrs);

        client = new FocusWebViewClient(getContext().getApplicationContext());

        setWebViewClient(client);
        setWebChromeClient(createWebChromeClient());
        setDownloadListener(createDownloadListener());

        if (BuildConfig.DEBUG) {
            setWebContentsDebuggingEnabled(true);
        }

        setLongClickable(true);

        linkHandler = new LinkHandler(this);
        setOnLongClickListener(linkHandler);
    }

    @VisibleForTesting
    public Callback getCallback() {
        return callback;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        PreferenceManager.getDefaultSharedPreferences(getContext()).registerOnSharedPreferenceChangeListener(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            TelemetryAutofillCallback.INSTANCE.register(getContext());
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        PreferenceManager.getDefaultSharedPreferences(getContext()).unregisterOnSharedPreferenceChangeListener(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            TelemetryAutofillCallback.INSTANCE.unregister(getContext());
        }
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        final InputConnection connection = super.onCreateInputConnection(outAttrs);
        outAttrs.imeOptions |= ViewUtils.IME_FLAG_NO_PERSONALIZED_LEARNING;
        return connection;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        WebViewProvider.applyAppSettings(getContext(), getSettings(), this);
    }

    @Override
    public void onPause() {
        super.onPause();
        pauseTimers();
    }

    @Override
    public void onResume() {
        super.onResume();
        resumeTimers();
    }

    @Override
    public void restoreWebViewState(Session session) {
        final Bundle stateData = session.getWebViewState();

        final WebBackForwardList backForwardList = stateData != null
                ? super.restoreState(stateData)
                : null;

        final String desiredURL = session.getUrl().getValue();

        client.restoreState(stateData);
        client.notifyCurrentURL(desiredURL);

        // Pages are only added to the back/forward list when loading finishes. If a new page is
        // loading when the Activity is paused/killed, then that page won't be in the list,
        // and needs to be restored separately to the history list. We detect this by checking
        // whether the last fully loaded page (getCurrentItem()) matches the last page that the
        // WebView was actively loading (which was retrieved during onSaveInstanceState():
        // WebView.getUrl() always returns the currently loading or loaded page).
        // If the app is paused/killed before the initial page finished loading, then the entire
        // list will be null - so we need to additionally check whether the list even exists.

        if (backForwardList != null &&
                backForwardList.getCurrentItem().getUrl().equals(desiredURL)) {
            // restoreState doesn't actually load the current page, it just restores navigation history,
            // so we also need to explicitly reload in this case:
            reload();
        } else {
            loadUrl(desiredURL);
        }
    }

    @Override
    public void saveWebViewState(@NonNull Session session) {
        // We store the actual state into another bundle that we will keep in memory as long as this
        // browsing session is active. The data that WebView stores in this bundle is too large for
        // Android to save and restore as part of the state bundle.
        final Bundle stateData = new Bundle();

        super.saveState(stateData);
        client.saveState(this, stateData);

        session.saveWebViewState(stateData);
    }

    @Override
    public void setBlockingEnabled(boolean enabled) {
        client.setBlockingEnabled(enabled);
        if (enabled) {
            WebViewProvider.applyAppSettings(getContext(), getSettings(), this);
        } else {
            WebViewProvider.disableBlocking(getSettings(), this);
        }

        if (callback != null) {
            callback.onBlockingStateChanged(enabled);
        }
    }

    @Override
    public void setCallback(Callback callback) {
        this.callback = callback;
        client.setCallback(callback);
        linkHandler.setCallback(callback);
    }

    public void loadUrl(String url) {
        // We need to check external URL handling here - shouldOverrideUrlLoading() is only
        // called by webview when clicking on a link, and not when opening a new page for the
        // first time using loadUrl().
        if (!client.shouldOverrideUrlLoading(this, url)) {
            final Map<String, String> additionalHeaders = new HashMap<>();
            additionalHeaders.put("X-Requested-With", "");

            super.loadUrl(url, additionalHeaders);
        }

        client.notifyCurrentURL(url);
    }

    @Override
    public void grabScreenshot(@NonNull ValueCallback<String> callback) {
        final String script = "(function() {" +
            "let dpr = window.devicePixelRatio;" +
            "let canvas = window.document.createElement(\"canvas\");" +
            "let ctx = canvas.getContext(\"2d\");" +
            // Grab the visible viewport coordinates
            "let x = window.document.documentElement.scrollLeft;" +
            "let y = window.document.documentElement.scrollTop;" +
            "let w = window.innerWidth;" +
            "let h = window.innerHeight;" +
            // Scale according to devicePixelRatio and coordinates
            "canvas.width = dpr * w;" +
            "canvas.height = dpr * h;" +
            "ctx.scale(dpr, dpr);" +
            "ctx.drawImage(canvas, x, y, w, h);" +
            "let screenshot = canvas.toDataURL();" +
            // TODO: Delete this png encoded URI
            //"let screenshot = 'data:image/png;base64,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';" +
            // Return screenshot URI
            "return screenshot;" +
            "})();";

        evaluateJavascript(script, callback);
    }

    @Override
    public void postScreenshot(@NonNull String screenshot, @NonNull String originUrl,
        @Nullable ValueCallback<String> callback) {
        final String script = "(function() {" +
            "window.postMessage(" + screenshot + ", '" + originUrl + "');" +
            "})();";

        evaluateJavascript(script, callback);
    }

    @Override
    public void exitFullscreen() {}

    @Override
    public void loadData(String baseURL, String data, String mimeType, String encoding, String historyURL) {
        loadDataWithBaseURL(baseURL, data, mimeType, encoding, historyURL);
    }

    @Override
    public void destroy() {
        super.destroy();

        // WebView might save data to disk once it gets destroyed. In this case our cleanup call
        // might not have been able to see this data. Let's do it again.
        deleteContentFromKnownLocations(getContext());
    }

    @Override
    public void cleanup() {
        clearFormData();
        clearHistory();
        clearMatches();
        clearSslPreferences();
        clearCache(true);

        // We don't care about the callback - we just want to make sure cookies are gone
        CookieManager.getInstance().removeAllCookies(null);

        WebStorage.getInstance().deleteAllData();

        final WebViewDatabase webViewDatabase = WebViewDatabase.getInstance(getContext());
        // It isn't entirely clear how this differs from WebView.clearFormData()
        webViewDatabase.clearFormData();
        webViewDatabase.clearHttpAuthUsernamePassword();

        deleteContentFromKnownLocations(getContext());
    }

    @Override
    public void autofill(SparseArray<AutofillValue> values) {
        super.autofill(values);

        TelemetryWrapper.autofillPerformedEvent();
    }

    public static void deleteContentFromKnownLocations(final Context context) {
        ThreadUtils.INSTANCE.postToBackgroundThread(new Runnable() {
            @Override
            public void run() {
                // We call all methods on WebView to delete data. But some traces still remain
                // on disk. This will wipe the whole webview directory.
                FileUtils.deleteWebViewDirectory(context);

                // WebView stores some files in the cache directory. We do not use it ourselves
                // so let's truncate it.
                FileUtils.truncateCacheDirectory(context);
            }
        });
    }

    private WebChromeClient createWebChromeClient() {
        return new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (callback != null) {
                    // This is the earliest point where we might be able to confirm a redirected
                    // URL: we don't necessarily get a shouldInterceptRequest() after a redirect,
                    // so we can only check the updated url in onProgressChanges(), or in onPageFinished()
                    // (which is even later).
                    final String viewURL = view.getUrl();
                    if (!UrlUtils.isInternalErrorURL(viewURL) && viewURL != null) {
                        callback.onURLChanged(viewURL);
                    }
                    callback.onProgress(newProgress);
                }
            }

            @Override
            public void onShowCustomView(View view, final CustomViewCallback webviewCallback) {
                final FullscreenCallback fullscreenCallback = new FullscreenCallback() {
                    @Override
                    public void fullScreenExited() {
                        webviewCallback.onCustomViewHidden();
                    }
                };

                callback.onEnterFullScreen(fullscreenCallback, view);
            }

            @Override
            public void onHideCustomView() {
                callback.onExitFullScreen();
            }
        };
    }

    private DownloadListener createDownloadListener() {
        return new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                if (!AppConstants.supportsDownloadingFiles()) {
                    return;
                }

                final String scheme = Uri.parse(url).getScheme();
                if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                    // We are ignoring everything that is not http or https. This is a limitation of
                    // Android's download manager. There's no reason to show a download dialog for
                    // something we can't download anyways.
                    Log.w(TAG, "Ignoring download from non http(s) URL: " + url);
                    return;
                }

                if (callback != null) {
                    final Download download = new Download(url, userAgent, contentDisposition, mimetype, contentLength, Environment.DIRECTORY_DOWNLOADS);
                    callback.onDownloadStart(download);
                }
            }
        };
    }
}
