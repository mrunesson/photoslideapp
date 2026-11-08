package org.linuxalert.photo_slide_app.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.sse.EventSource
import okhttp3.sse.EventSources
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import org.linuxalert.photo_slide_app.data.PhotoApi

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder().create()

    @Provides
    @Singleton
    fun provideEventSourceFactory(): EventSource.Factory = EventSources.createFactory(
        OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build(),
    )

    @Provides
    @Singleton
    fun providePhotoApi(gson: Gson): PhotoApi = Retrofit.Builder()
        .baseUrl("http://localhost/")
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(PhotoApi::class.java)
}