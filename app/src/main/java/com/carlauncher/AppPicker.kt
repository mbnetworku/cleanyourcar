package com.carlauncher

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** An app tile. An empty [pkg] is the "add" tile. */
class AppItem(val pkg: String, val label: String)

/** Loads icons off the UI thread and caches them, so grids scroll smoothly on slow head units. */
object IconLoader {
    private val cache = LruCache<String, Drawable>(160)
    private val pool = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())

    /** CarPlay-style icon: the app icon on a dark glossy rounded-square tile with a light rim. */
    private fun styled(src: Drawable): Drawable {
        val s = 144
        val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val r = s * 0.24f
        val rect = android.graphics.RectF(2f, 2f, s - 2f, s - 2f)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, s.toFloat(), 0xFF454A53.toInt(), 0xFF16191E.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, p)
        val inset = (s * 0.17f).toInt()
        src.setBounds(inset, inset, s - inset, s - inset)
        src.draw(c)
        val sheen = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        sheen.shader = android.graphics.LinearGradient(0f, 0f, 0f, s * 0.5f, 0x33FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, sheen)
        val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        rim.style = android.graphics.Paint.Style.STROKE
        rim.strokeWidth = 3f
        rim.color = 0x55FFFFFF
        c.drawRoundRect(rect, r, r, rim)
        return android.graphics.drawable.BitmapDrawable(bmp)
    }

    fun load(pm: PackageManager, pkg: String, view: ImageView) {
        Apps.builtIn(pkg)?.let { b ->
            view.tag = pkg
            val d = cache.get(pkg) ?: styled(view.context.getDrawable(b.icon)!!).also { cache.put(pkg, it) }
            view.setImageDrawable(d.constantState?.newDrawable() ?: d)
            return
        }
        view.tag = pkg
        val hit = cache.get(pkg)
        if (hit != null) {
            view.setImageDrawable(hit.constantState?.newDrawable() ?: hit)
            return
        }
        view.setImageDrawable(null)
        pool.execute {
            val d = (try {
                styled(pm.getApplicationIcon(pkg))
            } catch (e: Exception) {
                null
            }) ?: return@execute
            cache.put(pkg, d)
            ui.post { if (view.tag == pkg) view.setImageDrawable(d.constantState?.newDrawable() ?: d) }
        }
    }
}

class AppAdapter(ctx: Context, private var items: List<AppItem>) : BaseAdapter() {
    private val inflater = LayoutInflater.from(ctx)
    private val pm = ctx.packageManager

    fun set(list: List<AppItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: inflater.inflate(R.layout.item_app, parent, false)
        val item = items[position]
        v.findViewById<TextView>(R.id.label).text = item.label
        val icon = v.findViewById<ImageView>(R.id.icon)
        if (item.pkg.isEmpty()) {
            icon.tag = null
            icon.setImageResource(R.drawable.ic_add)
        } else {
            IconLoader.load(pm, item.pkg, icon)
        }
        return v
    }
}


/** The app library: search, a row of the launcher's own car apps, and every installed app in an A–Z grid. */
object AppPicker {
    @Volatile private var cached: List<AppItem>? = null
    @Volatile private var cachedAt = 0L

    fun invalidate() { cached = null }

    fun preload(ctx: Context) {
        Thread { query(ctx) }.start()
    }

    /** Built-in car apps first, then all launchable apps (other home launchers included), A–Z. */
    fun query(ctx: Context): List<AppItem> {
        cached?.let { if (System.currentTimeMillis() - cachedAt < 30_000) return it }
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = (pm.queryIntentActivities(intent, 0)
            .map { AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString()) } + Apps.otherLaunchers(ctx))
            .filter { it.pkg != ctx.packageName && it.pkg !in Apps.blocked(ctx) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
        val list = Apps.builtIns.map { AppItem(it.key, it.label) } + installed
        cached = list
        cachedAt = System.currentTimeMillis()
        return list
    }

