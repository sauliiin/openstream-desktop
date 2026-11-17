package androidx.room

/**
 * `room-ktx`'s `withTransaction`, which only exists for Android. With a driver,
 * DAO calls made inside the writer connection's coroutine join its transaction.
 */
suspend fun <R> RoomDatabase.withTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
