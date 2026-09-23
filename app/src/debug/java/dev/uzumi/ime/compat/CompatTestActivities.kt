package dev.uzumi.ime.compat

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.uzumi.ime.R
import dev.uzumi.ime.applySystemInsets

/**
 * 互換試験用に、inputTypeとeditor actionの異なるEditTextを一画面に並べる。debugビルドだけに入り、
 * 入力は端末内で完結する。受け取ったeditor actionを欄ごとに数えて上部へ表示し、二重送信を見分ける。
 */
class CompatEditTextActivity : Activity() {
    // 欄ごとのeditor actionの受信回数。一回の操作で二回以上届けば二重送信とわかる。
    private val actionCounts = mutableMapOf<String, Int>()
    private lateinit var log: TextView

    // 伏せ字の欄（機密欄とPIN欄）の本文。画面上は伏せ字でuiautomatorから読めないため、架空の試験値を写す。
    private lateinit var maskedEcho: TextView

    /** 試験用の欄を追加UI依存なしで構築する。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        log = TextView(this).apply {
            id = R.id.compat_log
            textSize = 12f
        }
        content.addView(log)
        maskedEcho = TextView(this).apply {
            id = R.id.compat_masked_echo
            textSize = 12f
        }
        content.addView(maskedEcho)
        val text = InputType.TYPE_CLASS_TEXT
        content.addView(field(R.id.compat_search, "search", text, EditorInfo.IME_ACTION_SEARCH))
        content.addView(field(R.id.compat_send, "send", text, EditorInfo.IME_ACTION_SEND))
        content.addView(field(R.id.compat_next, "next", text, EditorInfo.IME_ACTION_NEXT))
        content.addView(field(R.id.compat_done, "done", text, EditorInfo.IME_ACTION_DONE))
        content.addView(
            field(R.id.compat_multi, "multi", text or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_NONE),
        )
        content.addView(field(R.id.compat_number, "number", InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_DONE))
        content.addView(field(R.id.compat_phone, "phone", InputType.TYPE_CLASS_PHONE, EditorInfo.IME_ACTION_DONE))
        content.addView(
            field(
                R.id.compat_email,
                "email",
                text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                EditorInfo.IME_ACTION_NEXT,
            ),
        )
        content.addView(
            field(R.id.compat_url, "url", text or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_GO),
        )
        content.addView(
            field(
                R.id.compat_password,
                "password",
                text or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                EditorInfo.IME_ACTION_DONE,
            ),
        )
        content.addView(
            field(
                R.id.compat_pin,
                "pin",
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                EditorInfo.IME_ACTION_DONE,
            ),
        )
        content.addView(
            field(R.id.compat_nolearn, "nolearn", text, EditorInfo.IME_ACTION_DONE).apply {
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            },
        )
        val maskedFields = listOf(R.id.compat_password, R.id.compat_pin).map { content.findViewById<EditText>(it) }
        val echoWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                maskedEcho.text = getString(R.string.compat_masked_echo, maskedFields[0].text, maskedFields[1].text)
            }
        }
        maskedFields.forEach { it.addTextChangedListener(echoWatcher) }
        val root = ScrollView(this).apply { addView(content) }
        applySystemInsets(root)
        setContentView(root)
    }

    /**
     * 名前をhintに表示する試験用の欄を作る。NextとDoneは既定の処理（次の欄への移動、キーボードを閉じる）を
     * 試すため消費しない。それ以外は消費し、TextViewが続けて送る代替のEnterキーで回数が増えないようにする。
     */
    private fun field(viewId: Int, name: String, type: Int, action: Int): EditText {
        return EditText(this).apply {
            id = viewId
            hint = name
            inputType = type
            imeOptions = action
            setOnEditorActionListener { _, actionId, _ ->
                val count = (actionCounts[name] ?: 0) + 1
                actionCounts[name] = count
                log.text = getString(R.string.compat_action_log, name, actionId, count)
                actionId != EditorInfo.IME_ACTION_NEXT && actionId != EditorInfo.IME_ACTION_DONE
            }
        }
    }
}

/**
 * 互換試験用に、ローカルのHTMLの入力欄をWebViewで表示する。INTERNET権限は無く、外部へ接続しない。
 * 欄の値とEnter・送信の回数をページ内に表示し、欠落・二重入力・二重送信を見分ける。
 */
class CompatWebViewActivity : Activity() {
    /** HTMLをdata URLではなく基準URLなしの文字列として読み込む。 */
    @SuppressLint("SetJavaScriptEnabled") // 読み込むのは下の固定HTMLだけで、値とイベント数の表示にJavaScriptを使う。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this).apply {
            id = R.id.compat_webview
            settings.javaScriptEnabled = true
            loadDataWithBaseURL(null, PAGE, "text/html", "utf-8", null)
        }
        applySystemInsets(webView)
        setContentView(webView)
    }

    private companion object {
        /**
         * 試験用のHTML。enterkeyhintとtypeの異なる欄、textareaを置き、formの送信は画面遷移させずに数える。
         * 各欄の値は下の表示欄へ角括弧付きで写し、見えない文字の欠落や重複も確かめられるようにする。
         */
        const val PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width">
<style>input,textarea{display:block;width:90%;font-size:18px;margin:4px 0}#echo{font-size:12px;white-space:pre-wrap}</style>
</head><body>
<form onsubmit="return countSubmit(this)"><input id="w_search" type="search" enterkeyhint="search" placeholder="w_search"></form>
<form onsubmit="return countSubmit(this)"><input id="w_send" type="text" enterkeyhint="send" placeholder="w_send"></form>
<form onsubmit="return countSubmit(this)"><input id="w_next" type="text" enterkeyhint="next" placeholder="w_next"><input id="w_done" type="text" enterkeyhint="done" placeholder="w_done"></form>
<textarea id="w_area" rows="3" placeholder="w_area"></textarea>
<input id="w_email" type="email" placeholder="w_email">
<input id="w_url" type="url" placeholder="w_url">
<input id="w_tel" type="tel" placeholder="w_tel">
<input id="w_num" type="text" inputmode="numeric" placeholder="w_num">
<input id="w_pw" type="password" placeholder="w_pw">
<div id="echo"></div>
<script>
var counts = {};
function bump(key) { counts[key] = (counts[key] || 0) + 1; render(); }
function countSubmit(form) { bump('submit:' + form.querySelector('input').id); return false; }
function render() {
  var lines = [];
  document.querySelectorAll('input,textarea').forEach(function (e) {
    lines.push(e.id + '=[' + e.value + ']');
  });
  lines.push(JSON.stringify(counts));
  document.getElementById('echo').textContent = lines.join('\n');
}
document.querySelectorAll('input,textarea').forEach(function (e) {
  e.addEventListener('input', render);
  e.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') bump('enter:' + e.id); });
});
render();
</script></body></html>"""
    }
}
