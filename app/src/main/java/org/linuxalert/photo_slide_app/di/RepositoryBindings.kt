package org.linuxalert.photo_slide_app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.linuxalert.photo_slide_app.data.OkHttpPhotoEventSource
import org.linuxalert.photo_slide_app.data.PhotoEventSource
import org.linuxalert.photo_slide_app.data.PreferenceServerAddressStore
import org.linuxalert.photo_slide_app.data.ServerAddressStore

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindings {
    @Binds
    abstract fun bindPhotoEventSource(implementation: OkHttpPhotoEventSource): PhotoEventSource

    @Binds
    abstract fun bindServerAddressStore(implementation: PreferenceServerAddressStore): ServerAddressStore
}