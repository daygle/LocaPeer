package com.locapeer.di

import android.app.NotificationManager
import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideNotificationManager(@ApplicationContext context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * AppLockManager is a @Singleton @Inject already; we don't provide it explicitly
     * because Hilt's constructor injection handles construction. This module exists so
     * a future migration to a non-injectable lock backend (e.g. an externally owned
     * AuthRequiredActivity) can drop in a provider here without touching call sites.
     */
}
