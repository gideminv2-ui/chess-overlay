package com.example.chessoverlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var web: WebView
    private lateinit var bubble: TextView
    private var big = false
    private var mini = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mini) { mini = false; applyState() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            wm.removeView(root)
            web.destroy()
        } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("chess", "Шахматы", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "chess")
            .setContentTitle("Шахматы против Stockfish")
            .setContentText("Плавающее окно запущено")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun button(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { onClick() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWindow() {
        val ctx = ContextThemeWrapper(applicationContext, android.R.style.Theme_Material)

        header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF2B2B28.toInt())
            setPadding(dp(10), 0, dp(2), 0)
        }
        val title = TextView(ctx).apply {
            text = "♞ Stockfish"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button(ctx, "⤢") { big = !big; applyState() })
        header.addView(button(ctx, "—") { mini = true; applyState() })
        header.addView(button(ctx, "✕") { stopSelf() })

        web = WebView(ctx).apply {
            setBackgroundColor(0xFF1B1B19.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse? {
                    val u = request?.url ?: return null
                    if (u.host != "app.local") return null
                    val name = (u.path ?: "").removePrefix("/")
                    return try {
                        val mime = if (name.endsWith(".html")) "text/html" else "text/javascript"
                        WebResourceResponse(mime, "utf-8", this@OverlayService.assets.open(name))
                    } catch (e: Exception) {
                        WebResourceResponse(
                            "text/plain", "utf-8", 404, "Not Found",
                            emptyMap(), ByteArrayInputStream(ByteArray(0))
                        )
                    }
                }
            }
            loadUrl("https://app.local/game.html")
        }

        bubble = TextView(ctx).apply {
            text = "♞"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF2F6F4F.toInt())
            }
            visibility = View.GONE
        }

        root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(web)
        root.addView(bubble, LinearLayout.LayoutParams(dp(56), dp(56)))

        lp = WindowManager.LayoutParams(
            dp(280), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(96)
        }

        drag(header, false)
        drag(bubble, true)
        wm.addView(root, lp)
        applyState()
    }

    private fun applyState() {
        val w = dp(if (big) 350 else 280)
        header.visibility = if (mini) View.GONE else View.VISIBLE
        web.visibility = if (mini) View.GONE else View.VISIBLE
        bubble.visibility = if (mini) View.VISIBLE else View.GONE
        web.layoutParams = LinearLayout.LayoutParams(w, (w * 1.5f).toInt())
        lp.width = if (mini) WindowManager.LayoutParams.WRAP_CONTENT else w
        wm.updateViewLayout(root, lp)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun drag(v: View, tapExpands: Boolean) {
        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (Math.abs(dx) + Math.abs(dy) > dp(6)) moved = true
                    lp.x = sx + dx
                    lp.y = sy + dy
                    wm.updateViewLayout(root, lp)
                }
                MotionEvent.ACTION_UP -> {
                    if (tapExpands && !moved) { mini = false; applyState() }
                }
            }
            true
        }
    }
}
