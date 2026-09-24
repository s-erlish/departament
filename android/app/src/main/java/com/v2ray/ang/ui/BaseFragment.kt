package com.v2ray.ang.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.viewbinding.ViewBinding
import com.v2ray.ang.viewmodel.MainViewModel

/**
 * The base every fragment in this app extends: a ViewBinding whose lifetime is the VIEW's, not the
 * fragment's, plus the two shell handles a bottom-nav tab needs.
 *
 * A tab fragment lives longer than its view — the shell hides and shows tabs rather than replacing
 * them — so [binding] must be released in `onDestroyView` and every async callback must check
 * [isBindingInitialized] before touching it.
 */
abstract class BaseFragment<VB : ViewBinding> : Fragment() {
    private var _binding: VB? = null
    protected val binding: VB
        get() = _binding!!

    /**
     * The shell's shared state: the server cache, the provider groups, the tunnel's running state,
     * the speed feed. Scoped to the ACTIVITY, so all four tabs read and write one instance and a
     * tab switch can never leave two copies disagreeing about what is connected.
     *
     * Lazy — a fragment that never touches it never constructs it.
     */
    protected val mainViewModel: MainViewModel by activityViewModels()

    /**
     * The shell that hosts this fragment: the connect actions and the tab switch. Everything a tab
     * needs from the activity goes through [MainHost]; nothing casts to `MainActivity`.
     *
     * Valid for any fragment `MainActivity` hosts (i.e. every bottom-nav tab). A fragment hosted by
     * some other activity must not read it.
     */
    protected val mainHost: MainHost
        get() = requireActivity() as MainHost

    /** True while the view (and its binding) is alive; use to guard async UI callbacks. */
    protected val isBindingInitialized: Boolean
        get() = _binding != null

    protected abstract fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?): VB

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = inflateBinding(inflater, container)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
