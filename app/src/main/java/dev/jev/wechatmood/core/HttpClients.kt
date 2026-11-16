package dev.jev.wechatmood.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 共享连接池与线程池，各接口只在自己那份 builder 上改超时。
 *
 * retryOnConnectionFailure 关掉：/chat/completions 按次计费，OkHttp 默认的
 * 连接层静默重发可能让一次点击扣两次钱。重试只在上层确认没产生响应时做。
 */
object HttpClients {
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
