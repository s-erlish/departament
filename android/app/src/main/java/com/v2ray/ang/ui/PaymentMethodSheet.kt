package com.v2ray.ang.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.v2ray.ang.R
import com.v2ray.ang.ui.component.onSingleClick

/**
 * Reusable, Incy-styled (dark surface + blue accents) bottom sheet that lets the user pick a
 * payment method. It exists because the backend rejects payments with HTTP 400
 * "Не выбран способ оплаты" when the app sends no method — callers show this sheet and receive the
 * chosen id back, then forward it to the payment request.
 *
 * Rows are built at runtime from [item_payment_method] into the [sheet_payment_method]
 * container, so the sheet works with any number of Platega methods (СБП, карта…), plus an
 * optional "С баланса" row that returns the id "balance".
 *
 * **How the answer gets back to the caller, and why it is not a callback (D11).**
 * This sheet used to hand its result to a lambda parked in a process-wide map, recovered by key
 * after a configuration change. That lambda captured the host that created it — an Activity, or a
 * Fragment's view binding — so rotating the phone with the sheet open and then picking a method
 * invoked a closure over a **destroyed** host and crashed the app. A parked closure cannot be
 * re-bound to the instance that replaced it; that is the whole defect, not an implementation
 * detail of it.
 *
 * So the result travels as **data**, through [FragmentManager.setFragmentResult]: the pick is a
 * [Bundle], the framework holds it until the host's listener is registered again, and the listener
 * is re-registered by the *live* host on every recreation. Rotating mid-flow now resumes exactly
 * where it left off.
 *
 * Because a host's own selection state may not survive that recreation either, [show] takes a
 * `payload` [Bundle] that is echoed back verbatim in the result. Everything the caller needs to
 * charge the right amount for the right thing rides along with the answer, so the charge can never
 * be rebuilt from state a rotation quietly reset.
 */
class PaymentMethodSheet : BottomSheetDialogFragment() {

