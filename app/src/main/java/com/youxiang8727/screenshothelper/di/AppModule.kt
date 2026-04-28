package com.youxiang8727.screenshothelper.di

import android.content.Context
import com.youxiang8727.screenshothelper.domain.usecase.CheckPermissionUseCase
import com.youxiang8727.screenshothelper.domain.usecase.ManageFloatingWindowUseCase
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
    fun provideCheckPermissionUseCase(
        @ApplicationContext context: Context
    ): CheckPermissionUseCase = CheckPermissionUseCase(context)

    @Provides
    @Singleton
    fun provideManageFloatingWindowUseCase(
        @ApplicationContext context: Context
    ): ManageFloatingWindowUseCase = ManageFloatingWindowUseCase(context)
}