    /** [onResult] gets the chosen package or built-in key, or null when "Remove" is pressed. Closing gives no callback. */
    fun show(act: Activity, title: String, allowRemove: Boolean, onResult: (String?) -> Unit) {
        val dlg = Dialog(act, R.style.PickerTheme)
        val density = act.resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()
        val prefs = act.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        Themes.update(prefs)
        val accent = Themes.accent(prefs)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        fun pill(sel: Boolean) = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = px(22).toFloat()
            setColor(if (sel) accent else 0x40000000)
        }
        fun button(label: String, onClick: () -> Unit) = TextView(act).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(px(22), 0, px(22), 0)
            background = pill(false)
            isClickable = true
            setOnClickListener { onClick() }
        }
        fun pick(pkg: String) { dlg.dismiss(); onResult(pkg) }

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(28), px(18), px(28), px(12))
        }

        // Header: title, search, actions
        val header = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(act).apply {
            text = title
            textSize = 26f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        val search = android.widget.EditText(act).apply {
            hint = "Search apps"
            setHintTextColor(0x99FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 17f
            setSingleLine()
            background = pill(false)
            setPadding(px(20), 0, px(20), 0)
        }
        header.addView(search, LinearLayout.LayoutParams(0, px(48), 1f).apply { leftMargin = px(24); rightMargin = px(12) })
        if (allowRemove) header.addView(button("Remove") { dlg.dismiss(); onResult(null) }, LinearLayout.LayoutParams(wrap, px(48)).apply { leftMargin = px(8) })
        header.addView(button("Settings") { dlg.dismiss(); act.startActivity(Intent(act, SettingsActivity::class.java)) }, LinearLayout.LayoutParams(wrap, px(48)).apply { leftMargin = px(8) })
        header.addView(button("✕") { dlg.dismiss() }, LinearLayout.LayoutParams(px(48), px(48)).apply { leftMargin = px(8) })
        root.addView(header)

        // Car apps row
        val carLabel = TextView(act).apply {
            text = "CAR APPS"
            textSize = 12f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(px(4), px(16), 0, px(6))
        }
        root.addView(carLabel)
        val carRow = LinearLayout(act)
        for (b in Apps.builtIns) {
            val tile = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setBackgroundResource(R.drawable.bg_sel)
                setOnClickListener { pick(b.key) }
            }
            val icon = ImageView(act)
            IconLoader.load(act.packageManager, b.key, icon)
            tile.addView(icon, LinearLayout.LayoutParams(px(70), px(70)))
            tile.addView(TextView(act).apply {
                text = b.label
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, px(6), 0, 0)
            })
            carRow.addView(tile, LinearLayout.LayoutParams(0, px(116), 1f))
        }
        root.addView(carRow, LinearLayout.LayoutParams(match, wrap))
        val divider = View(act).apply { setBackgroundColor(0x33FFFFFF) }
        root.addView(divider, LinearLayout.LayoutParams(match, px(1)).apply { topMargin = px(10) })

        val allLabel = TextView(act).apply {
            text = "ALL APPS"
            textSize = 12f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(px(4), px(12), 0, px(4))
        }
        root.addView(allLabel)
        val adapter = AppAdapter(act, emptyList())
        val grid = GridView(act).apply {
            numColumns = 8
            verticalSpacing = px(10)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setSelector(R.drawable.bg_sel)
            isVerticalScrollBarEnabled = false
            this.adapter = adapter
            setOnItemClickListener { _, _, pos, _ -> pick(adapter.getItem(pos).pkg) }
        }
        root.addView(grid, LinearLayout.LayoutParams(match, 0, 1f))

        var all: List<AppItem> = emptyList()
        fun filter() {
            val q = search.text.toString().trim().lowercase()
            val searching = q.isNotEmpty()
            for (v in listOf(carLabel, carRow, divider)) v.visibility = if (searching) View.GONE else View.VISIBLE
            allLabel.text = if (searching) "RESULTS" else "ALL APPS  ·  ${all.count { !it.pkg.startsWith("builtin:") }}"
            adapter.set(if (searching) all.filter { it.label.lowercase().contains(q) } else all.filter { !it.pkg.startsWith("builtin:") })
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = filter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        Thread {
            val list = query(act)
            act.runOnUiThread { all = list; filter() }
        }.start()

        // Liquid-glass sheet that slides up from the bottom over a dimmed home screen.
        val base = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = px(31).toFloat()
            setColor(0xE60B1620.toInt())
        }
        root.background = android.graphics.drawable.LayerDrawable(
            arrayOf(base, Themes.glass(maxOf(Themes.glassPct(prefs), 30), accent, px(24).toFloat(), px(1)))
        )
        root.isClickable = true
        root.isFocusableInTouchMode = true
        val dm = act.resources.displayMetrics
        val frame = android.widget.FrameLayout(act).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { dlg.dismiss() }
        }
        frame.addView(root, android.widget.FrameLayout.LayoutParams(
            (dm.widthPixels * 0.94f).toInt(), (dm.heightPixels * 0.92f).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = px(14) })
        Themes.shadowText(root)
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(200).start()
        root.translationY = dm.heightPixels.toFloat()
        root.animate().translationY(0f).setDuration(320).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        dlg.setContentView(frame)
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dlg.show()
        root.requestFocus()
        @Suppress("DEPRECATION")
        dlg.window?.decorView?.systemUiVisibility = MainActivity.immersiveFlags()
    }
}
