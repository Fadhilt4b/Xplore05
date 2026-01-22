package com.example.xploreapp

import android.view.View
import androidx.appcompat.app.AppCompatActivity

abstract class BaseActivity : AppCompatActivity()
{
    protected fun showLoading() {
        findViewById<View>(R.id.loadingOverlay)?.visibility = View.VISIBLE
    }

    protected fun hideLoading() {
        findViewById<View>(R.id.loadingOverlay)?.visibility = View.GONE
    }
}
