package com.locapeer.about

import androidx.lifecycle.ViewModel
import com.locapeer.nostr.NostrRelayClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor(
    val relayClient: NostrRelayClient
) : ViewModel()
