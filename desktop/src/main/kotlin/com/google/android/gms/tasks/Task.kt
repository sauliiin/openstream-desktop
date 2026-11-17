package com.google.android.gms.tasks

import java.util.concurrent.CompletableFuture

/** Play services' `Task`, over a `CompletableFuture`. */
class Task<T> internal constructor(private val future: CompletableFuture<T>) {
    val isComplete: Boolean get() = future.isDone
    val isSuccessful: Boolean get() = future.isDone && !future.isCompletedExceptionally
    val exception: Exception?
        get() = if (!future.isCompletedExceptionally) null else runCatching { future.join(); null }
            .exceptionOrNull()?.let { (it.cause ?: it) as? Exception ?: RuntimeException(it) }
    val result: T get() = future.join()

    fun addOnCompleteListener(listener: (Task<T>) -> Unit): Task<T> = apply {
        future.whenComplete { _, _ -> listener(this) }
    }

    fun addOnSuccessListener(listener: (T) -> Unit): Task<T> = apply {
        future.whenComplete { value, error -> if (error == null) listener(value) }
    }

    fun addOnFailureListener(listener: (Exception) -> Unit): Task<T> = apply {
        future.whenComplete { _, error -> if (error != null) listener(exception ?: RuntimeException(error)) }
    }

    companion object {
        fun <T> run(block: () -> T): Task<T> = Task(CompletableFuture.supplyAsync(block))
    }
}
