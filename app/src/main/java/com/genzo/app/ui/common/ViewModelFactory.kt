package com.genzo.app.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras

/** Minimal factory for constructor-injecting [GenzoRepository] into
 * ViewModels without pulling in Hilt/Dagger — see GenzoApplication's doc
 * comment for why. */
class SimpleViewModelFactory(private val create: () -> ViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = create() as T
}
