package com.cupcakecomics.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cupcakecomics.data.ConnectionRepository
import com.cupcakecomics.data.SmbShareEntity
import com.cupcakecomics.smb.SmbBrowser
import com.nkanaev.comics.R
import com.nkanaev.comics.activity.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmbEditFragment : Fragment() {
    private lateinit var repo: ConnectionRepository
    private lateinit var browser: SmbBrowser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConnectionRepository(requireContext())
        browser = SmbBrowser(repo.credentialStore())
        requireActivity().title = getString(R.string.connections_add_smb_title)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_smb_edit, container, false)
        val displayName = view.findViewById<EditText>(R.id.smb_display_name)
        val host = view.findViewById<EditText>(R.id.smb_host)
        val port = view.findViewById<EditText>(R.id.smb_port)
        val share = view.findViewById<EditText>(R.id.smb_share)
        val startPath = view.findViewById<EditText>(R.id.smb_start_path)
        val domain = view.findViewById<EditText>(R.id.smb_domain)
        val username = view.findViewById<EditText>(R.id.smb_username)
        val password = view.findViewById<EditText>(R.id.smb_password)
        val guest = view.findViewById<CheckBox>(R.id.smb_guest)
        val status = view.findViewById<TextView>(R.id.smb_status)

        // Sensible defaults for this LAN (password stays blank — type it each save).
        if (host.text.isNullOrBlank()) host.setText("192.168.18.15")
        if (share.text.isNullOrBlank()) share.setText("Comics")
        if (username.text.isNullOrBlank()) username.setText("Comics")
        guest.isChecked = false

        fun buildDraft(storePasswordTemporarily: Boolean): Pair<SmbShareEntity, String?> {
            val parsed = SmbBrowser.parseTarget(
                host.text?.toString().orEmpty(),
                share.text?.toString().orEmpty(),
                startPath.text?.toString().orEmpty(),
                port.text?.toString().orEmpty(),
            )
            host.setText(parsed.host)
            share.setText(parsed.shareName)
            startPath.setText(parsed.startPath)
            port.setText(parsed.port.toString())

            val useGuest = guest.isChecked
            val pass = password.text?.toString().orEmpty()
            var tempKey: String? = null
            val credKey = if (!useGuest && storePasswordTemporarily && pass.isNotEmpty()) {
                repo.credentialStore().putSecret(pass).also { tempKey = it }
            } else {
                ""
            }
            val entity = SmbShareEntity(
                displayName = displayName.text?.toString().orEmpty().ifBlank { "${parsed.host}/${parsed.shareName}" },
                host = parsed.host,
                port = parsed.port,
                shareName = parsed.shareName,
                startPath = parsed.startPath,
                domain = domain.text?.toString().orEmpty(),
                username = username.text?.toString().orEmpty(),
                credentialKey = credKey,
                useGuest = useGuest,
            )
            return entity to tempKey
        }

        view.findViewById<Button>(R.id.smb_test).setOnClickListener {
            if (guest.isChecked) {
                status.text = getString(R.string.smb_guest_warning)
            }
            status.text = getString(R.string.smb_testing)
            viewLifecycleOwner.lifecycleScope.launch {
                val (entity, tempKey) = buildDraft(storePasswordTemporarily = true)
                if (entity.shareName.isBlank()) {
                    status.text = getString(R.string.smb_share_required)
                    return@launch
                }
                if (!entity.useGuest && entity.username.isBlank()) {
                    status.text = getString(R.string.smb_user_required)
                    return@launch
                }
                val result = withContext(Dispatchers.IO) { browser.testConnection(entity) }
                tempKey?.let { repo.credentialStore().deleteSecret(it) }
                status.text = if (result.isSuccess) {
                    getString(R.string.smb_test_ok)
                } else {
                    result.exceptionOrNull()?.message ?: getString(R.string.smb_test_fail, "error")
                }
            }
        }

        view.findViewById<Button>(R.id.smb_save).setOnClickListener {
            val parsed = SmbBrowser.parseTarget(
                host.text?.toString().orEmpty(),
                share.text?.toString().orEmpty(),
                startPath.text?.toString().orEmpty(),
                port.text?.toString().orEmpty(),
            )
            if (parsed.host.isEmpty() || parsed.shareName.isEmpty()) {
                Toast.makeText(requireContext(), R.string.smb_host_share_required, Toast.LENGTH_LONG).show()
                status.text = getString(R.string.smb_share_required)
                return@setOnClickListener
            }
            if (!guest.isChecked && username.text.isNullOrBlank()) {
                Toast.makeText(requireContext(), R.string.smb_user_required, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                // Verify before saving so we don't store a broken profile.
                val (probe, tempKey) = buildDraft(storePasswordTemporarily = true)
                val result = withContext(Dispatchers.IO) { browser.testConnection(probe) }
                tempKey?.let { repo.credentialStore().deleteSecret(it) }
                if (result.isFailure) {
                    status.text = result.exceptionOrNull()?.message
                        ?: getString(R.string.smb_test_fail, "error")
                    return@launch
                }

                val id = repo.addSmbShare(
                    displayName = displayName.text?.toString().orEmpty(),
                    host = parsed.host,
                    port = parsed.port,
                    shareName = parsed.shareName,
                    startPath = parsed.startPath,
                    domain = domain.text?.toString().orEmpty(),
                    username = username.text?.toString().orEmpty(),
                    password = password.text?.toString().orEmpty(),
                    useGuest = guest.isChecked,
                )
                Toast.makeText(requireContext(), R.string.smb_saved, Toast.LENGTH_SHORT).show()
                // Kick off library card stats for the new share.
                withContext(Dispatchers.IO) {
                    val saved = repo.getSmbShare(id) ?: return@withContext
                    val stats = com.cupcakecomics.smb.SmbLibraryScanner(repo.credentialStore()).scan(saved)
                    stats.onSuccess {
                        repo.updateSmbStats(id, it.comicCount, it.totalBytes)
                    }
                }
                (activity as MainActivity).supportFragmentManager.popBackStack()
                (activity as MainActivity).pushFragment(SmbBrowseFragment.newInstance(id))
            }
        }
        return view
    }
}
