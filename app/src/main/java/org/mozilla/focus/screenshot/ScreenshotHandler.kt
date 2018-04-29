package org.mozilla.focus.screenshot

import android.util.Log
import org.mozilla.focus.web.IWebView

class ScreenshotHandler {

    companion object {
        private var instance: ScreenshotHandler? = null

        @JvmStatic
        fun provideInstance(): ScreenshotHandler {
            if (instance == null) {
                instance = ScreenshotHandler()
            }
            return instance as ScreenshotHandler
        }
    }

    /** Base64 encoded screenshot URI with a public getter and private setter */
    var screenshot: String? = null
        private set

    /**
     * Grab a screenshot of the current active [webView], then notify the caller object with the provided
     * [ScreenshotCallback].
     *
     * @param webView The current active [IWebView]
     * @param callback Optional [ScreenshotCallback] to get notified when the screenshot has been grabbed
     */
    fun grabScreenshot(webView: IWebView, callback: ScreenshotCallback?) {
        webView.grabScreenshot {
            // TODO: Delete Log.d
            Log.d("SCREENSHOT_URI", it)

            screenshot = it
            callback?.screenshotGrabbed()
        }
    }

    /**
     * Post the [screenshot] to the given [originUrl].
     *
     * @param webView The current [IWebView]
     * @param screenshot The Base64 encoded screenshot
     * @param originUrl The origin URL
     * @param callback Optional [ScreenshotCallback] to get notified when the message has been posted
     */
    fun postScreenshot(webView: IWebView, screenshot: String, originUrl: String, callback: ScreenshotCallback?) {
        webView.postScreenshot(screenshot, originUrl, {
            callback?.screenshotPosted()
        })
    }

    /**
     * Callback abstract class
     */
    abstract class ScreenshotCallback {

        /**
         * Notify downstream class that the screenshot has been grabbed
         */
        open fun screenshotGrabbed() {}

        /**
         * Notify downstream class that the screenshot has been posted
         */
        open fun screenshotPosted() {}
    }
}
