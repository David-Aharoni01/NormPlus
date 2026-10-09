package com.normplus.di

import com.normplus.ble.AndroidBonder
import com.normplus.ble.BleManager
import com.normplus.ble.WatchBonder
import com.normplus.protocol.WatchTransport
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
