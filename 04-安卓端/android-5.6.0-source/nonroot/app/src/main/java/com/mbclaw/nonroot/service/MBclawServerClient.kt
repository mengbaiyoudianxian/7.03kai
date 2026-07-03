package com.mbclaw.nonroot.service

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

/**
 * MBclaw 服务端 API 客户端
 *
 * 连接到用户的云服务器（47.83.2.188 或自定义），无需 VPN。
 * 使用 Token 配对模式（借鉴 OpenClaw DM pairing 思路但独立实现）。
 */
interface MBclawApi {
    @GET("api/health")
    suspend fun health(): Map<String, Any>

    @GET("api/projects")
    suspend fun getProjects(): List<Map<String, Any>>

    @POST("api/projects/{id}/sessions")
    suspend fun createSession(@Path("id") projectId: Int, @Body body: Map<String, Any>): Map<String, Any>

    @POST("api/sessions/{id}/messages")
    suspend fun sendMessage(@Path("id") sessionId: Int, @Body body: Map<String, Any>): Map<String, Any>

    @GET("api/projects/{id}/memory/durable")
    suspend fun getMemory(@Path("id") projectId: Int): Map<String, Any>

    @GET("api/search")
    suspend fun searchMemory(@Query("q") query: String): List<Map<String, Any>>

    @POST("api/projects/{id}/search/prefetch")
    suspend fun prefetchMemory(@Path("id") projectId: Int, @Body body: Map<String, Any>): List<Map<String, Any>>

    @GET("api/providers/mimo/status")
    suspend fun mimoStatus(): Map<String, Any>

    @POST("api/agent/run")
    suspend fun runAgent(@Body body: Map<String, Any>): Map<String, Any>
}

class MBclawServerClient(serverUrl: String, apiKey: String) {
    val api: MBclawApi = Retrofit.Builder()
        .baseUrl(serverUrl.trimEnd('/') + "/")
        .addConverterFactory(GsonConverterFactory.create())
        .client(
            okhttp3.OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("X-MBclaw-Client", "android-root")
                        .addHeader("X-MBclaw-Version", "0.2.0")
                        .build()
                    chain.proceed(req)
                }
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )
        .build()
        .create(MBclawApi::class.java)
}
