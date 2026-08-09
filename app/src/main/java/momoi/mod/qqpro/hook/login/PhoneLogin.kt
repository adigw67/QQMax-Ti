package momoi.mod.qqpro.hook.login

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tencent.qqnt.account.register.ui.RegisterInputPhoneFragment
import com.tencent.qqnt.account.register.ui.RegisterLicenseFragment
import com.tencent.qqnt.account.register.ui.RegisterVerifySmsFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.lib.material.M3ProgressDrawable
import momoi.mod.qqpro.util.Utils

/**
 * Materialize the phone-number login chain (gated by `materializeLogin`):
 *  - [LicenseBypass]   the 服务协议/隐私 page is auto-skipped (no visible license screen).
 *  - [PhoneInputM3]    phone entry via a Material field + the SYSTEM IME instead of the custom keypad.
 *  - [SmsVerifyM3]     SMS code entry via a Material field + system IME, with resend/countdown.
 *
 * The native register *engine* (WatchRegisterServlet calls, the SMS receiver, navigation) is reused:
 * the custom `NumericKeyboardView` keeps its native confirm wiring (we just drive it), and we feed the
 * typed value straight into the fragments' own state fields (`g`=phone, `f`=sms code) + submit helper.
 * All view-building lives here in `momoi.*` (not in the @Mixin bodies copied into QQ's package).
 */

@Mixin
class LicenseBypass : RegisterLicenseFragment() {
    override fun W(): Boolean = if (Settings.materializeLogin.value) true else super.W()

    override fun Y(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val native = super.Y(inflater, container, savedInstanceState)
        if (Settings.materializeLogin.value) {
            PhoneLoginM3.licenseAutoAdvance(native)?.let { return it }
        }
        return native
    }
}

@Mixin
class PhoneInputM3 : RegisterInputPhoneFragment() {
    override fun W(): Boolean = if (Settings.materializeLogin.value) true else super.W()

    override fun Y(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val native = super.Y(inflater, container, savedInstanceState)
        if (Settings.materializeLogin.value) {
            PhoneLoginM3.buildPhoneInput(this)?.let { return it }
        }
        return native
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState) // native wires the keypad confirm listener
        if (Settings.materializeLogin.value) PhoneLoginM3.focusPhone()
    }
}

@Mixin
class SmsVerifyM3 : RegisterVerifySmsFragment() {
    override fun W(): Boolean = if (Settings.materializeLogin.value) true else super.W()

    override fun Y(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val native = super.Y(inflater, container, savedInstanceState)
        if (Settings.materializeLogin.value) {
            PhoneLoginM3.buildSms(this)?.let { return it }
        }
        return native
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (Settings.materializeLogin.value) PhoneLoginM3.focusSms()
    }

    // I(code) = SMS auto-read autofill. Route into our field (which submits at 6 digits).
    override fun I(code: String) {
        if (Settings.materializeLogin.value) PhoneLoginM3.onSmsAutofill(code) else super.I(code)
    }

    // E(time, canReFetch) = resend countdown tick.
    override fun E(time: Int, canReFetch: Boolean) {
        super.E(time, canReFetch)
        if (Settings.materializeLogin.value) PhoneLoginM3.onSmsTick(time, canReFetch)
    }
}

object PhoneLoginM3 {
    @Volatile private var phoneField: EditText? = null
    @Volatile private var smsField: EditText? = null
    @Volatile private var resendBtn: M3Button? = null

    // ── License: render a brief M3 "please wait" then trigger the native agree → navigation ────────
    fun licenseAutoAdvance(native: View?): View? {
        val root = native ?: return null
        return try {
            val ctx = root.context
            val agreeId = ctx.resources.getIdentifier("agree", "id", ctx.packageName)
            val agree = if (agreeId != 0) root.findViewById<View>(agreeId) else null
            val loading = scaffold(ctx, title = null) { column ->
                column.addView(ImageView(ctx).apply {
                    setImageDrawable(M3ProgressDrawable(M3.primary, 3f * density(ctx)))
                }, LinearLayout.LayoutParams(28.dp, 28.dp).apply { bottomMargin = 12.dp })
                column.addView(TextView(ctx).apply {
                    text = "正在进入手机号登录…"
                    setTextColor(M3.onSurfaceVariant); textSize = 13f
                })
            }
            if (agree == null) {
                Utils.log("PhoneLoginM3: agree button not found, keep native license")
                return root
            }
            // Auto-advance once attached: the native agree click runs the network/permission gate then
            // navigates to the gateway page — so the license screen is skipped without reimplementing nav.
            loading.post {
                runCatching { agree.performClick() }
                    .onFailure { Utils.log("PhoneLoginM3: license auto-advance failed: $it") }
            }
            loading
        } catch (e: Throwable) {
            Utils.log("PhoneLoginM3: license build failed ($e), keep native")
            root
        }
    }

