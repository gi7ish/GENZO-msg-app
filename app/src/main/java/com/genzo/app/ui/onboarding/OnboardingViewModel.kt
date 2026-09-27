package com.genzo.app.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genzo.app.domain.GenzoRepository
import kotlinx.coroutines.launch

sealed interface OnboardingState {
    data object Idle : OnboardingState
    data object Registering : OnboardingState
    data class Error(val message: String) : OnboardingState
    data object Done : OnboardingState
}

class OnboardingViewModel(private val repository: GenzoRepository) : ViewModel() {

    var state by mutableStateOf<OnboardingState>(OnboardingState.Idle)
        private set

    /** Step 1 + Step 2 from the product brief: generate identity, generate
     * + register prekeys — all the way through the relay's /v1/register. */
    fun createIdentity(username: String) {
        val trimmed = username.trim()
        if (trimmed.isEmpty()) {
            state = OnboardingState.Error("Pick a username first.")
            return
        }
        state = OnboardingState.Registering
        viewModelScope.launch {
            try {
                repository.createIdentityAndRegister(trimmed)
                state = OnboardingState.Done
            } catch (e: Exception) {
                state = OnboardingState.Error(e.message ?: "Could not reach the relay.")
            }
        }
    }
}
