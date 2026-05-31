package com.norm2hacked.di

import com.norm2hacked.ble.AndroidBonder
import com.norm2hacked.ble.BleManager
import com.norm2hacked.ble.WatchBonder
import com.norm2hacked.protocol.WatchTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TransportModule {
    /** Bind BleManager as the WatchTransport implementation for the Android app. */
    @Binds
    @Singleton
    abstract fun bindWatchTransport(impl: BleManager): WatchTransport

    /** Bind AndroidBonder (createBond) as the WatchBonder implementation. */
    @Binds
    @Singleton
    abstract fun bindWatchBonder(impl: AndroidBonder): WatchBonder
}
