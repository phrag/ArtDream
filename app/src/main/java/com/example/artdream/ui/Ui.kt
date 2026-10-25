package com.example.artdream.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.artdream.R
import kotlin.math.roundToInt

/*
 * Small view factory for the 10-foot settings UI. Everything is built in code
 * (no layout XML) so the screen stays a plain-framework, dependency-free app;
 * the look lives in res/drawable + res/color selectors keyed on focus state.
 */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

/** Grow a little and lift when focused, so the D-pad position is obvious from the sofa. */
fun View.focusScale(scale: Float, onFocus: ((Boolean) -> Unit)? = null) {
    val lift = context.dpf(6f)
    setOnFocusChangeListener { v, has ->
        v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f)
            .translationZ(if (has) lift else 0f).setDuration(140).start()
        onFocus?.invoke(has)
    }
}

object Ui {
    const val TITLE_SP = 30f
    const val HEADER_SP = 26f
    const val LABEL_SP = 20f
    const val BODY_SP = 17f
    const val SMALL_SP = 15f

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(w, h)

    fun column(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun row(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun text(ctx: Context, s: CharSequence, sp: Float, colorRes: Int = R.color.ad_text) = TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(ctx.getColor(colorRes))
        setLineSpacing(0f, 1.15f)
    }

    fun header(ctx: Context, s: String) = text(ctx, s, HEADER_SP).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, 0, 0, ctx.dp(6))
    }

    fun body(ctx: Context, s: CharSequence) = text(ctx, s, BODY_SP, R.color.ad_text2).apply {
        setPadding(0, ctx.dp(4), 0, ctx.dp(4))
    }

    fun groupLabel(ctx: Context, s: String) = text(ctx, s.uppercase(), SMALL_SP, R.color.ad_text3).apply {
        letterSpacing = 0.08f
        setPadding(0, ctx.dp(22), 0, ctx.dp(8))
    }

    /** Amber-on-dark callout used whenever a setting would silently yield nothing. */
    fun warning(ctx: Context) = text(ctx, "", BODY_SP, R.color.ad_warn).apply {
        setBackgroundResource(R.drawable.ad_warn_bg)
        setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
        visibility = View.GONE
    }

    fun button(ctx: Context, label: String, primary: Boolean = false, onClick: (View) -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = LABEL_SP
        stateListAnimator = null
        minHeight = ctx.dp(52)
        minWidth = ctx.dp(120)
        setPadding(ctx.dp(24), ctx.dp(10), ctx.dp(24), ctx.dp(10))
        if (primary) {
            setBackgroundResource(R.drawable.ad_btn_primary_bg)
            setTextColor(ctx.getColor(R.color.ad_on_focus))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        } else {
            setBackgroundResource(R.drawable.ad_btn_bg)
            setTextColor(ctx.getColorStateList(R.color.ad_btn_text))
        }
        setOnClickListener(onClick)
        focusScale(1.06f)
    }

    fun spacer(ctx: Context, hDp: Int) = View(ctx).apply { layoutParams = lp(1, ctx.dp(hDp)) }
}

/**
 * A full-width focusable settings row: label + optional secondary line on the
 * left, a value "pill" on the right (On/Off, or a value like "1 min").
 * Clicking (OK on the remote) runs [onClick]. Code-only view, never inflated.
 */
@SuppressLint("ViewConstructor")
class SettingRow(ctx: Context, label: String, sub: String? = null, indent: Boolean = false) : LinearLayout(ctx) {

    private val labelView: TextView
    private val subView: TextView
    private val pill: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setBackgroundResource(R.drawable.ad_row_bg)
        val padStart = ctx.dp(if (indent) 44 else 20)
        setPadding(padStart, ctx.dp(12), ctx.dp(16), ctx.dp(12))
        minimumHeight = ctx.dp(if (indent) 56 else 64)
        layoutParams = Ui.lp().apply {
            topMargin = ctx.dp(if (indent) 4 else 6)
            marginStart = ctx.dp(if (indent) 24 else 0)
        }

        val texts = Ui.column(ctx)
        labelView = Ui.text(ctx, label, if (indent) Ui.BODY_SP + 1 else Ui.LABEL_SP).apply {
            isDuplicateParentStateEnabled = true
            setTextColor(ctx.getColorStateList(R.color.ad_btn_text))
        }
        subView = Ui.text(ctx, sub ?: "", Ui.SMALL_SP).apply {
            isDuplicateParentStateEnabled = true
            setTextColor(ctx.getColorStateList(R.color.ad_row_sub_text))
            visibility = if (sub.isNullOrEmpty()) GONE else VISIBLE
        }
        texts.addView(labelView)
        texts.addView(subView)
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        pill = TextView(ctx).apply {
            textSize = Ui.SMALL_SP
            gravity = Gravity.CENTER
            minWidth = ctx.dp(72)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(ctx.dp(14), ctx.dp(6), ctx.dp(14), ctx.dp(6))
        }
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = ctx.dp(16)
        })
        focusScale(1.02f)
    }

    fun setSub(s: String?) {
        subView.text = s ?: ""
        subView.visibility = if (s.isNullOrEmpty()) GONE else VISIBLE
    }

    fun setOnOff(on: Boolean) {
        pill.text = if (on) "On" else "Off"
        pill.setBackgroundResource(if (on) R.drawable.ad_pill_on else R.drawable.ad_pill_off)
        pill.setTextColor(context.getColor(if (on) R.color.ad_on_focus else R.color.ad_text2))
    }

    fun setValue(v: String) {
        pill.text = v
        pill.setBackgroundResource(R.drawable.ad_pill_on)
        pill.setTextColor(context.getColor(R.color.ad_on_focus))
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }

    companion object {
        /** A row that flips a boolean on OK. */
        fun toggle(
            ctx: Context, label: String, sub: String?, initial: Boolean, indent: Boolean = false,
            onChange: (Boolean) -> Unit,
        ): SettingRow {
            var on = initial
            return SettingRow(ctx, label, sub, indent).apply {
                setOnOff(on)
                setOnClickListener {
                    on = !on
                    setOnOff(on)
                    onChange(on)
                }
            }
        }
    }
}
