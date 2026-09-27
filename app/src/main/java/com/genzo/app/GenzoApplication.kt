package com.genzo.app

import android.app.Application
import com.genzo.app.data.KeystoreKeyProvider
import com.genzo.app.data.LocalStore
import com.genzo.app.domain.GenzoRepository
import com.genzo.app.network.RelayApi
import com.genzo.app.network.RelayConfig

/**
 * Deliberately no DI framework (Hilt/Dagger) here — this MVP is small
 * enough that manual construction is clearer, and it avoids adding an
 * annotation-processing build step to a project that has never been
 * compiled (see /docs/ANDROID_BUILD_STATUS.md). [repository] is the one
 * thing the UI layer is allowed to reach into; it never reaches
 * [keystoreKeyProvider] or the raw database key directly.
 */
class GenzoApplication : Application() {

    lateinit var repository: GenzoRepository
        private set

    override fun onCreate() {
        super.onCreate()

        val keystoreKeyProvider = KeystoreKeyProvider(this)
        val dbKeyHex = keystoreKeyProvider.getOrCreateDatabaseKeyHex()
        val localStore = LocalStore(this, dbKeyHex)
        val relayApi = RelayApi(RelayConfig.baseUrl)

        repository = GenzoRepository(relayApi, localStore)
    }
}
