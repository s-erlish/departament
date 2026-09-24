package com.v2ray.ang.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils


/**
 * BaseActivity provides common helpers and UI wiring used across the app's activities.
 *
 * Responsibilities:
 * - Inflate a shared base layout that contains a toolbar and a content container.
 * - Provide convenient overloads of `setContentViewWithToolbar` to attach child layouts or
 *   view-binding roots into the base container and initialize the toolbar.
 * - Expose a global in-layout `ProgressBar` (cached) with `showLoading()` / `hideLoading()` helpers.
 * - Wrap base context according to user locale settings.
 */
abstract class BaseActivity : AppCompatActivity() {
    companion object {
        const val THEME_BLUE = "blue"
        const val THEME_MONO = "mono"
    }

    // Progress indicator that sits at the bottom of the toolbar
    private var progressBar: LinearProgressIndicator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the monochrome color overlay before the view hierarchy is created,
        // so the whole activity picks up the black & white palette when selected.
        if (MmkvManager.decodeSettingsString(AppConfig.PREF_COLOR_THEME, THEME_BLUE) == THEME_MONO) {
            theme.applyStyle(R.style.ThemeOverlay_Mono, true)
        }
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (!Utils.getDarkModeStatus(this)) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = true
            }
        }
    }

    /**
     * Handle action bar item selections.
     *
     * Currently this handles the home/up button by delegating to the activity's
     * onBackPressedDispatcher to provide consistent back navigation behavior.
     *
     * @param item the selected menu item
     * @return true if the event was handled, otherwise delegates to the superclass
     */
    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            // Handles the home button press by delegating to the onBackPressedDispatcher.
            // This ensures consistent back navigation behavior.
            onBackPressedDispatcher.onBackPressed()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /**
     * Wrap the base context with the user's locale settings.
     *
     * This ensures resources are loaded using the configured locale.
     *
     * @param newBase the original base context to wrap
     */
    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(MyContextWrapper.wrap(newBase ?: return, SettingsManager.getLocale()))
    }

    /**
     * Configure the toolbar instance using the default toolbar id if null is passed.
     *
     * This helper will set the toolbar as the action bar and configure the up button
     * visibility plus optional title.
     *
     * @param toolbar the toolbar instance to configure (may be null, in which case the view
     *                with id R.id.toolbar in the activity content will be used)
     * @param showHomeAsUp whether the home/up affordance should be shown (default true)
     * @param title optional title to set on the activity
     */
    protected fun setupToolbar(toolbar: Toolbar?, showHomeAsUp: Boolean = true, title: CharSequence? = null) {
        val tb = toolbar ?: findViewById<Toolbar?>(R.id.toolbar)
        tb?.let {
            setSupportActionBar(it)
            supportActionBar?.setDisplayHomeAsUpEnabled(showHomeAsUp)
            title?.let { t -> this.title = t }
        }
        progressBar = findViewById(R.id.progress_bar)
    }

    /**
     * Inflate the shared base layout, attach the child layout resource into the base
     * content container, cache the in-layout ProgressBar and configure the toolbar.
     *
     * Typical usage in subclasses:
     * setContentViewWithToolbar(R.layout.activity_settings, showHomeAsUp = true, title = "Settings")
     *
     * @param layoutResId child layout resource to inflate into the base content container
     * @param showHomeAsUp whether to show the up/home affordance on the toolbar (default true)
     * @param title optional activity title to set on the toolbar
     */
    protected fun setContentViewWithToolbar(layoutResId: Int, showHomeAsUp: Boolean = true, title: CharSequence? = null) {
        val base = LayoutInflater.from(this).inflate(R.layout.activity_base, null)
        val container = base.findViewById<FrameLayout>(R.id.content_container)
        LayoutInflater.from(this).inflate(layoutResId, container, true)
        progressBar = base.findViewById(R.id.progress_bar)
        super.setContentView(base)
        setupToolbar(base, showHomeAsUp, title)
    }

    /**
     * Inflate the shared base layout, attach the provided child view (commonly a view-binding root)
     * into the base content container, cache the in-layout ProgressBar and configure the toolbar.
     *
     * Typical usage with view binding:
     * setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = "...")
     *
     * @param childView the already-inflated child view to add to the base content container
     * @param showHomeAsUp whether to show the up/home affordance on the toolbar (default true)
     * @param title optional activity title to set on the toolbar
     */
    protected fun setContentViewWithToolbar(childView: View, showHomeAsUp: Boolean = true, title: CharSequence? = null) {
        val base = LayoutInflater.from(this).inflate(R.layout.activity_base, null)
        val container = base.findViewById<FrameLayout>(R.id.content_container)
        container.addView(childView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        progressBar = base.findViewById(R.id.progress_bar)
        super.setContentView(base)
        setupToolbar(base, showHomeAsUp, title)
    }

    /**
     * Internal helper that configures the MaterialToolbar found in the inflated base root.
     *
     * @param baseRoot the root view of the inflated base layout
     * @param showHomeAsUp whether to show the up/home affordance
     * @param title optional title to set on the support action bar
     */
    private fun setupToolbar(baseRoot: View, showHomeAsUp: Boolean, title: CharSequence?) {
        val toolbar = baseRoot.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar?.let {
            setSupportActionBar(it)
            supportActionBar?.setDisplayHomeAsUpEnabled(showHomeAsUp)
            title?.let { t -> supportActionBar?.title = t }
        }
    }

    /**
     * Show the base layout's ProgressBar.
     *
     * This method is safe to call from background threads; the visibility change will
     * be posted to the UI thread via [runOnUiThread]. If the base layout was not set yet
     * (progressBar == null) the call is a no-op.
     */
    protected open fun showLoading() {
        runOnUiThread {
            progressBar?.visibility = View.VISIBLE
        }
    }

    /**
     * Hide the base layout's ProgressBar.
     *
     * Safe to call from background threads. No-op if the progress bar hasn't been cached.
     */
    protected open fun hideLoading() {
        runOnUiThread {
            progressBar?.visibility = View.GONE
        }
    }

}
