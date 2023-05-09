package com.river.connector.http

import com.river.core.mapParallel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.jdk9.asPublisher
import kotlinx.coroutines.jdk9.collect
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers.fromPublisher
import java.net.http.HttpResponse
import java.net.http.HttpResponse.*
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow.Publisher

private val DefaultHttpClient: HttpClient = HttpClient.newHttpClient()

suspend fun <T> HttpClient.coSend(request: HttpRequest, bodyHandler: BodyHandler<T>) =
    sendAsync(request, bodyHandler).await()

fun Flow<ByteBuffer>.asBodyPublisher(
    contentLength: Long = 0
): HttpRequest.BodyPublisher =
    if (contentLength < 1) fromPublisher(asPublisher())
    else fromPublisher(asPublisher(), contentLength)

suspend fun <T> HttpRequest.coSend(
    bodyHandler: BodyHandler<T>,
    client: HttpClient = DefaultHttpClient
): HttpResponse<T> = client.coSend(this, bodyHandler)

fun method(
    name: String,
    url: String,
    f: RequestBuilder.() -> Unit = {}
) = RequestBuilder(url, name.uppercase()).also(f).build()

fun get(
    url: String,
    f: RequestBuilder.() -> Unit = {}
): HttpRequest = method("GET", url, f)

fun post(
    url: String,
    f: RequestBuilder.() -> Unit = {}
): HttpRequest = method("POST", url, f)

fun put(
    url: String,
    f: RequestBuilder.() -> Unit = {}
): HttpRequest = method("PUT", url, f)

fun delete(
    url: String,
    f: RequestBuilder.() -> Unit = {}
): HttpRequest = method("DELETE", url, f)

fun patch(
    url: String,
    f: RequestBuilder.() -> Unit = {}
): HttpRequest = method("PATCH", url, f)

fun <T> HttpResponse<Publisher<T>>.bodyAsFlow(): Flow<T> =
    flow { body().collect { emit(it) } }

fun <T, R> CoroutineScope.coMapping(
    handler: BodyHandler<T>,
    f: suspend ResponseInfo.(T) -> R
): BodyHandler<R> =
    BodyHandler { responseInfo ->
        val inner = handler.apply(responseInfo)

        object : BodySubscriber<R> {
            override fun onSubscribe(subscription: java.util.concurrent.Flow.Subscription?) =
                inner.onSubscribe(subscription)

            override fun onNext(item: MutableList<ByteBuffer>?) =
                inner.onNext(item)

            override fun onError(throwable: Throwable?) =
                inner.onError(throwable)

            override fun onComplete() =
                inner.onComplete()

            override fun getBody(): CompletionStage<R> =
                future { f(responseInfo, inner.body.await()) }
        }
    }

val ofString = BodyHandlers.ofString()
val ofLines = BodyHandlers.ofLines()
val ofByteArray = BodyHandlers.ofByteArray()
val discarding = BodyHandlers.discarding()

val ofFlow: BodyHandler<Flow<ByteBuffer>> =
    BodyHandler {
        val subscriber = BodySubscribers.ofPublisher()

        object : BodySubscriber<Flow<ByteBuffer>> {
            override fun onSubscribe(subscription: java.util.concurrent.Flow.Subscription?) {
                subscriber.onSubscribe(subscription)
            }

            override fun onNext(item: MutableList<ByteBuffer>?) {
                subscriber.onNext(item)
            }

            override fun onError(throwable: Throwable?) {
                subscriber.onError(throwable)
            }

            override fun onComplete() {
                subscriber.onComplete()
            }

            override fun getBody(): CompletionStage<Flow<ByteBuffer>> =
                subscriber
                    .body
                    .thenApply { s -> flow { s.collect { it.forEach { emit(it) } } } }
        }
    }

fun <T> Flow<HttpRequest>.sendAndHandle(
    bodyHandler: BodyHandler<T>,
    parallelism: Int = 1,
    httpClient: HttpClient = DefaultHttpClient,
): Flow<HttpResponse<T>> =
    mapParallel(parallelism) { it.coSend(bodyHandler, httpClient) }

fun <T> Flow<HttpRequest>.sendAndHandle(
    parallelism: Int = 1,
    httpClient: HttpClient = DefaultHttpClient,
    handle: CoroutineScope.() -> BodyHandler<T>,
): Flow<HttpResponse<T>> =
    flow {
        emitAll(
            coroutineScope { sendAndHandle(handle(), parallelism, httpClient) }
        )
    }