    private var requestKey: String = DEFAULT_REQUEST_KEY
    private var title: String = ""
    private var balanceLabel: String? = null
    private var methods: List<Pair<String, String>> = emptyList()
    private var payload: Bundle = Bundle()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = arguments ?: return
        requestKey = args.getString(ARG_REQUEST_KEY) ?: DEFAULT_REQUEST_KEY
        title = args.getString(ARG_TITLE, "")
        balanceLabel = args.getString(ARG_BALANCE_LABEL)
        val ids = args.getStringArrayList(ARG_METHOD_IDS) ?: arrayListOf()
        val labels = args.getStringArrayList(ARG_METHOD_LABELS) ?: arrayListOf()
        methods = ids.indices.map { ids[it] to (labels.getOrNull(it) ?: ids[it]) }
        payload = args.getBundle(ARG_PAYLOAD) ?: Bundle()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.sheet_payment_method, container, false)

        val tvTitle = root.findViewById<TextView>(R.id.tv_pay_title)
        tvTitle.text = title.ifBlank { getString(R.string.pay_method_title) }

        val rows = root.findViewById<LinearLayout>(R.id.ll_pay_methods)

        // Balance row first, when a balance label is supplied.
        balanceLabel?.let { label ->
            addRow(
                inflater = inflater,
                parent = rows,
                iconRes = R.drawable.ic_pay_wallet,
                label = getString(R.string.pay_method_from_balance_fmt, label),
                methodId = ID_BALANCE,
            )
        }

        // Platega methods (СБП, карта…).
        for ((id, label) in methods) {
            val isSbp = id.contains("sbp", ignoreCase = true) || label.contains("СБП", ignoreCase = true)
            addRow(
                inflater = inflater,
                parent = rows,
                iconRes = if (isSbp) R.drawable.ic_pay_sbp else R.drawable.ic_pay_card,
                label = label,
                methodId = id,
            )
        }

        return root
    }

    /**
     * Adds one method row.
     *
     * THE TILE NO LONGER VARIES. Every row used to name its own plate — green for the balance,
     * blue for the Platega methods — and D-5 closes the coloured tile set at three (accent,
     * destructive, neutral) precisely because a fourth colour implies a category system this
     * product does not have. What actually tells the rows apart is the GLYPH: wallet, СБП, card.
     * The neutral plate and its tint come from the layout, through theme attributes, so the mono
     * overlay reaches both.
     */
    private fun addRow(
        inflater: LayoutInflater,
        parent: LinearLayout,
        iconRes: Int,
        label: String,
        methodId: String,
    ) {
        val row = inflater.inflate(R.layout.item_payment_method, parent, false)
        row.findViewById<TextView>(R.id.tv_pay_label).text = label
        row.findViewById<ImageView>(R.id.iv_pay_icon).setImageResource(iconRes)
        // A rule belongs BETWEEN two rows: on the first one it would cut across the gap that
        // separates the list from the title above it.
        row.findViewById<View>(R.id.pay_method_divider).isVisible = parent.childCount > 0
        row.onSingleClick {
            // Rows stop responding the moment one of them is taken: a bottom sheet dismisses over
            // a frame or two, which is long enough for a second tap to land on a second row and
            // post a second result — i.e. a second charge. onSingleClick guards the same row; this
            // guards its neighbours, and the sheet needs both.
            parent.isEnabled = false
            for (i in 0 until parent.childCount) parent.getChildAt(i).isClickable = false
            val result = Bundle(payload).apply { putString(RESULT_METHOD_ID, methodId) }
            parentFragmentManager.setFragmentResult(requestKey, result)
            dismissAllowingStateLoss()
        }
        parent.addView(row)
    }

    companion object {
        private const val TAG = "PaymentMethodSheet"

        /** Id returned by the "С баланса" row. */
        const val ID_BALANCE = "balance"

        /** Result key carrying the chosen method id. */
        const val RESULT_METHOD_ID = "result_method_id"

        /** Default request key, used when a caller has only one payment flow on the surface. */
        const val DEFAULT_REQUEST_KEY = "payment_method_pick"

        private const val ARG_REQUEST_KEY = "arg_request_key"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_BALANCE_LABEL = "arg_balance_label"
        private const val ARG_METHOD_IDS = "arg_method_ids"
        private const val ARG_METHOD_LABELS = "arg_method_labels"
        private const val ARG_PAYLOAD = "arg_payload"

        /**
         * Shows a themed bottom sheet titled [title] on [fm].
         *
         * If [balanceLabel] != null, the FIRST row is "С баланса — <balanceLabel>" and returns id
         * [ID_BALANCE]. Each of [methods] (id to label) becomes a row returning its id (these are
         * Platega methods: СБП, карта…).
         *
         * The pick arrives as a fragment result on [requestKey]: [RESULT_METHOD_ID] holds the
         * chosen id, and every key of [payload] is echoed back beside it. Register the listener on
         * the same [fm], against a lifecycle owner that is recreated with the host — the framework
         * then delivers a pick made before a rotation to the host that exists after it.
         *
         * Showing twice is a no-op: a sheet already on screen is left alone rather than stacked.
         */
        fun show(
            fm: FragmentManager,
            requestKey: String,
            title: String,
            balanceLabel: String?,
            methods: List<Pair<String, String>>,
            payload: Bundle = Bundle(),
        ) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return

            val sheet = PaymentMethodSheet()
            sheet.arguments = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putString(ARG_TITLE, title)
                putString(ARG_BALANCE_LABEL, balanceLabel)
                putStringArrayList(ARG_METHOD_IDS, ArrayList(methods.map { it.first }))
                putStringArrayList(ARG_METHOD_LABELS, ArrayList(methods.map { it.second }))
                putBundle(ARG_PAYLOAD, payload)
            }
            sheet.show(fm, TAG)
        }
    }
}
