package com.cupcakecomics.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cupcakecomics.data.ConnectionRepository
import com.cupcakecomics.data.KapowarrProfileEntity
import com.cupcakecomics.kapowarr.KapowarrClient
import com.cupcakecomics.kapowarr.KapowarrError
import com.cupcakecomics.kapowarr.KapowarrException
import com.cupcakecomics.kapowarr.KapowarrUrls
import com.nkanaev.comics.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal Kapowarr setup: host + API key → Connect & Save.
 * Private LAN http:// is allowed automatically. Outside-LAN hosts use HTTPS
 * (public http:// is blocked).
 */
class KapowarrEditFragment : Fragment() {
    private lateinit var repo: ConnectionRepository
    private lateinit var client: KapowarrClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConnectionRepository(requireContext())
        client = KapowarrClient(repo.credentialStore())
        requireActivity().title = getString(R.string.connections_add_kapowarr)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_kapowarr_edit, container, false)
        val hostField = view.findViewById<EditText>(R.id.kap_host)
        val apiKey = view.findViewById<EditText>(R.id.kap_api_key)
        val status = view.findViewById<TextView>(R.id.kap_status)
        val connect = view.findViewById<Button>(R.id.kap_connect)

        // Leave blank so domain or LAN address can be typed cleanly.
        hostField.hint = getString(R.string.kapowarr_host_hint)

        connect.setOnClickListener {
            val rawHost = hostField.text?.toString().orEmpty().trim()
            val key = apiKey.text?.toString().orEmpty().trim()
            if (rawHost.isBlank() || key.isBlank()) {
                status.text = getString(R.string.kapowarr_required_simple)
                return@setOnClickListener
            }
            val baseUrl = KapowarrUrls.normalize(rawHost)
            if (baseUrl.isBlank()) {
                status.text = getString(R.string.kapowarr_required_simple)
                return@setOnClickListener
            }
            // Public cleartext is blocked; private LAN http is auto-allowed.
            if (baseUrl.startsWith("http://", ignoreCase = true) && !KapowarrUrls.isPrivateLanHttp(baseUrl)) {
                status.text = getString(R.string.kapowarr_err_public_http)
                return@setOnClickListener
            }

            connect.isEnabled = false
            status.text = getString(R.string.kapowarr_testing)
            viewLifecycleOwner.lifecycleScope.launch {
                val tempKey = repo.credentialStore().putSecret(key)
                val draft = KapowarrProfileEntity(
                    displayName = KapowarrUrls.displayNameFor(baseUrl),
                    baseUrl = baseUrl,
                    apiKeyCredentialKey = tempKey,
                    lanHttpAcknowledged = KapowarrUrls.isPrivateLanHttp(baseUrl),
                )
                val probe = withContext(Dispatchers.IO) { client.probe(draft) }
                // Always drop the temp key; save will store a fresh one.
                repo.credentialStore().deleteSecret(tempKey)

                probe.onFailure { err ->
                    connect.isEnabled = true
                    status.text = friendly(err)
                    return@launch
                }

                repo.addKapowarrProfile(
                    displayName = draft.displayName,
                    baseUrl = baseUrl,
                    apiKey = key,
                    lanHttpAcknowledged = draft.lanHttpAcknowledged,
                )
                Toast.makeText(requireContext(), R.string.kapowarr_saved, Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
        }
        return view
    }

    private fun friendly(err: Throwable): String {
        val code = (err as? KapowarrException)?.code
        return when (code) {
            KapowarrError.AUTH_INVALID -> getString(R.string.kapowarr_err_auth)
            KapowarrError.SERVER_UNREACHABLE -> getString(R.string.kapowarr_err_unreachable)
            KapowarrError.CLEARTEXT_BLOCKED -> getString(R.string.kapowarr_err_cleartext)
            else -> err.message ?: getString(R.string.kapowarr_err_unknown)
        }
    }
}