    // ── Phone number entry: Material field + system IME ────────────────────────────────────────────
    fun buildPhoneInput(frag: RegisterInputPhoneFragment): View? {
        return try {
            val ctx = frag.requireContext()
            val field = m3Field(ctx, "请输入手机号", InputType.TYPE_CLASS_PHONE, 11)
            field.addTextChangedListener(simpleWatcher {
                val digits = field.text.toString().filter { it.isDigit() }.take(11)
                runCatching { frag.g = digits }                 // curPhoneNum (native confirm reads this)
                runCatching { frag.f.c.setText(digits) }        // native display field (belt & suspenders)
            })
            val next = M3Button(ctx).apply {
                text = "下一步"
                variant(M3Button.Variant.FILLED)
                minWidth = 140.dp
                setOnClickListener {
                    val digits = (frag.g ?: "").filter { it.isDigit() }
                    if (digits.length < 5) { Utils.toast(ctx, "请输入完整手机号"); return@setOnClickListener }
                    // Drive the native keypad's confirm button → native query/commit servlet → SMS page.
                    runCatching { frag.f.b.functionBtnRight?.performClick() }
                        .onFailure { Utils.log("PhoneLoginM3: phone confirm failed: $it") }
                }
            }
            phoneField = field
            scaffold(ctx, title = "输入手机号", subtitle = "未注册的手机号将自动注册") { column ->
                column.addView(field, fieldParams())
                column.addView(next, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 18.dp })
            }
        } catch (e: Throwable) {
            Utils.log("PhoneLoginM3: phone build failed ($e), keep native")
            null
        }
    }

    fun focusPhone() = phoneField?.showSystemIme()

    // ── SMS code entry: Material field + system IME ────────────────────────────────────────────────
    fun buildSms(frag: RegisterVerifySmsFragment): View? {
        return try {
            val ctx = frag.requireContext()
            val field = m3Field(ctx, "输入6位验证码", InputType.TYPE_CLASS_NUMBER, 6)
            field.addTextChangedListener(simpleWatcher {
                val code = field.text.toString().filter { it.isDigit() }.take(6)
                runCatching { frag.f = code }                   // smsCode field
                runCatching { frag.e.c.setText(code) }          // native display
                if (code.length == 6) runCatching { frag.g.b(code) }  // RegisterHelper.b = submit
            })
            val resend = M3Button(ctx).apply {
                text = "重新发送"
                variant(M3Button.Variant.TEXT)
                setOnClickListener {
                    // Native keypad confirm on the SMS page = resend/fetch.
                    runCatching { frag.e.b.functionBtnRight?.performClick() }
                        .onFailure { Utils.log("PhoneLoginM3: sms resend failed: $it") }
                }
            }
            smsField = field; resendBtn = resend
            scaffold(ctx, title = "输入验证码", subtitle = "验证码已发送至你的手机") { column ->
                column.addView(field, fieldParams())
                column.addView(resend, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 14.dp })
            }
        } catch (e: Throwable) {
            Utils.log("PhoneLoginM3: sms build failed ($e), keep native")
            null
        }
    }

    fun focusSms() = smsField?.showSystemIme()

    fun onSmsAutofill(code: String?) {
        val f = smsField ?: return
        val digits = code?.filter { it.isDigit() }?.take(6) ?: return
        f.post { runCatching { f.setText(digits) } }  // triggers the watcher → submit at 6
    }

    fun onSmsTick(time: Int, canReFetch: Boolean) {
        val b = resendBtn ?: return
        b.post {
            if (canReFetch || time <= 0) {
                b.text = "重新发送"; b.isEnabled = true
            } else {
                b.text = "${time}s 后重新发送"; b.isEnabled = false
            }
        }
    }

    // ── shared builders ────────────────────────────────────────────────────────────────────────────

    /** Vertical M3 surface page: optional centered title/subtitle, then [body] content. */
    private inline fun scaffold(
        ctx: Context, title: String?, subtitle: String? = null, body: (LinearLayout) -> Unit,
    ): View {
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val ph = 22.dp
            setPadding(ph, 22.dp, ph, 22.dp)
        }
        if (title != null) column.addView(TextView(ctx).apply {
            text = title; setTextColor(M3.onSurface); textSize = 19f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        if (subtitle != null) column.addView(TextView(ctx).apply {
            text = subtitle; setTextColor(M3.onSurfaceVariant); textSize = 12f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 6.dp })
        body(column)
        return ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(M3.surface)
            addView(column, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
    }

    private fun m3Field(ctx: Context, hint: String, imeType: Int, maxLen: Int): EditText =
        EditText(ctx).apply {
            background = M3.rounded(M3.surfaceContainer, M3.radiusMd)
            setTextColor(M3.onSurface)
            setHintTextColor(M3.hint)
            this.hint = hint
            inputType = imeType
            gravity = Gravity.CENTER
            textSize = 18f
            isSingleLine = true
            setPadding(16.dp, 13.dp, 16.dp, 13.dp)
            filters = arrayOf(InputFilter.LengthFilter(maxLen))
        }

    private fun fieldParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        width = 220.dp; topMargin = 22.dp
    }

    private fun EditText.showSystemIme() {
        post {
            runCatching {
                requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun density(ctx: Context) = ctx.resources.displayMetrics.density

    private inline fun simpleWatcher(crossinline after: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { after() }
    }
}
